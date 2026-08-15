# MinIO 图片存储与配置管理设计

## 目标

在配置管理中新增独立的 MinIO 配置页面，并将图片故事工作台生成的参考图、镜头底图和最终合成图统一存入 MinIO，替代容器内本地目录。实现复用 `ai-datahub` 的 MinIO 连接方式，但使用独立私有 Bucket，避免与 DataHub 的公开资源、清理策略和目录结构耦合。

## 已确认边界

- MinIO 是图片故事资产的唯一存储后端，不提供本地/MinIO 切换开关。
- 复用 `ai-datahub` 当前 MinIO 服务与凭据，但使用独立 Bucket `english-material`。
- Bucket 内统一使用基础前缀 `image-story`。
- Bucket 保持私有，不设置匿名读取策略。
- 浏览器继续通过 `/api/image-assets/{assetId}` 获取图片，不直接访问 MinIO。
- 当前环境没有历史图片批次，因此不实现旧本地资产迁移或读取回退。
- 不把 Endpoint、Access Key、Secret Key 写入源码、文档、Git 提交或日志；部署后通过受控接口写入运行配置。

## 配置模型

新增单例 MinIO 配置，字段与 `ai-datahub` 对齐：

- `enabled`：是否启用图片存储。
- `endpoint`：MinIO 服务地址，使用 `host:port` 形式，不携带路径、查询参数或凭据。
- `accessKeyId`：访问键 ID。
- `secretAccessKey`：访问密钥。
- `useSsl`：是否使用 HTTPS。
- `bucketName`：固定初始化为 `english-material`，允许管理员显式修改。
- `basePath`：固定初始化为 `image-story`，作为全部对象键的公共前缀。
- `updatedAt`：乐观并发令牌。

配置使用独立数据库实体与表，不混入 AI Provider 或本地 CLI JSON。GET 响应永远不返回 Secret Key，只返回 `secretConfigured`；更新时 Secret Key 留空表示保留现有值。Endpoint、Access Key 和错误消息都进行长度与格式约束，任何错误不得回显 Secret Key。

## 配置页面

配置管理侧边栏在“图片模型配置”后新增“MinIO 配置”。页面提供：

- Endpoint、Access Key、Secret Key、HTTPS、Bucket、基础路径和启用状态表单。
- “测试连接”按钮：验证身份、Bucket 访问和对象写读删能力，不持久化表单。
- “保存配置”按钮：使用 `updatedAt` 做乐观并发校验；保存前执行同样的连接验证。
- 密钥输入框保存后清空并提示“已配置”；前端状态不保存服务端已有密钥。

页面加载失败只影响 MinIO 页面，不阻断数据库、AI、CLI、图片模型或工作台页面。

## 后端 API

新增以下接口：

- `GET /api/minio/config`：读取脱敏配置。
- `PUT /api/minio/config`：验证并保存配置；Secret Key 为空时保留已有值。
- `POST /api/minio/config/test`：使用表单值测试连接；Secret Key 为空时可复用已保存密钥。

测试与保存流程在私有 Bucket 中使用随机探测对象执行“写入少量字节 → 读取并比对 → 删除”。Bucket 不存在时允许创建；不设置公共策略。探测失败必须尽力删除临时对象，并返回有界中文错误。

## 图片资产数据流

`ImageAssetStore` 保持现有业务接口，但底层改为 MinIO：

1. 创建图片批次前调用 `assertWritable()`，确认配置启用且 Bucket 具备写读删能力。
2. 生成图片后构造受控对象键：`image-story/{runId}/{assetKey}.{ext}`。
3. 使用只创建、不覆盖语义写入对象；重复键直接失败。
4. 写入成功后保存 `ImageAsset` 数据库记录。记录继续保存相对对象键、MIME、尺寸和 SHA-256，不保存凭据或公开 URL。
5. 数据库保存失败时，按对象键和预期 SHA 删除本次新对象；不删除无法证明属于本次写入的对象。
6. 资产读取时用数据库 ID 查到对象键，从 MinIO 有界读取并重新校验长度、MIME 和 SHA-256，再返回原有 HTTP 响应。
7. 资产删除继续使用对象键与 SHA 校验，防止误删被替换的对象。

对象键沿用现有 runId、assetKey 和扩展名白名单；禁止绝对路径、`.`、`..`、反斜杠、控制字符和超长字段。

## 依赖与组件边界

- 引入官方 MinIO Java SDK。
- `MinioConfigService` 负责配置规范化、脱敏、乐观并发和客户端参数构造。
- `MinioConnectionVerifier` 负责 Bucket 检查/创建及探测对象生命周期。
- `ImageAssetStore` 只负责图片对象写读删、边界限制和 SHA 校验，不负责配置页面 DTO。
- MinIO 客户端按当前配置创建，不在日志中打印客户端参数；配置更新后后续调用立即使用新配置。

## 错误处理与安全

- 未配置、未启用、凭据缺失、Endpoint 非法、Bucket 非法分别返回明确错误。
- 网络、认证、Bucket 权限和对象操作错误映射为有界中文消息，不透传 SDK 响应体、Endpoint、Access Key 或 Secret Key。
- 单对象大小、图片像素、运行资产数量继续沿用现有上限；读取必须流式有界，禁止无界 `readAllBytes`。
- API 响应、运行快照、图片 metadata 和前端错误中均不得出现 MinIO 凭据。
- 私有 Bucket 不生成预签名公开链接，访问控制继续由应用资产 API 承担。

## 验证计划

后端测试覆盖：

- 配置默认值、规范化、脱敏、空密钥保留和 stale `updatedAt`。
- 非法 Endpoint/Bucket/basePath、缺失凭据和错误不泄密。
- Bucket 不存在时创建，已存在时复用，且不设置公开策略。
- 探测对象写读删、失败清理和不可写/不可读/不可删路径。
- 图片对象不覆盖写、读取 SHA/MIME/大小校验、数据库失败补偿删除。
- 图片批次在 MinIO 不可用时于任何模型调用和批次持久化前失败。

前端测试覆盖：

- 菜单顺序和页面隔离加载。
- 脱敏密钥展示、留空保留、字段校验、测试连接与保存请求合同。
- 保存期间防重复提交、乐观锁时间戳更新和迟到响应保护。
- MinIO 配置失败不影响其他配置页与图片工作台导航。

最终验收包括完整后端测试、完整前端测试、生产构建、Context Router 部署，以及使用 `ai-datahub` 的连接信息完成一次不泄密的 MinIO 写读删探测。不会在验收中调用图片模型。

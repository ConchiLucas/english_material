---
title: Antigravity 图片模型配置与兼容设计
summary: 在配置管理中增加独立图片模型入口，复用既有 AI Provider 存储和凭据，并兼容 Antigravity Gemini 图片接口。
---

# Antigravity 图片模型配置与兼容设计

## 目标

在“配置管理”的“本地 CLI 配置”下增加“图片模型配置”。该页面支持多个图片 Provider，并将 Antigravity 的 `gemini-3-pro-image` 配置为首个可用图片模型。若图片工作台尚无有效图片 Provider，完成 bootstrap 后同时把它选为当前图片 Provider；已有有效选择时不覆盖。配置本身不调用图片接口，不消耗图片额度。

图片工作台继续从同一份 AI Provider 配置中选择图片模型，不新增第二套密钥存储或图片 Provider 表。

## 方案选择

采用“独立页面、共享 Provider 存储”的方案：

- 配置菜单和表单独立，避免普通文本模型与图片模型混在同一编辑列表中。
- 后端仍以 `tb_ai_config` 中的 Provider JSON 为唯一事实源。
- 图片 Provider 通过固定 capabilities/options 被图片工作台识别。
- 普通 AI 配置、本地 CLI 配置和既有 Agent Provider 引用不迁移、不复制。

不采用独立图片模型表，因为它会重复保存 Base URL、API Key、启用状态和模型协议；也不只扩展现有 AI 配置表单，因为这不符合独立菜单的交互要求。

## 页面设计

配置管理二级菜单固定为：

1. 数据库配置
2. AI 配置
3. 本地 CLI 配置
4. 图片模型配置

图片模型页面沿用当前“左侧配置列表、右侧编辑器”的布局，只展示同时声明 `IMAGE_GENERATION` 和 `IMAGE_REFERENCE` 的 Provider。页面支持添加、选择、编辑、启用、删除和保存多个图片 Provider。

右侧表单展示：

- 启用状态
- 配置 ID
- 显示名称
- 接口类型，第一版固定为 `openai-compatible`
- Base URL
- API Key；保存后不回显，留空不覆盖同 ID 已保存密钥
- 模型名称
- 质量：`standard`、`medium`、`hd`，Antigravity 默认 `hd`
- 固定响应格式：`b64_json`
- 固定目标尺寸：`1536x864`

页面不允许手工删除两项图片能力，也不暴露任意 JSON options，避免保存出“页面显示可用、运行时才失败”的配置。

新增草稿默认值：

- ID：`antigravity-gemini-image`（若已占用则生成不冲突的草稿 ID）
- 名称：`Antigravity Gemini Image`
- 模型：`gemini-3-pro-image`
- capabilities：`IMAGE_GENERATION`、`IMAGE_REFERENCE`
- options：`responseFormat=b64_json`、`quality=hd`、`size=1536x864`
- enabled：`true`

## 安全复用已有 Antigravity 凭据

新增一个有明确用途的后端 bootstrap 操作。请求只提交来源 Provider ID，不接收或返回密钥。服务端从未脱敏的既有 Provider 配置中读取来源的 Base URL 和 API Key，创建目标图片 Provider：

- 来源必须存在、已启用、类型为 `openai-compatible`，并且 Base URL、API Key 非空。
- 目标 ID 已存在时不覆盖，避免破坏人工配置。
- 响应沿用脱敏 Provider DTO，API Key 始终为 `null`。
- 错误信息不得包含 API Key、完整请求 options 或带凭据的 URL。

页面在尚无 Antigravity 图片 Provider 时显示“从现有 Antigravity 配置添加”操作，来源列表只显示 ID/名称/模型，不显示密钥。部署更新后，本次任务会使用该操作将现有 Antigravity 凭据安全复制到 `antigravity-gemini-image`，不要求用户再次输入密钥。

普通新增图片 Provider 仍允许用户输入自己的 Base URL/API Key；保存继续使用现有同 ID 空密钥保留语义。

## Antigravity Images API 兼容

### 多参考图

无参考图继续调用 `/v1/images/generations`。有参考图调用 `/v1/images/edits`，multipart 图片字段依次命名为：

- 第一张：`image`
- 第二张：`image1`
- 第三张：`image2`
- 后续按相同规则递增

现有单张参考图协议保持兼容；最多 8 张和现有字节上限不变。

### 输出尺寸归一化

Provider 返回图片经现有 MIME、字节数和像素数安全检查后，统一归一化为调用要求的 `1536x864`：

- 已经是目标尺寸时原样返回。
- 其他尺寸先按中心区域裁切到目标宽高比，再使用 Java2D 高质量双三次插值缩放。
- 归一化结果编码为 PNG，并重新验证最终尺寸与像素边界。
- 原始图片不落库，数据库和文件系统只保存归一化后的安全结果。

该处理属于图片适配器的统一输出合同，不依赖 Antigravity 的名称或 URL；其他 OpenAI-compatible Provider 返回非精确像素时也得到相同行为。

## 数据流

1. 前端加载现有 `/api/ai/config`，派生图片 Provider 列表。
2. 用户选择已有 Antigravity 文本 Provider 作为安全凭据来源。
3. bootstrap 操作在后端复制 Base URL/API Key，写入新的图片 Provider，并返回脱敏配置。
4. 前端刷新统一 Provider 配置，并读取图片流程配置。流程尚无有效图片 Provider 时，前端携带当前 `updatedAt` 将新 Provider 保存为图片工作台当前选择；已有有效选择时保持原值。
5. 图片工作台随即能使用新 Provider，批次创建继续由 `ImageProviderPolicy` 做最终资格校验。
6. 正式运行时，适配器使用 Antigravity generations/edits 接口，并将返回图片归一化后再交给资产存储和文字合成。

## 失败处理

- 没有可复用来源时，页面提示先配置 Antigravity Provider 或手工填写图片 Provider。
- 来源缺少密钥、目标 ID 冲突、Provider 资格不合法时拒绝 bootstrap，现有配置不变。
- 图片响应无法解码、超过字节/像素上限或归一化失败时，当前图片步骤失败且不重试。
- 保存配置失败时保留页面草稿和未保存标记。
- 页面切换遵循配置管理现有交互；不会因打开页面自动发起生成或远程模型探测。

## 测试与验收

后端测试覆盖：

- bootstrap 从来源复制密钥但响应和日志不泄露密钥。
- 来源无效、无密钥和目标冲突时不写入。
- 图片 Provider 最终通过共享 `ImageProviderPolicy`。
- multipart 单图为 `image`，多图为 `image`、`image1`、`image2`。
- 非目标尺寸图片中心裁切并缩放为 `1536x864`；目标尺寸不重复编码。
- 超限、坏图和失败响应保持现有失败边界。

前端测试覆盖：

- 二级菜单顺序及“图片模型配置”入口。
- 页面只显示图片 Provider，不丢失普通 AI Provider。
- 默认 Antigravity 草稿字段和固定 capabilities/options。
- 多 Provider 添加、编辑、删除和保存。
- 安全凭据来源操作不要求或显示 API Key。
- 图片工作台没有有效 Provider 时自动选择新模型，已有有效选择时不覆盖。
- 保存失败保持草稿，成功后图片工作台能收到新 Provider。

最终验证包括后端完整测试、前端完整测试、前端生产构建和 diff 检查。配置完成后只验证 Provider 已出现在图片工作台；实际图片生成另行明确授权，避免无意消耗图片额度。

## 范围外

- 不在本次实现中生成测试图片或运行完整图片批次。
- 不新增视觉评审、自动重绘、候选图或额度统计。
- 不自动发现 Antigravity 账户中的所有模型；第一版默认 `gemini-3-pro-image`，其他模型由用户新增。
- 不把 Antigravity API Key 写入源码、文档、前端状态快照、运行历史或日志。

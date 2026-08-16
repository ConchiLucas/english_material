# Sub2API Grok 图片 Provider 接入设计

## 目标

在不覆盖现有 Antigravity 配置的前提下，为英语材料项目新增独立的 Sub2API Grok 图片 Provider，将图片工作流切换到该 Provider，并用项目实际请求合同验证能够生成图片。

## 配置设计

Provider 保存到项目现有 `tb_ai_config`，不在源码、文档、测试或日志中保存 API Key。配置固定为：

- ID：`sub2api-grok-image`
- 名称：`Sub2API Grok Image`
- 协议：`openai-compatible`
- Base URL：`http://host.docker.internal:18046/v1`
- 模型：`grok-imagine-image`
- 能力：`IMAGE_GENERATION`、`IMAGE_REFERENCE`
- Options：`responseFormat=b64_json`、`quality=hd`、`size=1536x864`
- 状态：启用

Base URL 使用 `host.docker.internal`，因为项目后端运行在 Docker 容器中，而 Sub2API 只映射到宿主机 `127.0.0.1:18046`。现已从后端容器探测该地址并获得 HTTP 401，证明网络可达且鉴权边界正常。

## 写入与切换

通过项目现有 `POST /api/ai/config` 保存完整 Provider 列表。读取接口返回的旧 Provider 密钥保持为空，后端现有“同 ID 空密钥保留”语义会保留它们的持久化密钥；新 Grok Provider 使用 CodeBuddy 当前 Sub2API Key。随后通过图片流程配置接口把当前图片 Provider 切换为 `sub2api-grok-image`，保留所有其他流程固定参数。

不直接修改 PostgreSQL JSON，不覆盖或删除 Antigravity Provider，也不改变默认文本 Provider。

## 验证

1. 重新读取 AI 配置，确认 Grok Provider 已出现、字段正确且 API 响应不回传密钥。
2. 重新读取图片流程，确认当前 Provider 已切换为 `sub2api-grok-image`。
3. 使用项目相同的 generations 请求合同实际调用一次：`grok-imagine-image`、`b64_json`、`hd`、`1536x864`。
4. 校验 HTTP 200、返回图片可解码、MIME 与像素尺寸有效；若 Provider 返回非目标尺寸，由项目既有归一化逻辑转换为 1536×864。
5. 若生成失败，区分 Sub2API 本地并发 429、上游额度/鉴权和参数兼容错误，不擅自提高账号并发或修改其他配置。

实际生成会消耗一次 Grok 图片额度，属于用户本次“确保项目能使用”的明确授权范围。

## 回滚

若验证失败，保留 Grok Provider 便于排查，但将图片流程切回原 Antigravity Provider；不删除任何既有配置。若用户明确要求删除，再通过正常配置接口移除 Grok Provider。

# Progress

## 2026-08-14 纯故事最终输出

- 用户确认最终格式：仅英文 `Scene N: Title` 和故事段落，无中文说明、清单、评分、修订记录或 Markdown。
- 根因调查完成：输出协议未下发，提取器缺失标记时容错为完整响应。
- 设计规格已提交：`ee55fa0 docs: design pure story output`。
- 实施计划已写入 `docs/superpowers/plans/2026-08-14-pure-story-output.md`，准备从严格 RED 测试开始。
- 首次暂存检查发现计划文件 EOF 多余空行，已记录并修正，未进入生产代码修改。
- 默认 Prompt 测试首次因 accessor 名称写错而编译失败；已改为 `defaultPrompt()`，该次不计为有效 RED。
- 文档组合补丁首次因链路上下文未匹配而未应用，未产生部分写入；已拆分为准确的小补丁。
- 严格故事协议完成 RED→GREEN：初始 14 项中 4 个行为失败，修复并将旧夹具迁移到 `Scene 1:` 契约后 14/14 通过；提交 `5d13de6`。
- 默认 Prompt 对齐完成有效 RED→GREEN：3 项中 1 项按预期失败，修改后与执行测试合计 17/17 通过；提交 `91a2b9a`。
- 已发起独立只读代码审查，主线程并行执行全量后端、前端测试与生产构建。
- 独立审查未发现 Critical，但发现 2 个 Important 解析缺口和 1 个 Important 测试缺口；已验证均适用于当前代码，开始逐项补 RED。
- 第一轮审查修复后聚焦 17/17、全量后端 58/58、前端 25/25 与构建通过；复审仍发现一组 Markdown 漏洞及 `$5` 误伤，进入第二轮 RED。
- 第二轮补充链接、引用、编号列表、行内代码、两列表格、`Revision Notes` 与 `$5` 用例，先得到 1 failure + 1 error 的有效 RED，修复后聚焦 18/18 通过；提交 `4df3364`。
- 最新全量验证：后端 59/59、前端 25/25、React 生产构建成功；仅保留既有 Vite 大包体积提示。
- 后续复审识别 `Rating`/`Scoring Report`/`Review Notes`、波浪号 Markdown 和无冒号独立审计标题；三组回归均先 RED 后 GREEN，提交 `dcb4640`、`4f8f449`，等待最终复审。
- 最终独立复审确认无 Critical/Important，可部署；提交后全量复验后端 59/59、前端 25/25、React 生产构建成功。
- 首次真实 20 词批次完成 12 个 Agent 后因修订 Agent 在结束边界外追加报告而失败，但 `finalStory` 保持干净初稿；新增提取与完整修订轮回归先得到 1 failure + 1 error 的 RED，兼容唯一干净故事块后聚焦 19/19 通过。
- 真实兼容修复提交 `64a51f1` 经独立复审确认无 Critical/Important；全量后端 60/60、前端 25/25、React 构建成功，并由 Context Router 操作 `fe8d0f1ea4c84775b2af50ae858ddc89` 成功部署。
- 最终批次 `141a0c49-9190-41f8-9c0f-8c1079c8dd7b` 使用同一组三年级上册 20 词，执行 23 个实际 Agent 步骤和 3 轮有界质量循环后以 `LIMIT_REACHED` 正常终止，`error=null`；两次修订均完成。
- 最终故事机器校验通过：以 `Scene 1:` 开头，不含星号、竖线、反引号、中文或审计标题；第二次修订的边界外报告仍完整保留在步骤原始输出中，未进入 `finalStory`。

- 2026-07-17：用户批准统一任务处理器与调用通道设计，设计文档已提交为 `d727f21`。
- 2026-07-17：开始当前分支实施准备；已确认 Java 模型、批次生成、开始执行、单条结果和 Python Worker 均存在 `cliId` 耦合。
- 2026-07-17：确定采用新增兼容字段而非批量改写历史数据，实施计划编写中。
- 2026-07-17：实施计划已保存到 `docs/superpowers/plans/2026-07-17-unified-execution-target.md` 并通过占位符、类型命名和规格覆盖自检；选择当前会话内联执行。
- 2026-07-17：Task 1 已添加统一调用通道目录的 RED 测试；首次测试命令发现仓库无 `mvnw`，已切换为 `mvn`。
- 2026-07-17：Task 1 生产代码已编译；沙箱测试运行被 GraalVM 下 Mockito/Byte Buddy 自附加限制阻断，需在宿主权限下复验。
- 2026-07-17：Task 1 宿主环境聚焦测试通过（1/1）；统一目录仅返回非敏感元数据。
- 2026-07-17：Task 2 按 RED/GREEN 完成，处理器与调用通道兼容解析测试 4/4 通过；四个领域模型已增加 nullable 快照字段，未更新历史记录。
- 2026-07-17：Java 配置能力校验、批次快照、无 CLI 启动、新建任务和执行日志快照均按 RED/GREEN 完成；最近聚焦测试 11/11 与 6/6 通过。
- 2026-07-17：Python Task 4 已添加调用通道解析 RED 测试；系统 Python 依赖组合不兼容，按启动脚本切换到 `python-worker/.venv/bin/python`。
- 2026-07-17：Python 调用通道解析、MiMo Provider ID、CLI/OpenAI/Anthropic 文本适配器、handler 路由、TTS/评分接线和结果快照均按 RED/GREEN 完成；全量 Worker 测试 34/34 通过。
- 2026-07-17：TaskResult Provider 单条/批量执行新增 RED 测试，宿主测试 8 项中 2 项按预期失败，确认 Java 仍强制要求 CLI；开始修复兼容传播与队列快照。
- 2026-07-17：TaskResult Java 兼容传播与正式结果队列快照完成；Provider 可无 CLI 执行，旧 CLI 校验保留，聚焦测试 9/9 通过；Python 批量验证端点已允许可选 cliId。
- 2026-07-17：React 任务配置已拆分任务处理器、运行调用通道和接入代码 CLI；任务/结果/日志展示真实调用通道，开始执行不再覆盖目标，省略结果均支持悬浮全文；生产构建通过。
- 2026-07-17：Java 全量测试 34/34、Python Worker 全量测试 34/34、Python 语法检查和 git diff 格式检查均通过；进入三服务启动与只读接口验收。
- 2026-07-17：根据独立代码审查修复显式 CLI 快照被接入 CLI 覆盖、显式 MiMo Provider 环境变量回退、旧记录任务配置回退、未知 handler、正式 TTS 单条入队和统一调用通道筛选。
- 2026-07-17：最终 Java 全量测试 41/41、Python Worker 全量测试 37/37、React 生产构建、Python 语法、启动脚本语法和 `git diff --check` 全部通过。
- 2026-07-17：已重启 Java、React、Python Worker；三端健康，队列 `QUEUED/RUNNING/RETRY_WAIT` 均为 0，未调用真实 AI/TTS。
- 2026-07-17：只读确认 `xiaomi-mimo-tts` 目录协议为 `mimo-tts`、能力为 `AUDIO_TTS`，数据库 Key 非空且公开配置接口不返回 Key。

- 2026-07-17：开始只读定位任务日志页面与批次 4483 的失败链路；尚未修改业务代码或数据。
- 2026-07-17：定位到三个需要悬浮全文的省略位置：顶部最近结果、执行结果、本次结果/错误。
- 2026-07-17：只读确认失败根因是 word-agent 缺少 `MIMO_API_KEY` / `WORD_AGENT_MIMO_API_KEY`，5 条结果均为同一 502 错误。
- 2026-07-17：进一步确认单条执行错误来自 FORMAL 结果误走验证接口；当前 `.env` 已有 MIMO key，但 word-agent 进程早于配置文件启动，需重启才会加载。
- 2026-07-17：用户确认真实 MiMo Key 维护在数据库；开始定位配置表与 word-agent 数据库读取边界。
- 2026-07-17：确认数据库 provider JSON 已有通用 API Key 字段，公开接口会脱敏；继续核对实际 MiMo provider。
# 2026-07-17 MiMo 数据库配置读取

- 已确认 `tb_ai_config.providers` 中 `xiaomi-mimo-tts` 的 Key 存在，数据库实际字段为 `api_key`、`base_url`。
- 已在 word-agent 当前 `main` 分支实现数据库优先、环境变量回退的配置解析器。
- 已通过 13 个单元/接口测试和本次变更文件 Ruff 检查。
- 已安全验证解析来源为 `database`，未调用真实 TTS，未输出 Key。
- 已重启 word-agent，健康接口返回 `status=ok`。

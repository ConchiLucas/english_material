# Task Plan

## Goal
修复故事运行的最终结果污染：新批次只保存纯英文场景标题与故事正文，完整 Agent 原始输出仍保留用于审计。

## Current Phase
Complete — independently approved, deployed, and verified with a real 20-word multi-round acceptance batch.

## Phases
1. [complete] 按 TDD 为故事运行时协议、严格提取和失败路径增加测试
2. [complete] 实现纯英文故事协议并让审核链只接收提取后的正文
3. [complete] 对齐默认作家/修订 Prompt 与当前事实文档
4. [complete] 执行后端、前端全量验证与独立审查
5. [complete] 通过 Context Router 部署并运行真实三年级批次验收

## Decisions
- 采用用户确认的方案 A：运行时追加不可编辑协议，后端严格提取，不增加最终整理 Agent。
- 最终结果保留 `Scene N: Plain English Title` 与英文段落，不允许 Markdown、中文说明、清单或修订记录。
- `StoryRunStep.outputText` 保留原始响应；`StoryRun.finalStory` 和下游审核只使用提取后的正文。
- 无协议或协议内不纯净时明确失败，不再回退为保存完整响应。
- 用户已明确要求直接在当前目录开发，本次继续使用当前 `main`，不创建 worktree。
- 不使用独立 worktree；用户明确要求在当前分支直接修改。
- `handlerKey` 表示做什么；`executorType + executorId` 表示通过谁调用。
- `executorType` 第一版仅允许 `CLI`、`AI_PROVIDER`。
- 接入阶段的 `onboardingCliId` 与运行调用通道分离。
- 新字段迁移期允许为空；旧记录按自身字段、任务配置、旧 `cliId`/载荷顺序回退。
- 不批量更新或删除已有 `tb_task_result`、`tb_task_run`、`tb_task_run_result`。
- Java 只负责编排和持久化；CLI、AI API、TTS 调用全部在 Python Worker。
- 失败重试不自动跨调用通道切换。

## Safety
- 不覆盖数据库中用户已编辑的 Agent Prompt；默认 Prompt 只影响后续缺失配置，运行时协议保证现有配置也生效。
- 正常路径不增加模型调用次数；真实验收只创建一个用户已批准规格中的批次。
- 不输出、记录或提交 API Key 正文。
- 不调用真实 AI 或 TTS 完成功能验证；使用单元测试和安全配置检查。
- 保留工作区中已有 MiMo、提示词边界和前端悬浮修复相关改动。
- 不修改外部业务项目。

## Errors Encountered
| Error | Attempt | Resolution |
| --- | --- | --- |
| `psql` 在当前环境不可用 | 1 | 使用已有安全配置验证结果和代码模型继续设计，不安装额外客户端。 |
| 实施计划 `git diff --cached --check` 报告 EOF 多余空行 | 1 | 用 `apply_patch` 删除末尾空行，重新暂存并复核。 |
| Prompt 测试误用不存在的 `systemPrompt()` accessor | 1 | 核对 `NodeDefinition` record 后改为真实的 `defaultPrompt()`，重新运行取得行为 RED。 |
| 文档组合补丁因链路上下文未匹配而整体未应用 | 1 | 重新读取准确段落，拆成小补丁分别更新后端、前端和链路文档。 |
| 实施计划使用了不存在的 `./mvnw` | 1 | 仓库没有 Maven Wrapper，后续统一使用系统 `mvn`。 |
| 沙箱内 Mockito/Byte Buddy 无法附加 GraalVM | 1 | 编译已通过；按既有项目验证方式在宿主权限下重跑同一 Maven 测试。 |
| 系统 Python 运行 Worker 测试出现 FastAPI/Starlette 参数不兼容 | 1 | 项目启动脚本明确使用 `python-worker/.venv`，后续测试统一用其解释器。 |
| zsh 提前展开 unittest 的 `test_*.py` 模式 | 1 | 对 `-p` 参数使用单引号后重跑，全量 34 项通过。 |
| Python Worker 虚拟环境未安装 `pytest` | 1 | 仅在项目 `.venv` 安装 pytest 8.4.2；全量 37 项通过。 |
| 全量 Python 测试新增调用通道校验后仍使用旧 mock | 1 | 补充统一目标解析 mock 与断言后通过，未放宽生产校验。 |
| 系统无 `psql` 命令 | 2 | 使用 Worker 已有 psycopg2 连接只读验证 Key 是否非空，未输出密钥。 |

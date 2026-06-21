# AGENTS.md

## 语言

- 默认使用简体中文回复用户。
- git commit message 使用简体中文。

## 命令行

- 遵守 `~/.codex/RTK.md`。
- Shell 命令默认使用 `rtk` 前缀，例如 `rtk rg ...`、`rtk git status --short`。
- 搜索文件优先使用 `rg`、`fd`；需要 JSON 处理时优先使用 `jq`。
- 涉及外部库、框架或工具的当前行为时，积极使用网络搜索和 Context7 MCP 查询最新官方文档。

## Codebase Memory

- 分析代码前，先阅读 `docs/codebase/README.md`。
- 根据任务归属继续阅读相关边界目录，例如 `docs/codebase/api-service/README.md` 或 `docs/codebase/message-service/README.md`。
- 涉及协议、共享抽象、部署或 legacy 入口时，同时阅读 `docs/codebase/shared/README.md`、`docs/codebase/deployment/README.md` 或 `docs/codebase/compatibility-app/README.md`。
- 代码变更如果影响服务职责、协议、配置、数据 ownership、测试入口、部署方式或关键交互语义，必须同步更新对应 `docs/codebase/**/README.md`。
- 不确定归属时，先读 `docs/codebase/shared/README.md`、`docs/mochat-technical-documentation.md` 和相关 `openspec/specs`。

## MultiAgent

- 只在子任务彼此独立、不会互相写冲突时使用 MultiAgent。
- 等待子代理时使用长超时；不要因为默认短超时就打断子代理。
- 默认不要打断子代理；补充信息时使用非 interrupt 输入。
- 只有在用户明确要求、任务明显跑偏、发生冲突或存在安全风险时，才允许 interrupt。

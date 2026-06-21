# History Seq Range and Comment Pass Design

## Goal

在保留当前 `seq` 游标历史查询语义的前提下，为现有 `GET /history` 增加 `startSeq/endSeq` 范围查询能力，并同步补充一轮关键中文注释、OpenSpec 与技术文档，且尽量不重构现有代码。

## Confirmed Constraints

- 保留当前已经落地的 `seq` 游标分页，不回退到旧的 `msgId` 游标模型。
- 采用单接口方案：继续复用 `GET /history`，不新增独立历史查询路由。
- `cursorSeq` 与 `startSeq/endSeq` 两种模式互斥。
- 两种模式统一硬上限 `50`，超出时直接截断到 `50`。
- 实现时尽量不重构当前代码；如果发现和现有功能有真实冲突，需要先中断并由用户决定是否保留旧行为。
- 本轮需要补充关键中文注释，但只写必要注释，不做冗余注释铺满代码。

## Current Context

当前历史查询链路已经存在：

- `HistoryController` 提供 `GET /history`
- `HistoryService` 负责统一默认 `limit`
- `JdbcHistoryRepository` 负责按 `conversation_id + seq` 执行 `ORDER BY seq DESC LIMIT ?`

当前系统事实已经明确是“`seq` 游标分页”，这一点在代码和技术文档中已经体现，但 OpenSpec change 和 tasks 里仍残留旧的 `msgId` 语义，需要一并收敛。

## Recommended Approach

继续沿用当前 `HistoryController -> HistoryService -> HistoryRepository -> JdbcHistoryRepository` 这条链路，只做最小增量：

- `HistoryController` 新增 `startSeq`、`endSeq` 参数并做互斥校验
- `HistoryService` 统一默认值与上限裁剪逻辑
- `HistoryRepository` 扩展为同时支持“游标模式”和“范围模式”
- `JdbcHistoryRepository` 新增 `BETWEEN startSeq AND endSeq ORDER BY seq DESC LIMIT ?` 查询

这样可以复用现有鉴权、访问控制、DTO 和返回结构，避免为了一个查询模式扩展去重构整条历史查询链路。

## API Contract

继续使用现有接口：

`GET /history`

### Cursor mode

参数：

- `sessionId`
- `conversationId`
- `cursorSeq`（可选）
- `limit`（可选）

语义保持不变：

- 无 `cursorSeq` 时返回该会话最新消息窗口
- 有 `cursorSeq` 时返回 `seq < cursorSeq` 的消息
- 返回顺序仍为 `seq DESC`

### Range mode

新增参数：

- `startSeq`
- `endSeq`

语义：

- 返回 `seq` 落在闭区间 `[startSeq, endSeq]` 内的消息
- 返回顺序仍为 `seq DESC`
- 如果范围内消息数超过 `50`，只返回该范围内“最新的 50 条”

### Validation rules

- `cursorSeq` 模式与 `startSeq/endSeq` 模式互斥
- 传了 `startSeq` 就必须同时传 `endSeq`，反之亦然
- `startSeq > endSeq` 直接返回 `400`
- `limit <= 0` 时使用默认值 `50`
- `limit > 50` 时截断到 `50`

## Implementation Boundaries

### Controller

- 在 `HistoryController` 增加范围参数读取和参数组合校验
- 保持现有会话鉴权与会话访问控制逻辑不变
- 返回结构仍为 `HistoryResponse(items)`，不改 DTO 字段

### Service

- 建议引入轻量查询对象，如 `HistoryQuery`
- 这个对象只封装：`conversationId`、`cursorSeq`、`startSeq`、`endSeq`、`limit`
- 统一在 service 层做默认值和上限裁剪，避免 controller/repository 各自维护一套规则

### Repository

- 保留当前游标 SQL
- 增加范围 SQL：`WHERE conversation_id = ? AND seq BETWEEN ? AND ? ORDER BY seq DESC LIMIT ?`
- 不改变当前消息映射结构和返回记录格式

## Comment Strategy

本轮只在非直观逻辑上补关键中文注释，重点放在：

- `HistoryController`：为什么两种查询模式互斥
- `HistoryService`：为什么统一在这里裁剪 `limit`
- `JdbcHistoryRepository`：为什么范围查询也使用 `DESC + LIMIT 50`
- 现有历史查询代码：明确“当前事实语义是 `seq`，不是 `msgId`”

不在简单 DTO、显而易见的 getter/setter、单行赋值上补注释。

## Spec and Doc Sync

本轮需要同步更新以下内容：

- `openspec/changes/single-node-cloud-native-im-phase1/specs/data-model-and-history-pagination/spec.md`
- `openspec/specs/data-model-and-history-pagination/spec.md`
- `openspec/changes/single-node-cloud-native-im-phase1/tasks.md`
- `docs/mochat-technical-documentation.md`

同步目标：

- 明确主历史分页事实来源是 `seq`
- 补充 `startSeq/endSeq` 范围查询语义
- 把统一 50 条上限写成明确规则

## Testing Strategy

严格按 TDD 实施：

1. 先在 controller 层补参数互斥与 `limit` 裁剪的失败测试
2. 再在 service 层补默认值和上限裁剪测试
3. 再在 repository 层补范围 SQL 与结果窗口测试
4. 最后更新 spec / docs，并跑完整验证

重点验证：

- `cursorSeq` 与 `startSeq/endSeq` 混用时报 `400`
- 只传单侧范围参数时报 `400`
- `startSeq > endSeq` 报 `400`
- 范围查询只返回闭区间内消息
- 范围超过 50 条时，只返回该区间里最新的 50 条
- 旧分页模式在统一上限为 50 后行为稳定

## Stop-and-Ask Conditions

如果出现以下情况，必须先中断并让用户判断：

- 发现现有调用方真实依赖 `limit > 50` 行为
- 发现已有功能真实依赖旧的 `/history` 参数兼容形式
- 发现为了支持范围查询必须连带重构 history 相关类的大面积结构
- 发现新的历史查询语义会和其他尚未落地的功能契约冲突

## Success Markers

- `/history` 同时支持 `cursorSeq` 和 `startSeq/endSeq` 两种模式
- 两种模式统一默认值和 50 条硬上限
- 关键历史查询代码补入必要中文注释
- OpenSpec / tasks / 技术文档都与当前事实保持一致
- 相关测试通过，且不引入额外架构重构

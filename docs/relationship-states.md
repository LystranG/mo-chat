# MoChat 关系状态说明

本文档说明 MoChat 中两套独立的关系状态：好友关系 与 群成员关系。

---

## 一、好友关系（Private Relationship）

好友关系定义在两个用户之间，存储在 `user_friendships` 表中。

### 数据库约束

```sql
CREATE TABLE IF NOT EXISTS user_friendships (
  id           BIGINT PRIMARY KEY,
  uid_1        BIGINT NOT NULL,
  uid_2        BIGINT NOT NULL,
  status       TEXT   NOT NULL,
  blocked_by   SMALLINT,
  ...
  CONSTRAINT user_friendships_status_chk CHECK (status IN ('ok', 'blocked')),
  CONSTRAINT user_friendships_uid_order_chk CHECK (uid_1 < uid_2),
  CONSTRAINT user_friendships_pair_uniq UNIQUE (uid_1, uid_2)
);
```

### 状态说明

| 数据库值 | 代码枚举 | 含义 | 业务影响 |
|---|---|---|---|
| `ok` | `ACTIVE` | 正常好友关系 | 可以互相发送私聊消息、进行私聊通话 |
| `blocked` | `BLOCKED` | 已拉黑 | 不能发送私聊消息、不能进行私聊通话 |
| 无记录 | `NOT_FRIEND` | 不是好友 | 不能发送私聊消息、不能进行私聊通话 |

### 关键规则

- `uid_1 < uid_2`：无论谁发起好友关系，数据库里始终把较小的用户 ID 放在 `uid_1`，较大的放在 `uid_2`，保证唯一性。
- `blocked_by`：当 `status = 'blocked'` 时，该字段表示是谁拉黑谁（`0` 表示 `uid_1` 拉黑了 `uid_2`，`1` 表示 `uid_2` 拉黑了 `uid_1`）。
- 当 `status = 'ok'` 时，`blocked_by` 必须为 `NULL`。



---

## 二、群成员关系（Group Membership）

群成员关系定义在某个用户和某个群之间，存储在 `group_memberships` 表中。

### 数据库约束

```sql
CREATE TABLE IF NOT EXISTS group_memberships (
  id           BIGINT PRIMARY KEY,
  group_id     BIGINT NOT NULL,
  user_id      BIGINT NOT NULL,
  role         TEXT   NOT NULL,
  status       TEXT   NOT NULL,
  ...
  CONSTRAINT group_memberships_role_chk CHECK (role IN ('owner', 'member')),
  CONSTRAINT group_memberships_status_chk CHECK (status IN ('active', 'left', 'kicked')),
  CONSTRAINT group_memberships_pair_uniq UNIQUE (group_id, user_id)
);
```

### 状态说明

| 数据库值 | 含义 | 业务影响 |
|---|---|---|
| `active` | 活跃成员，仍在群中 | 可以接收和发送群消息、参与群通话、收到群通话邀请 |
| `left` | 主动退群 | 不再是群成员，不会收到群消息和群通话邀请 |
| `kicked` | 被踢出群 | 同 `left`，由群主/管理员移除 |

### 角色说明

| 数据库值 | 含义 |
|---|---|
| `owner` | 群主，群的创建者 |
| `member` | 普通成员 |






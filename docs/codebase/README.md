# MoChat Codebase Memory

MoChat 当前默认运行形态是五个 dedicated services 加共享 PostgreSQL、Redis 和 RocketMQ；音视频通话能力另接 LiveKit。读代码前先按任务归属阅读本目录的运行时边界记忆，再进入源码。

## 阅读顺序

1. 先读本文，确认任务属于哪个运行时边界。
2. 再读对应目录的 `README.md`。
3. 如果任务跨服务或涉及协议、配置、部署，继续读 `shared/` 或 `deployment/`。
4. 如果任务涉及 legacy `app`，读 `compatibility-app/`，不要把它当作默认生产入口。

## 边界索引

| 目录 | 何时阅读 | 主要代码路径 |
| --- | --- | --- |
| `access-gateway/` | TCP 长连接、bind、在线 route、heartbeat、duplicate login、drain、targeted delivery | `access-gateway-app`, `connection-module`, `common/session`, `common/directory` |
| `api-service/` | 登录、session authority、好友/群组、发送策略、history read-side、conversation state read-side | `api-service-app`, `logic-module/http`, `logic-module/service`, `logic-module/repository` |
| `message-service/` | 消息命令、幂等、MQ publish、sender ACK、在线投递、offline fallback | `message-service-app`, `logic-module/chat`, `message-module`, `infra-redis` |
| `persistence-service/` | MQ consume、消息事实持久化、conversation 推进、receipt/state、Flyway migration | `persistence-service-app`, `persistence-module` |
| `call-service/` | 音视频通话 HTTP/WebSocket 信令、LiveKit token、进程内房间状态、通话离线通知 | `call-service-app`, `call-module` |
| `shared/` | 跨服务契约、protobuf、session/event/offline/id/seq/lock 抽象、runtime config | `common`, `protocol`, `service-runtime`, `message-module`, `infra-redis` |
| `deployment/` | Kubernetes、Dockerfile、环境变量、端口、kind 验证、native image | `deploy/kubernetes`, `docker-compose.yml`, service `Dockerfile`, service `application.yml` |
| `compatibility-app/` | legacy compatibility shell、回滚入口、旧单体装配 | `app` |

## 维护规则

- 代码变更影响服务职责、协议、配置、数据 ownership、测试入口、部署方式或关键交互语义时，必须同步更新对应 memory。
- 不确定归属时，先读 `shared/README.md`，再读 `docs/mochat-technical-documentation.md` 和相关 `openspec/specs`。
- 当参考文档和源码不一致时，以当前源码、Flyway migration、测试和稳定技术总览为准。
- `docs/ddl/phase1.sql` 是参考稿，不是数据库 truth source；数据库事实以 `persistence-module/src/main/resources/db/migration` 和 JDBC 仓储为准。

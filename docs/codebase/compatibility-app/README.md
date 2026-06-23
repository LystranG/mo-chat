# Compatibility App Codebase Memory

## 职责

`app` 是 legacy compatibility shell，不是默认运行拓扑。它保留旧单体装配能力，用于显式回滚或兼容模式：

- Micronaut legacy 入口：`app/src/main/java/com/github/lystran/mochat/Application.java`
- 单体 runtime factory：`app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- legacy TCP lifecycle：`app/src/main/java/com/github/lystran/mochat/runtime/ConnectionRuntimeLifecycle.java`
- legacy persistence lifecycle：`app/src/main/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycle.java`
- legacy in-memory channel directory / Snowflake id：`InMemoryChannelDirectory.java`、`SnowflakeIdGenerator.java`
- 默认仍可装配 Redis、DataSource、Flyway、RocketMQ producer、Netty server 等基础组件。
- 只有显式打开开关时才恢复旧 persistence owner 和 inbound consumer。

## 非职责

- 不是默认开发、部署或生产入口。
- 不是 dedicated services 的 source of truth。
- 默认不拥有 persistence ownership。
- 默认不启动 legacy inbound consumer。
- 不代表 Kubernetes-first 拓扑。

## 主要代码路径

- `app/src/main/java/com/github/lystran/mochat/Application.java`
- `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- `app/src/main/java/com/github/lystran/mochat/runtime/ConnectionRuntimeLifecycle.java`
- `app/src/main/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycle.java`
- `app/src/main/resources/application.yml`
- `app/src/main/resources/application-local.yml`
- `app/build.gradle.kts`
- `build.gradle.kts` 中 `deployableNativeAppImages[":app"] = "mo-chat"`

## 核心数据流和交互

默认配置在 `app/src/main/resources/application.yml`：

- HTTP `MOCHAT_HTTP_PORT=8080`
- TCP `MOCHAT_TCP_PORT=9000`
- Redis `redis://localhost:6379`
- PostgreSQL `jdbc:postgresql://localhost:5432/mochat`
- RocketMQ `localhost:9876`
- TLS 默认启用并允许 self-signed
- `MOCHAT_LEGACY_PERSISTENCE_ENABLED=false`
- `MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=false`

`MochatRuntimeFactory` 默认仍创建：

- `DataSource`
- `Flyway`
- `RedisClient`
- `RedisEventBus`
- `RedisOfflineQueue`
- `RedisConversationSeqGenerator`
- `RedisIdempotencyStore`
- `DefaultMQProducer`
- `RocketMqProducer`
- `NettyChatServer`

显式打开 `mochat.legacy.persistence.enabled=true` 后才创建：

- `MessageRepository`
- `ConversationRepository`
- `GroupMessageCache`
- `ReceiptConversationStateStore`
- `MqConsumer`
- `DefaultMQPushConsumer`
- `RocketMqPersistenceConsumer`

当前注意点：

- “compatibility shell” 不表示完全不装配基础设施；关键边界是默认不拥有 persistence ownership、不启动 legacy inbound consumer。
- `app/src/main/resources/application.yml` 的 Redis/Postgres/RocketMQ 默认地址没有全部用环境变量包裹，`application-local.yml` 才提供更多环境变量覆盖。
- `app` 仍在根 `build.gradle.kts` 的 native image map 中，可构建 `mo-chat`，但这不代表它是默认部署目标。

## 配置和运行入口

- 运行：`./gradlew :app:run`
- main class：`com.github.lystran.mochat.Application`
- 配置文件：`app/src/main/resources/application.yml`、`app/src/main/resources/application-local.yml`
- 回滚/兼容模式关键开关：
  - `MOCHAT_LEGACY_PERSISTENCE_ENABLED=true`
  - `MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED=true`

## 测试入口

- `app/src/test/java/com/github/lystran/mochat/AppRuntimeAssemblyTest.java`
- `app/src/test/java/com/github/lystran/mochat/AppSmokeTest.java`
- `app/src/test/java/com/github/lystran/mochat/AppTestSupport.java`
- `app/src/test/java/com/github/lystran/mochat/FlywayMigrationBootstrapTest.java`
- `app/src/test/java/com/github/lystran/mochat/ServiceTopologyStructureTest.java`
- `app/src/test/java/com/github/lystran/mochat/runtime/MochatRuntimeFactoryTest.java`
- `app/src/test/java/com/github/lystran/mochat/runtime/PersistenceRuntimeLifecycleTest.java`
- `app/src/test/java/com/github/lystran/mochat/runtime/ConnectionRuntimeLifecycleTest.java`

关键测试事实：

- `AppRuntimeAssemblyTest.applicationContextUsesApplicationYamlDefaultRuntimePropertiesWithoutPersistenceOwners()` 证明默认不装配 persistence owners / inbound consumer。
- `AppRuntimeAssemblyTest.applicationContextAssemblesInboundConsumerWhenLegacyPersistenceCompatibilityExplicitlyEnabled()` 证明显式兼容开关可恢复旧 inbound chain。

## 变更时必须同步更新

- `app` 从 compatibility shell 变回默认入口，或被移除。
- 修改 `MOCHAT_LEGACY_PERSISTENCE_ENABLED` / `MOCHAT_MESSAGE_SERVICE_INBOUND_CONSUMER_ENABLED` 默认值。
- 修改 `MochatRuntimeFactory` 默认装配的 bean，尤其 persistence owner、MQ consumer、inbound consumer。
- 修改 `app/src/main/resources/application.yml` 中端口、TLS、Redis/Postgres/RocketMQ 默认。
- 修改 `:app:run` 或 native image 名称 `mo-chat`。
- dedicated service 与 `app` 的 ownership 测试发生变化。


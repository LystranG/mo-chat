# Runtime Integration and User Persistence Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Turn MoChat into a minimally runnable integrated service by wiring the app runtime, persisting users in PostgreSQL, and connecting a real RocketMQ consume -> DB commit -> ack path.

**Architecture:** Keep `app` as the composition root and add the thinnest possible runtime adapters in owner modules. Reuse existing business and persistence kernels, change bean selection with `@Requires`/fallback beans, and implement each task with TDD before moving on.

**Tech Stack:** Micronaut 4, Java 25, Netty, Lettuce, PostgreSQL JDBC, Flyway, RocketMQ client, JUnit 5, Mockito, Testcontainers

---

### Task 1: Wire app dependencies and runtime configuration

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/resources/application.yml`
- Create: `app/src/test/java/com/github/lystran/mochat/AppRuntimeAssemblyTest.java`

**Step 1: Write the failing test**

Create `app/src/test/java/com/github/lystran/mochat/AppRuntimeAssemblyTest.java` with a Micronaut context boot test that expects:

```java
assertTrue(context.containsBean(com.github.lystran.mochat.logic.http.AuthController.class));
assertTrue(context.containsBean(com.github.lystran.mochat.logic.service.UserService.class));
assertTrue(context.containsBean(com.github.lystran.mochat.logic.service.SessionService.class));
```

Pass only the minimal config required to boot the app without touching external services yet.

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests com.github.lystran.mochat.AppRuntimeAssemblyTest`

Expected: FAIL because `app` does not yet depend on the core modules and/or cannot resolve required runtime beans.

**Step 3: Write minimal implementation**

- Add `implementation(project(":logic-module"))`, `implementation(project(":connection-module"))`, `implementation(project(":infra-redis"))`, and `implementation(project(":persistence-module"))` to `app/build.gradle.kts`.
- Add embedded HTTP server runtime dependencies to `app/build.gradle.kts`.
- Expand `app/src/main/resources/application.yml` with namespaced runtime config for:
  - HTTP port
  - Netty TCP host/port/max-frame/heartbeat
  - Redis URI and topic/key prefixes
  - PostgreSQL JDBC URL/user/password
  - RocketMQ name server, producer group, consumer group, topic
  - TLS cert/key paths
  - ID generator worker/datacenter/epoch basics

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests com.github.lystran.mochat.AppRuntimeAssemblyTest`

Expected: PASS, or fail later on missing runtime beans that will be handled in Task 2.

**Step 5: Checkpoint**

No commit in this session because the user explicitly asked not to commit.

### Task 2: Add runtime factories and lifecycle beans

**Files:**
- Create: `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeProperties.java`
- Create: `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`
- Create: `app/src/main/java/com/github/lystran/mochat/runtime/NettyServerLifecycle.java`
- Create: `app/src/main/java/com/github/lystran/mochat/runtime/InMemoryChannelDirectory.java`
- Create: `app/src/main/java/com/github/lystran/mochat/runtime/SnowflakeIdGenerator.java`
- Modify: `app/src/test/java/com/github/lystran/mochat/AppRuntimeAssemblyTest.java`

**Step 1: Write the failing test**

Extend `AppRuntimeAssemblyTest` so the application context now expects these beans:

```java
assertNotNull(context.getBean(javax.sql.DataSource.class));
assertNotNull(context.getBean(com.github.lystran.mochat.common.event.EventBus.class));
assertNotNull(context.getBean(com.github.lystran.mochat.common.id.IdGenerator.class));
assertNotNull(context.getBean(com.github.lystran.mochat.connection.NettyChatServer.class));
```

Stub external connections where appropriate using `@Replaces` test beans so the test focuses on assembly.

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests com.github.lystran.mochat.AppRuntimeAssemblyTest`

Expected: FAIL because the factory and lifecycle beans do not exist yet.

**Step 3: Write minimal implementation**

- Add typed config records/classes in `MochatRuntimeProperties`.
- In `MochatRuntimeFactory`, create Micronaut beans for:
  - `DataSource`
  - Flyway bootstrap/migrate hook
  - Redis client, sync connection, sync commands, Pub/Sub connection
  - `EventBus`, `OfflineQueue`, `ConversationLock`, `ConversationSeqGenerator`, `IdempotencyStore`
  - `UserChannelDirectory<io.netty.channel.Channel>`
  - `IdGenerator`
  - RocketMQ producer client
  - `NettyChatServer`
- In `NettyServerLifecycle`, start/stop `NettyChatServer` with Micronaut lifecycle callbacks.

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests com.github.lystran.mochat.AppRuntimeAssemblyTest`

Expected: PASS.

**Step 5: Checkpoint**

No commit in this session because the user explicitly asked not to commit.

### Task 3: Replace in-memory user persistence with PostgreSQL

**Files:**
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/service/JdbcUserRepository.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/service/InMemoryUserRepository.java`
- Create: `logic-module/src/test/java/com/github/lystran/mochat/logic/service/JdbcUserRepositoryTest.java`
- Modify: `logic-module/build.gradle.kts`

**Step 1: Write the failing test**

Create `JdbcUserRepositoryTest` with PostgreSQL-backed integration tests that prove:

```java
assertEquals("alice", repository.create("alice", key).username());
assertArrayEquals(key, repository.findByUsername("alice").orElseThrow().identityPublicKey());
assertArrayEquals(key, repository.create("alice", otherKey).identityPublicKey());
```

Use Flyway against a Testcontainers PostgreSQL instance so the repository is tested against the real schema.

**Step 2: Run test to verify it fails**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.service.JdbcUserRepositoryTest`

Expected: FAIL because `JdbcUserRepository` does not exist.

**Step 3: Write minimal implementation**

- Add `JdbcUserRepository` with `@Requires(beans = DataSource.class)`.
- Inject `DataSource` and `IdGenerator`.
- Implement `findByUsername` with `SELECT id, username, public_key FROM users WHERE username = ?`.
- Implement `create` with `INSERT ... ON CONFLICT (username) DO NOTHING RETURNING ...`; if nothing is returned, read the existing row.
- Change `InMemoryUserRepository` to fallback only when no other `UserRepository` bean exists.
- Add any missing test dependencies in `logic-module/build.gradle.kts`.

**Step 4: Run test to verify it passes**

Run: `./gradlew :logic-module:test --tests com.github.lystran.mochat.logic.service.JdbcUserRepositoryTest`

Expected: PASS.

**Step 5: Checkpoint**

No commit in this session because the user explicitly asked not to commit.

### Task 4: Connect the RocketMQ consume -> DB commit -> ack loop

**Files:**
- Create: `persistence-module/src/main/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumer.java`
- Create: `persistence-module/src/test/java/com/github/lystran/mochat/persistence/RocketMqPersistenceConsumerTest.java`
- Modify: `persistence-module/build.gradle.kts`
- Modify: `app/src/main/java/com/github/lystran/mochat/runtime/MochatRuntimeFactory.java`

**Step 1: Write the failing test**

Create `RocketMqPersistenceConsumerTest` that verifies:

```java
consumer.onMessage(envelopeBytes);
verify(mqConsumer).persistMessage(expectedMessage);
assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, status);
```

and a second test where `persistMessage` throws and the consumer returns reconsume-later instead of success.

**Step 2: Run test to verify it fails**

Run: `./gradlew :persistence-module:test --tests com.github.lystran.mochat.persistence.RocketMqPersistenceConsumerTest`

Expected: FAIL because the runtime consumer adapter does not exist.

**Step 3: Write minimal implementation**

- Add RocketMQ client dependency to `persistence-module`.
- Implement `RocketMqPersistenceConsumer` as a thin adapter that:
  - deserializes the existing ordered message string payload into `MessageRepository.PersistedMessage`
  - calls `MqConsumer.persistMessage(...)`
  - returns success only after `persistMessage(...)` returns successfully
  - returns retry/reconsume on any persistence failure
- Expose the adapter from `app` factory and register/start the RocketMQ consumer lifecycle.

**Step 4: Run test to verify it passes**

Run: `./gradlew :persistence-module:test --tests com.github.lystran.mochat.persistence.RocketMqPersistenceConsumerTest`

Expected: PASS.

**Step 5: Checkpoint**

No commit in this session because the user explicitly asked not to commit.

### Task 5: Verify integrated startup and full test suite

**Files:**
- Modify: `app/src/test/java/com/github/lystran/mochat/AppRuntimeAssemblyTest.java`
- Modify: `docs/runbook.md`

**Step 1: Write the failing test**

Add or extend app-level smoke coverage so the test proves the embedded server path no longer exits as CLI-only and that the app can create the main runtime beans under configuration.

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests com.github.lystran.mochat.AppRuntimeAssemblyTest`

Expected: FAIL until the final wiring gaps are closed.

**Step 3: Write minimal implementation**

- Close any remaining bean wiring or conditional startup gaps.
- Update `docs/runbook.md` with the new runtime config keys and the expected startup behavior for HTTP/Netty listeners.

**Step 4: Run verification to verify it passes**

Run: `./gradlew :app:test --tests com.github.lystran.mochat.AppRuntimeAssemblyTest`

Expected: PASS.

**Step 5: Run final full verification**

Run: `./gradlew test`

Expected: BUILD SUCCESSFUL.

**Step 6: Checkpoint**

No commit in this session because the user explicitly asked not to commit.

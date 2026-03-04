# MoChat Phase 1 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement a single-node, high-throughput IM backend with connection/logic/persistence modules, per-conversation `seq`, RocketMQ async-flush pipeline, and private two-stage delivery receipts.

**Architecture:** A modular monolith split into `connection-module` (Netty transport only), `logic-module` (stateless business logic), and `persistence-module` (MQ consume + DB writes). Connection <-> logic communicate via EventBus (phase-1 default: Redis Pub/Sub). Per-conversation ordering is guaranteed by `seq` (Redis atomic) + per-conversation lock + RocketMQ ordered publish keyed by `conversationId`.

**Tech Stack:** Java 25, Micronaut, Netty (+ io_uring), Protobuf, PostgreSQL, Redis, Caffeine, RocketMQ (async flush), Gradle, GraalVM Native Image, Apache Commons Codec.

**Engineering discipline (MANDATORY):**
- Before implementing any integration point, query **Context7** for up-to-date docs (Micronaut, Netty io_uring, RocketMQ ordered messages, Micronaut Redis client, GraalVM native-image). Capture any critical API choices in this plan as you go.
- Use **Serena** during implementation for code navigation, symbol search/rename, and safe large refactors.

---

### Task 0: Create an isolated worktree (recommended)

**Files:**
- None

**Step 1: Create worktree**

Run: `git worktree add -b phase1-impl ../mo-chat-wt/phase1`
Expected: new worktree created

**Step 2: Verify worktree**

Run: `git -C ../mo-chat-wt/phase1 status`
Expected: clean or known dirty state

**Step 3: Commit boundary note**

Decide commit cadence: one logical feature per commit.

---

### Task 1: Gradle multi-module bootstrap

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `common/build.gradle.kts`
- Create: `protocol/build.gradle.kts`
- Create: `infra-redis/build.gradle.kts`
- Create: `connection-module/build.gradle.kts`
- Create: `logic-module/build.gradle.kts`
- Create: `persistence-module/build.gradle.kts`

**Step 1: Write a failing build check**

Run: `./gradlew projects`
Expected: FAIL after you start changing settings until all modules are included

**Step 2: Implement settings includes**

Edit `settings.gradle.kts` to include:
```kotlin
rootProject.name = "mo-chat"
include(
  "app",
  "common",
  "protocol",
  "infra-redis",
  "connection-module",
  "logic-module",
  "persistence-module",
)
```

**Step 3: Add shared Java 25 + repositories**

In root `build.gradle.kts`, configure subprojects:
```kotlin
subprojects {
  repositories { mavenCentral() }
  plugins.withId("java") {
    java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
  }
}
```

**Step 4: Verify build**

Run: `./gradlew projects`
Expected: PASS, lists all modules

**Step 5: Commit**

Run:
```bash
git add settings.gradle.kts build.gradle.kts */build.gradle.kts
git commit -m "chore(build): bootstrap gradle multi-module layout"
```

---

### Task 2: Micronaut app entrypoint and configuration skeleton

**Files:**
- Create: `app/src/main/java/com/github/lystran/mochat/Application.java`
- Create: `app/src/main/resources/application.yml`
- Create: `app/src/test/java/com/github/lystran/mochat/AppSmokeTest.java`

**Step 1: Context7 baseline (Micronaut + Gradle)**

Use Context7 to confirm:
- Micronaut Gradle plugin setup for Java
- test configuration

**Step 2: Write failing smoke test**

Create `app/src/test/java/com/github/lystran/mochat/AppSmokeTest.java`:
```java
package com.github.lystran.mochat;

import io.micronaut.runtime.Micronaut;
import org.junit.jupiter.api.Test;

class AppSmokeTest {
  @Test
  void appStarts() {
    Micronaut.run(Application.class).close();
  }
}
```

**Step 3: Minimal app main**

Create `app/src/main/java/com/github/lystran/mochat/Application.java`:
```java
package com.github.lystran.mochat;

import io.micronaut.runtime.Micronaut;

public class Application {
  public static void main(String[] args) {
    Micronaut.run(Application.class, args);
  }
}
```

**Step 4: Run test**

Run: `./gradlew :app:test`
Expected: PASS

**Step 5: Commit**

```bash
git commit -m "feat(app): add Micronaut entrypoint and smoke test"
```

---

### Task 3: Protocol module (Protobuf + fixed header framing)

**Files:**
- Create: `protocol/src/main/proto/mochat/v1/chat.proto`
- Create: `protocol/src/main/java/com/github/lystran/mochat/protocol/FrameConstants.java`
- Create: `protocol/src/main/java/com/github/lystran/mochat/protocol/MsgType.java`
- Create: `protocol/src/main/java/com/github/lystran/mochat/protocol/SerializerType.java`
- Create: `protocol/src/main/java/com/github/lystran/mochat/protocol/ErrorCode.java`
- Create: `protocol/src/test/java/com/github/lystran/mochat/protocol/ProtoRoundTripTest.java`

**Step 1: Context7 baseline (protobuf Gradle plugin)**

Use Context7 to confirm:
- Gradle protobuf plugin configuration
- generated sources path and IDE integration

**Step 2: Write failing proto roundtrip test**

Create `protocol/src/test/java/com/github/lystran/mochat/protocol/ProtoRoundTripTest.java`:
```java
package com.github.lystran.mochat.protocol;

import com.github.lystran.mochat.protocol.proto.Mochat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtoRoundTripTest {
  @Test
  void sendAckRoundTrips() throws Exception {
    var ack = Mochat.SendAck.newBuilder()
      .setClientMsgId(1L)
      .setMsgId(2L)
      .setSeq(3L)
      .setServerTimeMs(4L)
      .build();

    byte[] bytes = ack.toByteArray();
    var parsed = Mochat.SendAck.parseFrom(bytes);
    assertEquals(ack, parsed);
  }
}
```

**Step 3: Define `.proto` and enums**

Create `protocol/src/main/proto/mochat/v1/chat.proto` (keep stable names; match specs):
```proto
syntax = "proto3";
package mochat.v1;

option java_package = "com.github.lystran.mochat.protocol.proto";
option java_outer_classname = "Mochat";

message SendAck {
  int64 clientMsgId = 1;
  int64 msgId = 2;
  int64 seq = 3;
  int64 serverTimeMs = 4;
}

message ErrorResponse {
  int32 errorCode = 1;
  string message = 2;
}

message Heartbeat {
  int64 serverTimeMs = 1;
}

message PrivateMessageReq {
  string sessionId = 1;
  int64 clientMsgId = 2;
  int64 conversationId = 3;
  int64 toUid = 4;
  bytes nonce = 5;
  bytes ciphertext = 6;
}

message GroupMessageReq {
  string sessionId = 1;
  int64 clientMsgId = 2;
  int64 conversationId = 3;
  int64 groupId = 4;
  string text = 5;
}

message ChatMessageDelivery {
  int64 msgId = 1;
  int64 seq = 2;
  int64 serverTimeMs = 3;
  int64 conversationId = 4;
  int64 fromUid = 5;

  oneof payload {
    PrivatePayload privatePayload = 10;
    GroupPayload groupPayload = 11;
  }
}

message PrivatePayload {
  int64 toUid = 1;
  bytes nonce = 2;
  bytes ciphertext = 3;
}

message GroupPayload {
  int64 groupId = 1;
  string text = 2;
}

message ClientReceiveAck {
  string sessionId = 1;
  int64 conversationId = 2;
  int64 latestReceivedSeq = 3;
}

message DeliveredAck {
  int64 conversationId = 1;
  int64 toUid = 2;
  int64 latestReceivedSeq = 3;
  int64 serverTimeMs = 4;
}
```

**Step 4: Generate + run tests**

Run: `./gradlew :protocol:test`
Expected: PASS

**Step 5: Commit**

```bash
git commit -m "feat(protocol): add protobuf schemas and enums"
```

---

### Task 4: Common abstractions (EventBus, locks, seq, directory)

**Files:**
- Create: `common/src/main/java/com/github/lystran/mochat/common/event/EventBus.java`
- Create: `common/src/main/java/com/github/lystran/mochat/common/event/InProcessEventBus.java`
- Create: `common/src/main/java/com/github/lystran/mochat/common/lock/ConversationLock.java`
- Create: `common/src/main/java/com/github/lystran/mochat/common/lock/JucConversationLock.java`
- Create: `common/src/main/java/com/github/lystran/mochat/common/seq/ConversationSeqGenerator.java`
- Create: `common/src/main/java/com/github/lystran/mochat/common/id/IdGenerator.java`
- Create: `common/src/main/java/com/github/lystran/mochat/common/directory/UserChannelDirectory.java`
- Test: `common/src/test/java/com/github/lystran/mochat/common/lock/JucConversationLockTest.java`

**Step 1: Write failing lock ordering test**

Create `common/src/test/java/com/github/lystran/mochat/common/lock/JucConversationLockTest.java`:
```java
package com.github.lystran.mochat.common.lock;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JucConversationLockTest {
  @Test
  void serializesSameConversation() throws Exception {
    var lock = new JucConversationLock();
    var counter = new AtomicInteger();

    Callable<Integer> task = () -> {
      try (var ignored = lock.acquire(42L)) {
        return counter.incrementAndGet();
      }
    };

    var pool = Executors.newFixedThreadPool(2);
    var f1 = pool.submit(task);
    var f2 = pool.submit(task);
    assertNotEquals(f1.get(), f2.get());
    pool.shutdown();
  }
}
```

**Step 2: Implement minimal lock abstraction**

Define:
- `ConversationLock.acquire(conversationId)` returns `AutoCloseable` handle.
- JUC impl uses a keyed lock map or bounded cache.

**Step 3: Run tests**

Run: `./gradlew :common:test`
Expected: PASS

**Step 4: Commit**

```bash
git commit -m "feat(common): add core abstractions (event bus, locks, ids, directory)"
```

---

### Task 5: Redis infra (EventBus default, idempotency, seq generator, offline queue)

**Files:**
- Create: `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisEventBus.java`
- Create: `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisConversationSeqGenerator.java`
- Create: `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisIdempotencyStore.java`
- Create: `infra-redis/src/main/java/com/github/lystran/mochat/infra/redis/RedisOfflineQueue.java`
- Test: `infra-redis/src/test/java/com/github/lystran/mochat/infra/redis/RedisSeqGeneratorTest.java`

**Step 1: Context7 baseline (Micronaut Redis + pub/sub)**

Use Context7 to confirm:
- how to do Redis pub/sub in Micronaut (Lettuce)
- atomic INCR operations

**Step 2: Write failing seq test**

Create `infra-redis/src/test/java/.../RedisSeqGeneratorTest.java` using Testcontainers Redis.
Test: calling `next(conversationId)` twice returns increasing values.

**Step 3: Implement Redis seq generator**

Implement algorithm:
1) under ConversationLock
2) if redis key missing -> init from DB conversations.latest_seq (wire as callback)
3) INCR and return

**Step 4: Run tests**

Run: `./gradlew :infra-redis:test`
Expected: PASS

**Step 5: Commit**

```bash
git commit -m "feat(redis): add redis event bus and seq/idempotency primitives"
```

---

### Task 6: Connection module (Netty server, TLS, framing, routing)

**Files:**
- Create: `connection-module/src/main/java/com/github/lystran/mochat/connection/NettyChatServer.java`
- Create: `connection-module/src/main/java/com/github/lystran/mochat/connection/ChatChannelInitializer.java`
- Create: `connection-module/src/main/java/com/github/lystran/mochat/connection/InboundRouterHandler.java`
- Create: `connection-module/src/main/java/com/github/lystran/mochat/connection/OutboundEventSubscriber.java`
- Create: `connection-module/src/main/java/com/github/lystran/mochat/connection/RateLimitHandler.java`
- Create: `connection-module/src/main/java/com/github/lystran/mochat/connection/HeartbeatHandler.java`

**Step 1: Context7 baseline (Netty LengthFieldBasedFrameDecoder + TLS 1.3 + io_uring)**

Use Context7 to confirm:
- correct incubator io_uring dependency and bootstrap code
- LengthFieldBasedFrameDecoder configuration for our header
- SslContextBuilder TLS 1.3 setup

**Step 2: Write a failing framing test**

Use Netty EmbeddedChannel to verify:
- oversized frame rejected
- msgType parsed correctly

**Step 3: Implement pipeline**

Order:
- TLS handler
- LengthFieldBasedFrameDecoder (maxFrameLength)
- protobuf body decode by msgType
- RateLimitHandler
- HeartbeatHandler
- InboundRouterHandler (publish to EventBus)

**Step 4: Run tests**

Run: `./gradlew :connection-module:test`

**Step 5: Commit**

```bash
git commit -m "feat(connection): add netty transport, framing, and event routing"
```

---

### Task 7: Persistence module (DB migrations + MQ consumer)

**Files:**
- Create: `persistence-module/src/main/resources/db/migration/V1__phase1.sql` (derived from `docs/ddl/phase1.sql`)
- Create: `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`
- Create: `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MessageRepository.java`
- Create: `persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`

**Step 1: Write failing migration smoke test**

Use Testcontainers Postgres, run Flyway, assert tables exist.

**Step 2: Add Flyway + run migration**

Convert `docs/ddl/phase1.sql` into Flyway script `V1__phase1.sql`.

**Step 3: Implement persistence transaction**

In one transaction for each message:
- insert into messages
- conditional update conversations.latest_seq/latest_message_time (max seq wins)

**Step 4: Run tests**

Run: `./gradlew :persistence-module:test`

**Step 5: Commit**

```bash
git commit -m "feat(persistence): add db migrations and transactional message persistence"
```

---

### Task 8: Logic module (HTTP auth + social graph + groups)

**Files:**
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/http/AuthController.java`
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/http/FriendsController.java`
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/http/GroupsController.java`
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/service/SessionService.java`
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/service/UserService.java`

**Step 1: Write failing login/register tests**

MicronautTest:
- new user requires public key
- existing user rejects mismatched key

**Step 2: Implement base64 key validation (Apache Commons)**

Decode base64, verify 32 bytes.

**Step 3: Implement session store**

Redis authoritative + Caffeine L2.

**Step 4: Run tests**

Run: `./gradlew :logic-module:test`

**Step 5: Commit**

```bash
git commit -m "feat(logic): add auth/register with immutable X25519 key and sessions"
```

---

### Task 9: Logic module (message ingest, seq/msgId assignment, ordered MQ publish, ACK1)

**Files:**
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/mq/RocketMqProducer.java`

**Step 1: Context7 baseline (RocketMQ ordered messages)**

Use Context7 to confirm ordered publish API and sharding key usage.

**Step 2: Write failing idempotency + seq tests**

Test scenarios:
- duplicate (senderUid, clientMsgId) returns same msgId+seq
- seq strictly increases per conversation

**Step 3: Implement ingest algorithm**

Inside ConversationLock:
- idempotency get
- seq next (bootstrap if needed)
- msgId/time
- ordered publish (conversationId key)
- on success: store idempotency mapping and emit SEND_ACK

**Step 4: Run tests**

Run: `./gradlew :logic-module:test`

**Step 5: Commit**

```bash
git commit -m "feat(logic): implement seq+msgId assignment and ordered MQ publish with SEND_ACK"
```

---

### Task 10: Real-time delivery + offline queue

**Files:**
- Modify: `connection-module/src/main/java/com/github/lystran/mochat/connection/OutboundEventSubscriber.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/MessageIngestService.java`

**Step 1: Write failing offline queue cap test**

Ensure per-user offline list is trimmed to 50.

**Step 2: Implement delivery attempt + offline enqueue**

Flow:
- logic emits outbound delivery event
- connection writes if online
- on offline/write failure: logic enqueues offline entry (cap 50)

**Step 3: Implement login-triggered replay**

On login success:
- drain up to 50 entries
- attempt delivery

**Step 4: Run tests**

Run: `./gradlew test`

**Step 5: Commit**

```bash
git commit -m "feat(delivery): add offline queue and login-triggered replay"
```

---

### Task 11: Private second-stage receipts (ACK2)

**Files:**
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/ReceiptService.java`
- Modify: `logic-module/src/main/java/com/github/lystran/mochat/logic/chat/InboundMessageConsumer.java`
- Modify: `persistence-module/src/main/java/com/github/lystran/mochat/persistence/ConversationRepository.java`

**Step 1: Write failing receipt monotonicity test**

Scenario:
- receipt seq updates are max(existing, new)
- out-of-range receipt rejected

**Step 2: Implement receipt handling**

On CLIENT_RECEIVE_ACK:
- validate participant
- validate latestReceivedSeq <= server-known seq
- update conversations.uid_1_seq/uid_2_seq with GREATEST
- emit DELIVERED_ACK to sender if online

**Step 3: Run tests**

Run: `./gradlew :logic-module:test`

**Step 4: Commit**

```bash
git commit -m "feat(receipts): add private second-stage delivery receipts"
```

---

### Task 12: History + conversation state HTTP APIs (seq-based)

**Files:**
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/http/HistoryController.java`
- Create: `logic-module/src/main/java/com/github/lystran/mochat/logic/http/ConversationController.java`

**Step 1: Write failing history pagination test**

Scenario:
- query by conversationId + cursorSeq + limit returns correct window

**Step 2: Implement DB queries**

Use index `(conversation_id, seq DESC)`.

**Step 3: Implement conversation state endpoints**

- peer latest received seq (private)
- latest persisted seq + latest message time (private + group)

**Step 4: Run tests**

Run: `./gradlew :logic-module:test`

**Step 5: Commit**

```bash
git commit -m "feat(http): add seq-based history and conversation state APIs"
```

---

### Task 13: Group cache (Redis + Caffeine, update after DB commit)

**Files:**
- Create: `persistence-module/src/main/java/com/github/lystran/mochat/persistence/cache/GroupMessageCache.java`
- Modify: `persistence-module/src/main/java/com/github/lystran/mochat/persistence/MqConsumer.java`

**Step 1: Write failing cache eviction test**

Ensure per-group cache keeps max 500 messages.

**Step 2: Implement cache representation**

Recommended:
- Redis ZSET: score=seq, value=payload_base64
- Caffeine mirrors recent window

**Step 3: Update after commit only**

Ensure MqConsumer updates cache only after DB tx commit.

**Step 4: Run tests**

Run: `./gradlew :persistence-module:test`

**Step 5: Commit**

```bash
git commit -m "feat(cache): add group message cache updated after db commit"
```

---

### Task 14: Native image readiness

**Files:**
- Modify: `app/build.gradle.kts`
- Create/Modify: `app/src/main/resources/*` (native hints if needed)

**Step 1: Context7 baseline (GraalVM native for Micronaut)**

Confirm the recommended Micronaut approach for native image.

**Step 2: Add native build plugin/profile**

Wire Gradle native build tasks.

**Step 3: Run native build**

Run: `./gradlew :app:nativeCompile` (or Micronaut-native task)
Expected: builds native binary

**Step 4: Commit**

```bash
git commit -m "chore(native): add GraalVM native image build"
```

---

### Task 15: End-to-end verification via docker-compose

**Files:**
- Modify: `docker-compose.yml` (only if needed)
- Create: `docs/runbook.md`

**Step 1: Verify local dependencies**

Run: `podman compose up -d`
Expected: postgres/redis/rocketmq healthy

**Step 2: Run app locally**

Run: `./gradlew :app:run`
Expected: HTTP endpoints available, TCP server listening

**Step 3: Write runbook**

Document:
- how to start deps
- config keys
- how to generate TLS cert

**Step 4: Commit**

```bash
git commit -m "docs: add local runbook for phase1 services"
```

---

## Execution choice

Plan complete and saved to `docs/plans/2026-03-04-im-phase1-implementation-plan.md`.

Two execution options:

1. Subagent-Driven (this session) - I dispatch fresh subagent per task, review between tasks.
2. Parallel Session (separate) - Open new session with executing-plans, batch execution with checkpoints.

Which approach? (Reply with `1` or `2`.)

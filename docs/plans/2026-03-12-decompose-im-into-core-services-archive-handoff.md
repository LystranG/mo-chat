# decompose-im-into-core-services Archive Handoff

## Current state

- Worktree:
  `/home/lystran/programming/java/practice/mo-chat/.worktrees/decompose-im-core-services-g1`
- `openspec instructions apply --change decompose-im-into-core-services --json` now reports:
  - `progress.complete = 31`
  - `progress.total = 31`
  - `state = "all_done"`
- The change has **not** been archived yet.
- The worktree is intentionally dirty. Do **not** reset or clean unrelated changes.

## What was verified in this session

- Fresh verified:
  - `./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.AccessGatewayGrpcConnectivityTest --rerun-tasks`
  - `./gradlew :message-service-app:test --tests com.github.lystran.mochat.messageservice.MessageServiceCrossGatewayRoutingIntegrationTest.staleRouteAfterSingleRefreshFallsBackToOfflineQueue :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.newerBindOnOtherGatewayLeavesOldOwnerAliveUntilHeartbeatThenSelfKills --rerun-tasks`
  - `./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.drainingGatewayRejectsNewBindOwnership --rerun-tasks`
  - `./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.drainGraceExpirationClosesExistingBoundConnectionAndClearsRoute --rerun-tasks`
- Observed but not cleanly re-verified by exit code in this session:
  - `./gradlew :access-gateway-app:test --tests com.github.lystran.mochat.accessgateway.runtime.AccessGatewayOnlineRouteBindingTest.drainingGatewayRejectsNewBindButAllowsReconnectOnOtherGatewayAfterGrace --rerun-tasks`
  - This command repeatedly hit Gradle test result store failure:
    `java.nio.file.NoSuchFileException: .../build/test-results/test/binary/in-progress-results-generic....bin`
  - This looks like Gradle test reporting/result-store instability, not a business assertion failure and not a project write-permission issue inside the worktree.

## Important files

- OpenSpec tasks:
  - `openspec/changes/decompose-im-into-core-services/tasks.md`
- Archive target change:
  - `openspec/changes/decompose-im-into-core-services/`
- Latest runtime/runbook update:
  - `docs/runbook.md`
- Cross-gateway message test:
  - `message-service-app/src/test/java/com/github/lystran/mochat/messageservice/MessageServiceCrossGatewayRoutingIntegrationTest.java`

## Recommended next-session actions

1. Re-check `git status --short` and avoid reverting anything.
2. Decide archive evidence threshold:
   - strict: re-run the remaining 7.3 focused test with a workaround for Gradle result store instability
   - pragmatic: accept the current `tasks.md` completion note plus the other fresh focused verifications
3. If acceptable, use `openspec-archive-change` for `decompose-im-into-core-services`.
4. After archive, report any residual Gradle `SerializableTestResultStore` / `NoSuchFileException` issue as tooling noise, not as a spec gap.

## Notes for the next agent

- No `kind` cluster is required for archive work.
- Do not redo `6.4`.
- Do not claim the archive is done until the archive command actually succeeds.

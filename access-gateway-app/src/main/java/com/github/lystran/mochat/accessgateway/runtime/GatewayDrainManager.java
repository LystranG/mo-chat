package com.github.lystran.mochat.accessgateway.runtime;

import com.github.lystran.mochat.connection.GatewayDrainState;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一管理网关退场流程：先停止接收新的用户连接，再在宽限期结束后清掉剩余连接。
 */
public final class GatewayDrainManager implements GatewayDrainState, AutoCloseable {
    private final LocalGatewayConnectionDirectory localGatewayConnectionDirectory;
    private final ScheduledExecutorService scheduler;
    private final Duration gracePeriod;
    /**
     * 标记当前网关是不是已经进入“只退不进”的状态。
     */
    private final AtomicBoolean draining = new AtomicBoolean(false);
    /**
     * 让关闭流程可以等待整个 drain 窗口收尾。
     */
    private final CompletableFuture<Void> drainCompletion = new CompletableFuture<>();

    /**
     * 宽限期结束后真正执行清场的定时任务。
     */
    private volatile ScheduledFuture<?> gracePeriodClosure;

    /**
     * 组装退场所需的连接目录、调度器和宽限期配置。
     */
    public GatewayDrainManager(
        LocalGatewayConnectionDirectory localGatewayConnectionDirectory,
        ScheduledExecutorService scheduler,
        Duration gracePeriod
    ) {
        this.localGatewayConnectionDirectory = Objects.requireNonNull(
            localGatewayConnectionDirectory,
            "localGatewayConnectionDirectory"
        );
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.gracePeriod = Objects.requireNonNull(gracePeriod, "gracePeriod");
    }

    @Override
    /**
     * 返回网关是否已经开始退场，不再接收新的用户连接。
     */
    public boolean isDraining() {
        return draining.get();
    }

    /**
     * 开始退场：马上拒绝新的连接绑定，并安排宽限期结束后的清场动作。
     */
    public void startDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        gracePeriodClosure = scheduler.schedule(
            this::closeAndComplete,
            Math.max(0L, gracePeriod.toMillis()),
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * 立即结束宽限期，直接关闭所有剩余已绑定连接。
     */
    public int closeBoundConnectionsNow() {
        ScheduledFuture<?> scheduledClosure = gracePeriodClosure;
        if (scheduledClosure != null) {
            scheduledClosure.cancel(false);
        }
        return closeAndComplete();
    }

    /**
     * 在进程关闭时等待 drain 收尾，避免还没退场完就被直接停掉。
     */
    public void awaitDrainCompletion() {
        if (!draining.get()) {
            return;
        }
        try {
            drainCompletion.get(Math.max(1L, gracePeriod.toMillis()) + 1_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting gateway drain completion", interruptedException);
        } catch (ExecutionException executionException) {
            throw new IllegalStateException("Gateway drain failed", executionException.getCause());
        } catch (TimeoutException timeoutException) {
            throw new IllegalStateException("Timed out waiting for gateway drain completion", timeoutException);
        }
    }

    /**
     * 真正执行本地连接清场。
     */
    private int completeDrainWindow() {
        return localGatewayConnectionDirectory.closeBoundConnections();
    }

    /**
     * 执行清场并把 drain 结果通知给等待方。
     */
    private int closeAndComplete() {
        try {
            int closedConnections = completeDrainWindow();
            drainCompletion.complete(null);
            return closedConnections;
        } catch (RuntimeException runtimeException) {
            drainCompletion.completeExceptionally(runtimeException);
            throw runtimeException;
        }
    }

    @Override
    /**
     * 关闭管理器时取消尚未执行的定时清场任务。
     */
    public void close() {
        ScheduledFuture<?> scheduledClosure = gracePeriodClosure;
        if (scheduledClosure != null) {
            scheduledClosure.cancel(false);
        }
    }
}

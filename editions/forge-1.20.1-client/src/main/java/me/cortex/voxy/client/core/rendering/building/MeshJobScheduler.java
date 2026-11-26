package me.cortex.voxy.client.core.rendering.building;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Paces starts from the mesh priority queue without occupying a worker while it waits. */
final class MeshJobScheduler {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final int QUEUE_SCALE = 512;
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private final Runnable submitJob;
    private final LongSupplier clock;
    private final IntSupplier queueSize;
    private int baseRate;
    private long gracePeriod;
    private int scheduledRate;
    private boolean submitted;
    private boolean waitingForFrame = true;
    private boolean idle;
    private boolean zooming;
    private boolean closed;
    private long firstFrame;
    private long lastStart;
    private long wakeupGeneration;
    private ScheduledFuture<?> wakeup;

    MeshJobScheduler(Runnable submitJob, IntSupplier queueSize, int baseRate, int graceSeconds, LongSupplier clock) {
        this.submitJob = submitJob;
        this.queueSize = queueSize;
        this.clock = clock;
        this.baseRate = baseRate;
        this.gracePeriod = TimeUnit.SECONDS.toNanos(graceSeconds);
        this.lastStart = clock.getAsLong();
    }

    synchronized void queueChanged() {
        if (this.wakeup != null && this.scheduledRate != this.currentRate(this.clock.getAsLong())) {
            this.cancelWakeup();
        }
        this.dispatch();
    }

    synchronized void jobStarted(boolean started) {
        this.submitted = false;
        if (started) this.lastStart = this.clock.getAsLong();
        this.dispatch();
    }

    synchronized void updateLimit(int baseRate, int graceSeconds, boolean idle) {
        long gracePeriod = TimeUnit.SECONDS.toNanos(graceSeconds);
        if (this.baseRate == baseRate && this.gracePeriod == gracePeriod && this.idle == idle) return;
        this.baseRate = baseRate;
        this.gracePeriod = gracePeriod;
        this.idle = idle;
        this.queueChanged();
    }

    synchronized void setZooming(boolean zooming) {
        if (this.zooming == zooming) return;
        this.zooming = zooming;
        this.queueChanged();
    }

    synchronized boolean isUncapped() {
        return this.isUncapped(this.clock.getAsLong());
    }

    private boolean isUncapped(long now) {
        return this.baseRate == 0 || this.idle || this.zooming
                || (this.gracePeriod > 0 && (this.waitingForFrame || now - this.firstFrame < this.gracePeriod));
    }

    private int currentRate(long now) {
        int queued = this.queueSize.getAsInt();
        return queued == 0 || this.isUncapped(now) ? 0 : scaledRate(this.baseRate, queued);
    }

    static int scaledRate(int baseRate, int queued) {
        return (int) ((long) baseRate * Math.max(QUEUE_SCALE, queued) / QUEUE_SCALE);
    }

    synchronized void restartGracePeriod() {
        if (this.waitingForFrame) return;
        this.waitingForFrame = true;
        this.cancelWakeup();
        this.dispatch();
    }

    synchronized void frameRendered() {
        if (!this.waitingForFrame) return;
        this.firstFrame = this.clock.getAsLong();
        this.waitingForFrame = false;
    }

    synchronized void close() {
        this.closed = true;
        this.cancelWakeup();
    }

    private void dispatch() {
        // Keep at most one permit ahead of the workers so a bypass cannot leave a
        // backlog of already-admitted jobs when the cap comes back on.
        if (this.closed || this.queueSize.getAsInt() == 0 || this.submitted || this.wakeup != null) return;

        long now = this.clock.getAsLong();
        int rate = this.currentRate(now);
        if (rate > 0) {
            long interval = (SECOND + rate - 1) / rate;
            long delay = interval - (now - this.lastStart);
            if (delay > 0) {
                this.scheduledRate = rate;
                long generation = ++this.wakeupGeneration;
                this.wakeup = TIMER.schedule(() -> this.wake(generation), delay, TimeUnit.NANOSECONDS);
                return;
            }
        }

        this.submitted = true;
        this.submitJob.run();
    }

    private synchronized void wake(long generation) {
        if (generation != this.wakeupGeneration) return;
        this.wakeup = null;
        this.dispatch();
    }

    private void cancelWakeup() {
        if (this.wakeup == null) return;
        this.wakeupGeneration++;
        this.wakeup.cancel(false);
        this.wakeup = null;
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        var timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            var thread = new Thread(runnable, "Voxy mesh pacing");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}

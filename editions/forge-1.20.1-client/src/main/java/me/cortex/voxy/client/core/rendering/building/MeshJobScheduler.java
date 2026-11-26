package me.cortex.voxy.client.core.rendering.building;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Paces starts from the mesh priority queue without occupying a worker while it waits. */
final class MeshJobScheduler {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);
    private static final long GRACE_PERIOD = 10 * SECOND;
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private final Runnable submitJob;
    private final LongSupplier clock;
    private int jobsPerSecond;
    private int waitingJobs;
    private boolean submitted;
    private boolean waitingForFrame = true;
    private boolean idle;
    private boolean closed;
    private long firstFrame;
    private long lastStart;
    private long wakeupGeneration;
    private ScheduledFuture<?> wakeup;

    MeshJobScheduler(Runnable submitJob, int jobsPerSecond) {
        this(submitJob, jobsPerSecond, System::nanoTime);
    }

    MeshJobScheduler(Runnable submitJob, int jobsPerSecond, LongSupplier clock) {
        this.submitJob = submitJob;
        this.clock = clock;
        this.jobsPerSecond = jobsPerSecond;
        this.lastStart = clock.getAsLong();
    }

    synchronized void enqueue() {
        if (this.closed) return;
        this.waitingJobs++;
        this.dispatch();
    }

    synchronized void jobStarted() {
        this.submitted = false;
        this.lastStart = this.clock.getAsLong();
        this.dispatch();
    }

    synchronized void updateLimit(int jobsPerSecond, boolean idle) {
        if (this.jobsPerSecond == jobsPerSecond && this.idle == idle) return;
        this.jobsPerSecond = jobsPerSecond;
        this.idle = idle;
        this.cancelWakeup();
        this.dispatch();
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
        if (this.closed || this.waitingJobs == 0 || this.submitted || this.wakeup != null) return;

        long now = this.clock.getAsLong();
        if (this.jobsPerSecond > 0 && !this.idle && !this.waitingForFrame && now - this.firstFrame >= GRACE_PERIOD) {
            long interval = (SECOND + this.jobsPerSecond - 1) / this.jobsPerSecond;
            long delay = interval - (now - this.lastStart);
            if (delay > 0) {
                long generation = ++this.wakeupGeneration;
                this.wakeup = TIMER.schedule(() -> this.wake(generation), delay, TimeUnit.NANOSECONDS);
                return;
            }
        }

        this.waitingJobs--;
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

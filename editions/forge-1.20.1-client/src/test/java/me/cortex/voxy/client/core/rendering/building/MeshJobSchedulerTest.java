package me.cortex.voxy.client.core.rendering.building;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshJobSchedulerTest {
    @Test
    void releasesQueuedWorkWhenTheNextSlotBecomesAvailable() throws InterruptedException {
        var clock = new AtomicLong();
        var starts = new Semaphore(0);
        var scheduler = new MeshJobScheduler(starts::release, 50, clock::get);
        try {
            scheduler.frameRendered();
            clock.set(TimeUnit.SECONDS.toNanos(11));
            scheduler.enqueue();
            assertTrue(starts.tryAcquire());
            scheduler.jobStarted();
            scheduler.enqueue();
            assertEquals(0, starts.availablePermits());

            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
            assertTrue(starts.tryAcquire(5, TimeUnit.SECONDS));
        } finally {
            scheduler.close();
        }
    }

    @Test
    void graceStartsOnTheFirstFrameAndRestartsAfterAResourceReload() {
        var clock = new AtomicLong();
        var starts = new AtomicInteger();
        var scheduler = new MeshJobScheduler(starts::incrementAndGet, 50, clock::get);
        try {
            clock.set(TimeUnit.SECONDS.toNanos(30));
            scheduler.enqueue();
            scheduler.enqueue();
            scheduler.jobStarted();
            assertEquals(2, starts.get());

            scheduler.frameRendered();
            clock.addAndGet(TimeUnit.SECONDS.toNanos(10) - 1);
            scheduler.jobStarted();
            scheduler.enqueue();
            assertEquals(3, starts.get());

            clock.incrementAndGet();
            scheduler.jobStarted();
            scheduler.enqueue();
            assertEquals(3, starts.get());

            scheduler.restartGracePeriod();
            assertEquals(4, starts.get());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void idleBypassLeavesNoBacklogOfAdmittedJobsAfterReturningToActive() {
        var clock = new AtomicLong();
        var starts = new AtomicInteger();
        var scheduler = new MeshJobScheduler(starts::incrementAndGet, 50, clock::get);
        try {
            scheduler.frameRendered();
            clock.set(TimeUnit.SECONDS.toNanos(11));
            scheduler.enqueue();
            scheduler.jobStarted();
            scheduler.enqueue();
            scheduler.enqueue();
            assertEquals(1, starts.get());

            scheduler.updateLimit(50, true);
            assertEquals(2, starts.get());

            scheduler.updateLimit(50, false);
            scheduler.jobStarted();
            assertEquals(2, starts.get());
        } finally {
            scheduler.close();
        }
    }
}

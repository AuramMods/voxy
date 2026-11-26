package me.cortex.voxy.client.core.rendering.building;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshJobSchedulerTest {
    @Test
    void releasesQueuedWorkWhenTheNextSlotBecomesAvailable() throws InterruptedException {
        var clock = new AtomicLong();
        var starts = new Semaphore(0);
        var queued = new AtomicInteger();
        var scheduler = new MeshJobScheduler(starts::release, queued::get, 50, 10, clock::get);
        try {
            scheduler.frameRendered();
            clock.set(TimeUnit.SECONDS.toNanos(11));
            queued.incrementAndGet();
            scheduler.queueChanged();
            assertTrue(starts.tryAcquire());
            queued.decrementAndGet();
            scheduler.jobStarted(true);
            queued.incrementAndGet();
            scheduler.queueChanged();
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
        var queued = new AtomicInteger();
        var scheduler = new MeshJobScheduler(starts::incrementAndGet, queued::get, 50, 10, clock::get);
        try {
            clock.set(TimeUnit.SECONDS.toNanos(30));
            queued.incrementAndGet();
            scheduler.queueChanged();
            queued.incrementAndGet();
            scheduler.queueChanged();
            queued.decrementAndGet();
            scheduler.jobStarted(true);
            assertEquals(2, starts.get());

            scheduler.frameRendered();
            clock.addAndGet(TimeUnit.SECONDS.toNanos(10) - 1);
            queued.decrementAndGet();
            scheduler.jobStarted(true);
            queued.incrementAndGet();
            scheduler.queueChanged();
            assertEquals(3, starts.get());

            clock.incrementAndGet();
            queued.decrementAndGet();
            scheduler.jobStarted(true);
            queued.incrementAndGet();
            scheduler.queueChanged();
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
        var queued = new AtomicInteger();
        var scheduler = new MeshJobScheduler(starts::incrementAndGet, queued::get, 50, 10, clock::get);
        try {
            scheduler.frameRendered();
            clock.set(TimeUnit.SECONDS.toNanos(11));
            queued.incrementAndGet();
            scheduler.queueChanged();
            queued.decrementAndGet();
            scheduler.jobStarted(true);
            queued.incrementAndGet();
            scheduler.queueChanged();
            queued.incrementAndGet();
            scheduler.queueChanged();
            assertEquals(1, starts.get());

            scheduler.updateLimit(50, 10, true);
            assertEquals(2, starts.get());

            scheduler.updateLimit(50, 10, false);
            queued.decrementAndGet();
            scheduler.jobStarted(true);
            assertEquals(2, starts.get());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void scaledRateFloorsTheFractionalIncreaseAndKeepsTheBaseMinimum() {
        assertEquals(50, MeshJobScheduler.scaledRate(50, 250));
        assertEquals(50, MeshJobScheduler.scaledRate(50, 1_000));
        assertEquals(62, MeshJobScheduler.scaledRate(50, 1_250));
        assertEquals(100, MeshJobScheduler.scaledRate(50, 2_000));
    }

    @Test
    void pruningRecomputesTheRateAndAnEmptyPermitDoesNotConsumeASlot() {
        var clock = new AtomicLong();
        var starts = new AtomicInteger();
        var queued = new AtomicInteger(2_000);
        var scheduler = new MeshJobScheduler(starts::incrementAndGet, queued::get, 50, 0, clock::get);
        try {
            clock.set(TimeUnit.MILLISECONDS.toNanos(10));
            scheduler.queueChanged();
            assertEquals(1, starts.get());

            queued.set(0);
            scheduler.queueChanged();
            scheduler.jobStarted(false);
            queued.set(250);
            scheduler.queueChanged();
            assertEquals(1, starts.get());

            queued.set(2_000);
            scheduler.queueChanged();
            assertEquals(2, starts.get());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void zeroWarmupDisablesGraceAndZoomIsAnIndependentBypass() {
        var clock = new AtomicLong();
        var starts = new AtomicInteger();
        var queued = new AtomicInteger(2);
        var scheduler = new MeshJobScheduler(starts::incrementAndGet, queued::get, 50, 0, clock::get);
        try {
            scheduler.queueChanged();
            assertEquals(0, starts.get());
            assertFalse(scheduler.isUncapped());
            scheduler.restartGracePeriod();
            assertFalse(scheduler.isUncapped());
            scheduler.frameRendered();
            scheduler.restartGracePeriod();
            assertFalse(scheduler.isUncapped());

            scheduler.setZooming(true);
            assertTrue(scheduler.isUncapped());
            assertEquals(1, starts.get());
            scheduler.setZooming(false);
            queued.decrementAndGet();
            scheduler.jobStarted(true);
            assertFalse(scheduler.isUncapped());
            assertEquals(1, starts.get());

            scheduler.updateLimit(50, 60, false);
            assertTrue(scheduler.isUncapped());
            scheduler.frameRendered();
            clock.set(TimeUnit.SECONDS.toNanos(60));
            assertFalse(scheduler.isUncapped());
            scheduler.updateLimit(0, 0, false);
            assertTrue(scheduler.isUncapped());
        } finally {
            scheduler.close();
        }
    }
}

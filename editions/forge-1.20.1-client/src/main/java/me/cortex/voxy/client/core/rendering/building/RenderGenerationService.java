package me.cortex.voxy.client.core.rendering.building;

import me.cortex.voxy.common.util.ExceptionCleanup;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.SectionUpdateRouter;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

//TODO: Add a render cache


public class RenderGenerationService {
    private static final int MAX_HOLDING_SECTION_COUNT = 1000;

    public static final AtomicInteger MESH_FAILED_COUNTER = new AtomicInteger();
    private static final LongAdder COMPLETED_MESHES = new LongAdder();
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final class BuildTask {
        WorldSection section;
        final long position;
        boolean hasDoneModelRequestInner;
        boolean hasDoneModelRequestOuter;
        int attempts;
        int addin;
        long priority = Long.MIN_VALUE;
        private BuildTask(long position) {
            this.position = position;
        }
        private void updatePriority() {
            int unique = COUNTER.incrementAndGet();
            int lvl = WorldEngine.MAX_LOD_LAYER-WorldEngine.getLevel(this.position);
            lvl = Math.min(lvl, 3);//Make the 2 highest quality have equal priority
            this.priority = (((lvl*3L + Math.min(this.attempts, 3))*2 + this.addin) <<32) + Integer.toUnsignedLong(unique);
            this.addin = 0;
        }
    }

    private final AtomicInteger holdingSectionCount = new AtomicInteger();//Used to limit section holding

    private volatile int taskQueueCount;
    // The queue and its position index share a lock so a worker and a prune cannot both take a task.
    private final Object taskLock = new Object();
    private final PriorityQueue<BuildTask> taskQueue = new PriorityQueue<>(5000, (a,b)-> Long.compareUnsigned(a.priority, b.priority));
    private final Long2ObjectOpenHashMap<BuildTask> taskMap = new Long2ObjectOpenHashMap<>(5000);

    private final WorldEngine world;
    private final SectionUpdateRouter watcher;
    private final ModelBakerySubsystem modelBakery;
    private Consumer<BuiltSection> resultConsumer;
    private final boolean emitMeshlets;

    private final Service service;
    private final MeshJobScheduler scheduler;


    public RenderGenerationService(WorldEngine world, ModelBakerySubsystem modelBakery, ServiceManager sm,
                                   boolean emitMeshlets, SectionUpdateRouter watcher) {
        this.emitMeshlets = emitMeshlets;
        this.world = world;
        this.watcher = watcher;
        this.modelBakery = modelBakery;

        this.service = sm.createService(()->{
            //Thread local instance of the factory
            var factory = new RenderDataFactory(this.world, this.modelBakery.factory, this.emitMeshlets);
            IntOpenHashSet seenMissed = new IntOpenHashSet(128);
            return new Pair<>(() -> {
                this.processJob(factory, seenMissed);
            }, factory::free);
        }, 10, "Section mesh generation service");
        this.scheduler = new MeshJobScheduler(this.service::execute, this::getTaskCount,
                VoxyConfig.CONFIG.meshJobsPerSecond, VoxyConfig.CONFIG.meshWarmupSeconds, System::nanoTime);
    }

    public void updateMeshJobLimit(int jobsPerSecond, int graceSeconds, boolean idle) {
        this.scheduler.updateLimit(jobsPerSecond, graceSeconds, idle);
    }

    public void setZooming(boolean zooming) {
        this.scheduler.setZooming(zooming);
    }

    public boolean isMeshPacingBypassed() {
        return this.scheduler.isUncapped();
    }

    public void restartGracePeriod() {
        this.scheduler.restartGracePeriod();
    }

    public void frameRendered() {
        this.scheduler.frameRendered();
    }

    public void setResultConsumer(Consumer<BuiltSection> consumer) {
        this.resultConsumer = consumer;
    }

    //NOTE: the biomes are always fully populated/kept up to date

    //Asks the Model system to bake all blocks that currently dont have a model
    private void computeAndRequestRequiredModels(IntOpenHashSet seenMissedIds, int bitMsk, long[] auxData) {
        final var factory = this.modelBakery.factory;
        for (int i = 0; i < 6; i++) {
            if ((bitMsk&(1<<i))==0) continue;
            for (int j = 0; j < 32*32; j++) {
                int block = Mapper.getBlockId(auxData[j+(i*32*32)]);
                if (block != 0 && !factory.hasModelForBlockId(block)) {
                    if (seenMissedIds.add(block)) {
                        this.modelBakery.requestBlockBake(block);
                    }
                }
            }
        }
    }

    private void computeAndRequestRequiredModels(IntOpenHashSet seenMissedIds, WorldSection section) {
        //Know this is... very much not safe, however it reduces allocation rates and other garbage, am sure its "fine"
        final var factory = this.modelBakery.factory;
        for (long state : section._unsafeGetRawDataArray()) {
            int block = Mapper.getBlockId(state);
            if (block != 0 && !factory.hasModelForBlockId(block)) {
                if (seenMissedIds.add(block)) {
                    this.modelBakery.requestBlockBake(block);
                }
            }
        }
    }

    private WorldSection acquireSection(long pos) {
        return this.world.acquireIfExists(pos);
    }

    private static boolean putTaskFirst(long pos) {
        //Level 3 or 4
        return WorldEngine.getLevel(pos) > 2;
    }

    //TODO: add a generated render data cache
    private void processJob(RenderDataFactory factory, IntOpenHashSet seenMissedIds) {
        BuildTask task;
        synchronized (this.taskLock) {
            task = this.taskQueue.poll();
            if (task != null) {
                this.taskMap.remove(task.position);
                this.taskQueueCount = this.taskQueue.size();
            }
            this.scheduler.jobStarted(task != null);
        }
        if (task == null) return;

        WorldSection section = task.section == null ? this.acquireSection(task.position) : task.section;
        if (section == null) {
            if (this.resultConsumer != null) {
                this.resultConsumer.accept(BuiltSection.empty(task.position));
            }
            return;
        }
        section.assertNotFree();
        BuiltSection mesh = null;


        boolean requeued = false;
        try {
            mesh = factory.generateMesh(section);
        } catch (IdNotYetComputedException e) {
            this.requestMissingModels(task, section, seenMissedIds, e);
            requeued = this.requeueTask(task, section);
        } finally {
            if (!requeued) {
                if (task.section == null) section.release();
                else this.releaseHeldSection(task);
            }
        }
        if (mesh != null) {//If the mesh is null it means it didnt finish, so dont submit
            if (this.resultConsumer != null) {
                boolean hasGeometry = !mesh.isEmpty();
                this.resultConsumer.accept(mesh);
                if (hasGeometry) COMPLETED_MESHES.increment();
            } else {
                mesh.free();
            }
        }
    }


    private void requestMissingModels(BuildTask task, WorldSection section, IntOpenHashSet seenMissedIds,
                                      IdNotYetComputedException error) {
        if (error.isIdBlockId && !this.modelBakery.factory.hasModelForBlockId(error.id)
                && seenMissedIds.add(error.id)) {
            this.modelBakery.requestBlockBake(error.id);
        }

        if (task.hasDoneModelRequestOuter || task.hasDoneModelRequestInner) {
            MESH_FAILED_COUNTER.incrementAndGet();
        }

        if (task.hasDoneModelRequestInner && task.hasDoneModelRequestOuter) {
            task.attempts++;
            return;
        }
        if (task.hasDoneModelRequestInner) {
            task.attempts++;
        } else {
            if (error.auxData == null) this.computeAndRequestRequiredModels(seenMissedIds, section);
            task.hasDoneModelRequestInner = true;
        }
        if (task.hasDoneModelRequestOuter) {
            task.attempts++;
        } else if (error.auxData != null) {
            this.computeAndRequestRequiredModels(seenMissedIds, error.auxBitMsk, error.auxData);
            task.hasDoneModelRequestOuter = true;
        }
        // Give the model bakery time to handle the new requests.
        task.addin = WorldEngine.getLevel(task.position) > 2 ? 1 : 0;
    }

    public void enqueueTask(long pos) {
        synchronized (this.taskLock) {
            if (!this.service.isLive() || this.taskMap.containsKey(pos) || !this.isWatched(pos)) return;
            this.queueTask(new BuildTask(pos));
        }
    }

    private boolean requeueTask(BuildTask task, WorldSection section) {
        synchronized (this.taskLock) {
            if (!this.service.isLive() || !this.isWatched(task.position)) return false;
            BuildTask pending = this.taskMap.get(task.position);
            if (pending != null) {
                pending.hasDoneModelRequestInner |= task.hasDoneModelRequestInner;
                pending.hasDoneModelRequestOuter |= task.hasDoneModelRequestOuter;
                return false;
            }
            if (task.section == null && this.holdingSectionCount.get() < MAX_HOLDING_SECTION_COUNT) {
                this.holdingSectionCount.incrementAndGet();
                task.section = section;
            }
            this.queueTask(task);
            // At the reference limit, the queued task will reacquire the section on its next start.
            if (task.section == null) section.release();
            return true;
        }
    }

    private void queueTask(BuildTask task) {
        task.updatePriority();
        this.taskMap.put(task.position, task);
        this.taskQueue.add(task);
        this.taskQueueCount = this.taskQueue.size();
        this.scheduler.queueChanged();
    }

    private boolean isWatched(long pos) {
        return (this.watcher.get(pos) & WorldEngine.UPDATE_TYPE_BLOCK_BIT) != 0;
    }

    public void pruneUnwatchedTasks() {
        synchronized (this.taskLock) {
            if (!this.taskQueue.removeIf(task -> {
                if (this.isWatched(task.position)) return false;
                this.taskMap.remove(task.position);
                this.releaseHeldSection(task);
                return true;
            })) return;
            this.taskQueueCount = this.taskQueue.size();
            this.scheduler.queueChanged();
        }
    }

    private void releaseHeldSection(BuildTask task) {
        if (task.section == null) return;
        var section = task.section;
        task.section = null;
        this.holdingSectionCount.decrementAndGet();
        section.release();
    }

    public void shutdown() {
        var cleanup = new ExceptionCleanup(null);
        this.scheduler.close();
        cleanup.run(this.service::shutdown);
        synchronized (this.taskLock) {
            BuildTask task;
            while ((task = this.taskQueue.poll()) != null) {
                BuildTask pending = task;
                cleanup.run(() -> this.releaseHeldSection(pending));
                if (this.taskMap.remove(task.position) != task) {
                    cleanup.run(() -> { throw new IllegalStateException("Mesh task map mismatch"); });
                }
            }
            this.taskQueueCount = 0;
            if (!this.taskMap.isEmpty() || this.holdingSectionCount.get() != 0) {
                cleanup.run(() -> { throw new IllegalStateException("Mesh task cleanup incomplete"); });
            }
        }
        cleanup.rethrow();
    }

    private long lastChangedTime = 0;
    public void addDebugData(List<String> debug) {
        if (System.currentTimeMillis()-this.lastChangedTime > 100) {
            MESH_FAILED_COUNTER.set(0);
            this.lastChangedTime = System.currentTimeMillis();
        }
        debug.add("RSSQ/TFC: " + this.taskQueueCount + "/" + MESH_FAILED_COUNTER.get());//render section service queue, Task Fail Counter

    }

    public int getTaskCount() {
        return this.taskQueueCount;
    }

    public static long getCompletedMeshCount() {
        return COMPLETED_MESHES.sum();
    }
}

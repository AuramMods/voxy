package me.cortex.voxy.server;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.forge.ForgeWorld;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

/** Observes actual WorldEdit placements below the edit session's reorder buffers. */
public final class WorldEditLodHooks {
    private WorldEditLodHooks() {}

    public static void register() {
        WorldEdit.getInstance().getEventBus().register(new WorldEditLodHooks());
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() == EditSession.Stage.BEFORE_CHANGE && event.getWorld() instanceof ForgeWorld world) {
            event.setExtent(new DirtyChunks(event.getExtent(), world));
        }
    }

    private static final class DirtyChunks extends AbstractDelegateExtent {
        private final ForgeWorld world;
        private LongOpenHashSet changed;
        private long lastChunk;

        DirtyChunks(Extent delegate, ForgeWorld world) {
            super(delegate);
            this.world = world;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException {
            boolean written;
            try {
                written = super.setBlock(position, block);
            } catch (WorldEditException | RuntimeException | Error failure) {
                // The native placement can fail after it starts applying side effects.
                this.record(position);
                this.flush();
                throw failure;
            }
            if (written) this.record(position);
            return written;
        }

        @Override
        public boolean setBiome(BlockVector3 position, BiomeType biome) {
            boolean written = super.setBiome(position, biome);
            if (written) this.record(position);
            return written;
        }

        private void record(BlockVector3 position) {
            long chunk = ChunkPos.asLong(position.getBlockX() >> 4, position.getBlockZ() >> 4);
            if (this.changed == null) this.changed = new LongOpenHashSet();
            else if (chunk == this.lastChunk) return;
            this.changed.add(chunk);
            this.lastChunk = chunk;
        }

        @Override
        protected Operation commitBefore() {
            // WorldEdit assembles the commit before its buffered placements run.
            return new Operation() {
                @Override
                public Operation resume(RunContext context) {
                    DirtyChunks.this.flush();
                    return null;
                }

                @Override
                public void cancel() {
                    DirtyChunks.this.flush();
                }
            };
        }

        private void flush() {
            if (this.changed == null) return;
            var chunks = this.changed;
            this.changed = null;
            ServerLodService.chunksChanged(this.world.getWorld(), chunks);
        }
    }
}

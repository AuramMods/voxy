package me.cortex.voxy.common.config.section;

import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.config.IStoredSectionPositionIterator;
import me.cortex.voxy.common.world.WorldSection;

public abstract class SectionStorage implements IMappingStorage, IStoredSectionPositionIterator {
    public abstract int loadSection(WorldSection into);

    /** Loads an unpublished section. Its voxel array may be written before validation finishes. */
    public int loadNewSection(WorldSection into) {
        return this.loadSection(into);
    }

    public abstract void saveSection(WorldSection section);
}

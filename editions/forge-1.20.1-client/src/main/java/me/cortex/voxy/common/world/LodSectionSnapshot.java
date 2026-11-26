package me.cortex.voxy.common.world;

import me.cortex.voxy.common.world.other.Mapper;

import java.util.Objects;

/** Owned section data. The producer transfers ownership of the voxel array and coverage. */
public record LodSectionSnapshot(long key, byte children, SurfaceDataCoverage coverage, long[] voxels) {
    public LodSectionSnapshot {
        Objects.requireNonNull(coverage);
        if (coverage.level() != WorldEngine.getLevel(key) || voxels.length != WorldSection.SECTION_VOLUME) {
            throw new IllegalArgumentException("Section shape does not match its key");
        }
    }

    public static LodSectionSnapshot capture(WorldSection section) {
        synchronized (section) {
            var coverage = section.getSurfaceData();
            return new LodSectionSnapshot(section.key, section.getNonEmptyChildren(),
                    coverage.mode() == 2 ? copyPartialCoverage(coverage) : coverage, section.copyData());
        }
    }

    public void copyInto(WorldSection section) {
        if (section.key != this.key) throw new IllegalArgumentException("Section keys differ");
        synchronized (section) {
            if (this.voxels != section.data) {
                System.arraycopy(this.voxels, 0, section.data, 0, this.voxels.length);
            }
            section.surfaceData = this.coverage.mode() == 2
                    ? copyPartialCoverage(this.coverage) : this.coverage;
            section.legacySurfaceSource = section.lvl == 0 && this.coverage.mode() == 0;
            section._unsafeSetNonEmptyChildren(this.children);
            if (section.lvl == 0) {
                int count = 0;
                for (long voxel : this.voxels) {
                    if (!Mapper.isAir(voxel)) count++;
                }
                section.nonEmptyBlockCount = count;
            }
        }
    }

    private static SurfaceDataCoverage copyPartialCoverage(SurfaceDataCoverage coverage) {
        long[] words = new long[coverage.serializedWordCount()];
        for (int index = 0; index < words.length; index++) words[index] = coverage.word(index);
        return SurfaceDataCoverage.fromWords(coverage.level(), words);
    }
}

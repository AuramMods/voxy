package me.cortex.voxy.common.network;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.cortex.voxy.common.world.LodSectionSnapshot;
import me.cortex.voxy.common.world.SurfaceDataCoverage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** A portable section body. Session identity, position and revision belong to the message envelope. */
public final class LodSectionCodec {
    public static final int VERSION = 1;
    public static final int MAX_COMPRESSED_BYTES = 512 * 1024;
    private static final int MAX_RAW_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PALETTE_STRING = 512;

    public interface Palette {
        String blockName(int id);
        String biomeName(int id);
        int blockId(String name);
        int biomeId(String name);
    }

    private record Voxel(String block, String biome, int light, boolean carried) {
        boolean air() {
            return this.block.isEmpty();
        }
    }

    private LodSectionCodec() {}

    public static byte[] encode(LodSectionSnapshot section, Palette palette) throws IOException {
        var indices = new Long2IntOpenHashMap();
        indices.defaultReturnValue(-1);
        var lookup = new ArrayList<Voxel>();
        var cells = new short[WorldSection.SECTION_VOLUME];
        for (int i = 0; i < cells.length; i++) {
            long voxel = section.voxels()[i];
            int index = indices.get(voxel);
            if (index == -1) {
                index = lookup.size();
                indices.put(voxel, index);
                boolean carried = Mapper.isSurfaceCarrier(voxel);
                long material = carried ? Mapper.restoreSurfaceCarrier(voxel) : voxel;
                boolean air = Mapper.isAir(material);
                lookup.add(new Voxel(air ? "" : palette.blockName(Mapper.getBlockId(material)),
                        air ? "" : palette.biomeName(Mapper.getBiomeId(material)), Mapper.getLightId(voxel), carried));
            }
            cells[i] = (short) index;
        }

        var compressed = new ByteArrayOutputStream();
        var deflater = new Deflater(Deflater.BEST_SPEED);
        try (var output = new DataOutputStream(new DeflaterOutputStream(compressed, deflater))) {
            output.writeByte(VERSION);
            output.writeByte(section.children());
            writeCoverage(output, section.coverage());
            output.writeShort(lookup.size());
            int rawBytes = 7 + section.coverage().serializedWordCount() * Long.BYTES + cells.length * Short.BYTES;
            for (var entry : lookup) {
                // Modified UTF uses at most three bytes per character. Bound work before compression.
                rawBytes += 6 + 3 * (entry.block().length() + entry.biome().length());
                if (rawBytes > MAX_RAW_BYTES || entry.block().length() > MAX_PALETTE_STRING
                        || entry.biome().length() > MAX_PALETTE_STRING) {
                    throw new IOException("Voxy section palette exceeds wire limits");
                }
                output.writeUTF(entry.block());
                output.writeUTF(entry.biome());
                output.writeByte(entry.light());
                output.writeBoolean(entry.carried());
            }
            if (lookup.size() > 1) {
                for (short index : cells) output.writeShort(index);
            }
        } finally {
            deflater.end();
        }
        if (compressed.size() > MAX_COMPRESSED_BYTES) throw new IOException("Compressed Voxy section is too large");
        return compressed.toByteArray();
    }

    public static LodSectionSnapshot decode(long key, byte[] compressed, Palette palette) throws IOException {
        if (compressed.length == 0 || compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Invalid compressed Voxy section size");
        }
        int level = WorldEngine.getLevel(key);
        if (level > WorldEngine.MAX_LOD_LAYER) throw new IOException("Invalid Voxy LOD level");
        var inflater = new Inflater();
        try {
            byte[] raw;
            try (var stream = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater)) {
                raw = stream.readNBytes(MAX_RAW_BYTES + 1);
            }
            if (raw.length > MAX_RAW_BYTES || !inflater.finished() || inflater.getBytesRead() != compressed.length) {
                throw new IOException("Invalid compressed Voxy section body");
            }

            var input = new DataInputStream(new ByteArrayInputStream(raw));
            if (input.readUnsignedByte() != VERSION) throw new IOException("Unsupported Voxy section format");
            byte children = input.readByte();
            var coverage = readCoverage(input, level);
            int size = input.readUnsignedShort();
            if (size < 1 || size > WorldSection.SECTION_VOLUME) throw new IOException("Invalid Voxy palette size");

            var entries = new Voxel[size];
            for (int i = 0; i < size; i++) {
                var entry = new Voxel(input.readUTF(), input.readUTF(), input.readUnsignedByte(), input.readBoolean());
                if (entry.block().length() > MAX_PALETTE_STRING || entry.biome().length() > MAX_PALETTE_STRING
                        || entry.air() != entry.biome().isEmpty() || entry.air() && entry.carried()) {
                    throw new IOException("Invalid Voxy palette entry");
                }
                entries[i] = entry;
            }
            var cells = new short[WorldSection.SECTION_VOLUME];
            if (size > 1) {
                for (int i = 0; i < cells.length; i++) {
                    int index = input.readUnsignedShort();
                    if (index >= size) throw new IOException("Invalid Voxy palette index");
                    cells[i] = (short) index;
                }
            }
            if (input.read() != -1) throw new IOException("Unexpected data after Voxy section");

            var lookup = new long[size];
            for (int i = 0; i < size; i++) {
                Voxel entry = entries[i];
                int block = entry.air() ? 0 : palette.blockId(entry.block());
                int biome = entry.air() ? 0 : palette.biomeId(entry.biome());
                if (block < 0 || block >= 1 << 20 || biome < 0 || biome >= 1 << 9) {
                    throw new IOException("Voxy material could not be mapped");
                }
                long voxel = Mapper.composeMappingId((byte) entry.light(), block, biome);
                lookup[i] = entry.carried() && block != 0 ? Mapper.makeSurfaceCarrier(voxel) : voxel;
            }
            var voxels = new long[cells.length];
            for (int i = 0; i < cells.length; i++) voxels[i] = lookup[Short.toUnsignedInt(cells[i])];
            return new LodSectionSnapshot(key, children, coverage, voxels);
        } finally {
            inflater.end();
        }
    }

    private static void writeCoverage(DataOutputStream output, SurfaceDataCoverage coverage) throws IOException {
        output.writeByte(coverage.mode());
        for (int i = 0; i < coverage.serializedWordCount(); i++) output.writeLong(coverage.word(i));
    }

    private static SurfaceDataCoverage readCoverage(DataInputStream input, int level) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 0 -> SurfaceDataCoverage.legacy(level);
            case 1 -> SurfaceDataCoverage.raw(level);
            case 2 -> {
                long[] words = new long[((1 << (3 * (level + 1))) + 63) / 64];
                for (int i = 0; i < words.length; i++) words[i] = input.readLong();
                try {
                    yield SurfaceDataCoverage.fromWords(level, words);
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Invalid Voxy coverage bits", failure);
                }
            }
            default -> throw new IOException("Invalid Voxy coverage mode");
        };
    }
}

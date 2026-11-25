package me.cortex.voxy.server;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.world.LodSectionSnapshot;
import me.cortex.voxy.common.world.SurfaceDataCoverage;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collection;
import java.util.function.LongConsumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** One dimension's headless section store. Column coverage and its revised LODs commit together. */
public final class ServerSectionStorage extends SectionStorage implements AutoCloseable {
    public record Revision(long number, LodSectionSnapshot section) {}

    private final Connection db;

    public ServerSectionStorage(Path path) throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("CREATE TABLE IF NOT EXISTS mappings (id INTEGER PRIMARY KEY, data BLOB NOT NULL)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS section_v1 (
                        id INTEGER PRIMARY KEY,
                        level INTEGER NOT NULL,
                        revision INTEGER NOT NULL,
                        data BLOB NOT NULL
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS section_v1_level ON section_v1(level)");
            statement.execute("CREATE TABLE IF NOT EXISTS completed_columns (id INTEGER PRIMARY KEY)");
        } catch (SQLException ex) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
        this.db = connection;
    }

    public synchronized boolean hasColumn(long column) {
        try (var query = this.db.prepareStatement("SELECT 1 FROM completed_columns WHERE id=?")) {
            query.setLong(1, column);
            try (var result = query.executeQuery()) {
                return result.next();
            }
        } catch (SQLException ex) {
            throw storageFailure(ex);
        }
    }

    public synchronized long revision(long key) {
        try (var query = this.db.prepareStatement("SELECT revision FROM section_v1 WHERE id=?")) {
            query.setLong(1, key);
            try (var result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException ex) {
            throw storageFailure(ex);
        }
    }

    public Revision read(long key) {
        byte[] data;
        long revision;
        synchronized (this) {
            try (var query = this.db.prepareStatement("SELECT revision, data FROM section_v1 WHERE id=?")) {
                query.setLong(1, key);
                try (var result = query.executeQuery()) {
                    if (!result.next()) return null;
                    revision = result.getLong(1);
                    data = result.getBytes(2);
                }
            } catch (SQLException ex) {
                throw storageFailure(ex);
            }
        }
        try {
            return new Revision(revision, decode(key, data));
        } catch (IOException ex) {
            throw storageFailure(ex);
        }
    }

    @Override
    public int loadSection(WorldSection into) {
        var stored = this.read(into.key);
        if (stored == null) return 1;
        stored.section().copyInto(into);
        return 0;
    }

    @Override
    public void saveSection(WorldSection section) {
        this.write(null, java.util.List.of(LodSectionSnapshot.capture(section)));
    }

    public void commitColumn(long column, Collection<LodSectionSnapshot> sections) {
        this.write(column, sections);
    }

    private void write(Long column, Collection<LodSectionSnapshot> sections) {
        byte[][] data = new byte[sections.size()][];
        int index = 0;
        try {
            for (var section : sections) data[index++] = encode(section);
            this.writeTransaction(column, sections, data);
        } catch (SQLException | IOException ex) {
            throw storageFailure(ex);
        }
    }

    private synchronized void writeTransaction(Long column, Collection<LodSectionSnapshot> sections, byte[][] data)
            throws SQLException {
        this.db.setAutoCommit(false);
        try {
            try (var update = this.db.prepareStatement("""
                    INSERT INTO section_v1(id, level, revision, data) VALUES(?, ?, 1, ?)
                    ON CONFLICT(id) DO UPDATE SET revision=revision+1, data=excluded.data
                    """)) {
                int index = 0;
                for (var section : sections) {
                    update.setLong(1, section.key());
                    update.setInt(2, WorldEngine.getLevel(section.key()));
                    update.setBytes(3, data[index++]);
                    update.addBatch();
                }
                update.executeBatch();
            }
            if (column != null) {
                try (var done = this.db.prepareStatement("INSERT OR IGNORE INTO completed_columns(id) VALUES(?)")) {
                    done.setLong(1, column);
                    done.executeUpdate();
                }
            }
            this.db.commit();
        } catch (SQLException | RuntimeException ex) {
            try {
                this.db.rollback();
            } catch (SQLException rollbackFailure) {
                ex.addSuppressed(rollbackFailure);
            }
            throw ex;
        } finally {
            this.db.setAutoCommit(true);
        }
    }

    @Override
    public synchronized void putIdMapping(int id, ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.duplicate().get(bytes);
        try (var update = this.db.prepareStatement("INSERT OR REPLACE INTO mappings(id, data) VALUES(?, ?)")) {
            update.setInt(1, id);
            update.setBytes(2, bytes);
            update.executeUpdate();
        } catch (SQLException ex) {
            throw storageFailure(ex);
        }
    }

    @Override
    public synchronized Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        var mappings = new Int2ObjectOpenHashMap<byte[]>();
        try (var query = this.db.createStatement(); var result = query.executeQuery("SELECT id, data FROM mappings")) {
            while (result.next()) mappings.put(result.getInt(1), result.getBytes(2));
            return mappings;
        } catch (SQLException ex) {
            throw storageFailure(ex);
        }
    }

    @Override
    public synchronized void iteratePositions(int level, LongConsumer consumer) {
        try (var query = this.db.prepareStatement("SELECT id FROM section_v1 WHERE level=?")) {
            query.setInt(1, level);
            try (var result = query.executeQuery()) {
                while (result.next()) consumer.accept(result.getLong(1));
            }
        } catch (SQLException ex) {
            throw storageFailure(ex);
        }
    }

    @Override
    public void flush() {
        // SQLite synchronizes the WAL when a transaction commits.
    }

    @Override
    public synchronized void close() {
        try {
            this.db.close();
        } catch (SQLException ex) {
            throw storageFailure(ex);
        }
    }

    private static byte[] encode(LodSectionSnapshot section) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            var coverage = section.coverage();
            output.writeByte(section.children());
            output.writeByte(coverage.mode());
            for (int index = 0; index < coverage.serializedWordCount(); index++) output.writeLong(coverage.word(index));
            for (long voxel : section.voxels()) output.writeLong(voxel);
        }
        return bytes.toByteArray();
    }

    private static LodSectionSnapshot decode(long key, byte[] data) throws IOException {
        try (var input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            int level = WorldEngine.getLevel(key);
            byte children = input.readByte();
            SurfaceDataCoverage coverage = switch (input.readUnsignedByte()) {
                case 0 -> SurfaceDataCoverage.legacy(level);
                case 1 -> SurfaceDataCoverage.raw(level);
                case 2 -> {
                    long[] words = new long[((1 << (3 * (level + 1))) + 63) / 64];
                    for (int i = 0; i < words.length; i++) words[i] = input.readLong();
                    yield SurfaceDataCoverage.fromWords(level, words);
                }
                default -> throw new IOException("Unknown server section coverage mode");
            };
            long[] voxels = new long[WorldSection.SECTION_VOLUME];
            for (int i = 0; i < voxels.length; i++) voxels[i] = input.readLong();
            if (input.read() != -1) throw new IOException("Unexpected data after stored Voxy section");
            return new LodSectionSnapshot(key, children, coverage, voxels);
        }
    }

    private static IllegalStateException storageFailure(Exception ex) {
        return new IllegalStateException("Voxy server section storage failed", ex);
    }
}

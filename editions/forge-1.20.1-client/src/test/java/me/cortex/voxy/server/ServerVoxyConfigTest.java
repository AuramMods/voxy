package me.cortex.voxy.server;

import com.electronwill.nightconfig.toml.TomlFormat;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerVoxyConfigTest {
    @Test
    void existingOnlineLimitSurvivesAddingTheIdleDefault() {
        var config = TomlFormat.instance().createParser().parse("chunkJobs = 7");
        ServerVoxyConfig.SPEC.correct(config);

        assertEquals(7, config.getInt("chunkJobs"));
        assertEquals(4, config.getInt("idleChunkJobs"));
    }

    @Test
    void selectsTheLimitFromTheSameTomlUsedByAnIntegratedServer() {
        var format = TomlFormat.instance();
        var template = format.createParser().parse("""
                distantGeneration = false
                radiusChunks = 12
                chunkJobs = 2
                idleChunkJobs = 6
                spawnDimensions = ["future:world", "minecraft:overworld"]
                spawnAnchors = ["future:world=-64,32"]
                """);
        ServerVoxyConfig.SPEC.correct(template);
        var worldConfig = format.createParser().parse(format.createWriter().writeToString(template));
        assertTrue(ServerVoxyConfig.SPEC.isCorrect(worldConfig));

        ServerVoxyConfig.SPEC.setConfig(worldConfig);
        try {
            assertEquals(2, ServerVoxyConfig.chunkJobs(true));
            assertEquals(6, ServerVoxyConfig.chunkJobs(false));
            var snapshot = ServerVoxyConfig.snapshot();
            assertEquals(List.of("future:world", "minecraft:overworld"), snapshot.get("spawnDimensions"));
            assertEquals(List.of("future:world=-64,32"), snapshot.get("spawnAnchors"));
        } finally {
            ServerVoxyConfig.SPEC.setConfig(null);
        }
    }
}

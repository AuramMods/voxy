# Auram Voxy smoke client

## Test profile

Use a separate Prism instance with Minecraft 1.20.1, Forge 47.4.10, and ARM64 Java 17. The original Auram instance supplies the Embeddium 0.3.31 and Oculus 1.8.0 jars and the original AuramBliss presets. The shader source is checked in under `shaderpacks/AuramBliss/`.

| Mod | Purpose |
| --- | --- |
| Embeddium 0.3.31 | Near-terrain renderer |
| Oculus 1.8.0 | Shader pipeline |
| GLA 1.0.1, Modrinth version `NbMH9A4j` | Modern OpenGL on Apple Silicon |
| `auramphoton-compat-0.1.0-dh333.jar` | Provides AuramBliss's raster light volume and its Oculus and Embeddium hooks |

Set Minecraft's master volume to zero and disable pause on loss of focus for background testing. Use a disposable world. Keep Distant Horizons off the mod list.

## Reproduce the client

1. Create an isolated Prism profile named **Auram Voxy Test**. Add Forge 47.4.10, select an ARM64 Java 17 runtime, and add the mods from the table.
2. Copy the Forge client jar from the edition's `build/libs/` into the profile's `minecraft/mods/`. Copy the checked-in `shaderpacks/AuramBliss/` directory into the profile's `minecraft/shaderpacks/`. Copy the source instance's `AuramBliss.txt` preset alongside it and select AuramBliss in Oculus. The Mac smoke run used `TAA_UPSCALING=true` and `SCALE_FACTOR=0.80`.
3. For a terrain control, select six chunks of near render distance and disable shaders. In a disposable world with generated terrain beyond that radius, run `/voxy import current` and check for distant terrain. Close and reopen the client and check the same area without another import.
4. Enable AuramBliss and check the same view. In-game shader reload should recreate the Voxy pipeline and keep the LoDs on screen. For the prepared **Voxy Smoke** world, the pond begins at Z 162, the white bridge continues to Z 194, and the candle fixture is just south of spawn. A Nether round trip exercises the dimension-specific patches.

The installed profile ID is `AuramVoxyTest`. Its local background helper disables GLFW focus on show and routes smoke input to the isolated client only. The helper source and command runner are in that profile's `qa/` directory. Screenshots are under `evidence/`.

## GL and shader baseline

Embeddium reported `Apple M4 Max` and `4.6.0 Metal - 91.7 (gla 1.0.1)`. Oculus selected the copied AuramBliss pack and initialized its 128³ raster colored-light volume. The existing compatibility jar logged missing optional integration classes, including its unused Distant Horizons mixins. The launch also logged a `GL_INVALID_VALUE` from `glTexImage2D`; shader selection and raster light volume initialization continued.

## Forge source and native build

The standalone Forge edition under `editions/forge-1.20.1-client/` and its shared seed-generation sources originate from [Neo Voxy commit `4ba81de`](https://github.com/NHblock-Johnsnow/neo-voxy-multiversion/tree/4ba81de3fc70cab4d5049a2dda5fce5a7d68fe04). With a Java 17 JDK, run `./gradlew build` from the edition directory. The remapped client jar is under `build/libs/`.

The jar contains ARM64 macOS, x64 Windows, and x64 Linux natives for RocksDB, SQLite, LWJGL LMDB, and LWJGL Zstandard. Windows uses its native OpenGL driver. GLA is a separate Mac test dependency.

The first shaders-off run reached the terrain renderer and failed in JOML 1.10.5's native-address matrix writer. The renderer, Iris uniform uploads, and software rasterizer now marshal their values into native buffers without assuming Java object field offsets.

In the disposable `Voxy Smoke` world, the blue and red landmarks sit 184 and 208 blocks south of the spawn platform. At six chunks of near render distance, both were absent before `/voxy import current` and visible afterward. Both were still visible after a clean shutdown and restart without re-importing. The shaders-off run used `NormalRenderPipeline` and `MDICSectionRenderer`. The local baseline, import, and restart screenshots are in the Prism test instance's `evidence/` directory.

## Smoke results

| Check | Result |
| --- | --- |
| Original upscale preset | The red and blue distant landmarks and the bridge rendered through `IrisVoxyRenderPipeline` and `MDICSectionRenderer`. |
| Water, glass, and fog | The pond and glass rim rendered through the translucent pass. Clear, rain, and midnight screenshots record the same distant water and bridge view. |
| Shadows and raster colored lighting | The daylight fixture shows a shadow. At midnight the same wall and ground show separate red and blue candle lighting. Oculus logged both Embeddium terrain and Oculus entity occupancy captures. |
| Shader reload and dimension changes | Reload rebuilt the Voxy renderer and preserved the scene. The Overworld and Nether both created the patched shader pipeline during a round trip. |
| Rebuilt artifact | A forced Gradle rebuild was installed with a fresh copy of the checked-in shader and launched into the same scene. The rebuilt jar and installed jar matched byte for byte. See `13-verified-rebuild.png`. |
| Background and sound | GLFW focus was disabled for the isolated client. The active application stayed in front during the final launch, and the profile's master volume was zero. |
| Windows | The jar retained x64 Windows natives for all four storage libraries and declares no GLA dependency. The available Parallels Windows entries were invalid, so Windows runtime behavior remains unverified. |

## Full Auram launch regression

The full-pack smoke profile is `AuramVoxyFullTest` in Prism. It uses the same 541 enabled mod jars as Auram, including Connector and Impersonate. Leave Distant Horizons installed and set `[client.advanced.debugging].rendererMode` to `"DISABLED"` in `config/DistantHorizons.toml`. The profile uses the AuramBliss shader and preset from the pack, five chunks of near render distance, zero master volume, and the disposable **Voxy Smoke** world.

The previous jar initialized Voxy's serializers while its mixin plugin was checking for optional mods. That loaded Minecraft's `Component` before Connector prepared Impersonate's mixin. The Forge plugin now checks Forge's loading metadata directly, and Voxy's serializers initialize on the render thread after mixin preparation.

The rebuilt jar entered the smoke world and started `IrisVoxyRenderPipeline` with `MDICSectionRenderer`. Both distant landmarks and the bridge rendered outside the five-chunk near distance on both the initial run and after a clean restart without another import. The local screenshots are in the full-pack profile at `evidence/02-full-auram-voxy.png` and `evidence/03-full-auram-voxy-restart.png`.

Custom dimensions and general gameplay performance in the full pack remain unverified. Shader changes are recorded in [AuramBliss changes for Voxy](aurambliss-voxy-changes.md).

## Shader reload slowdown on GLA 1.0.1

The uncapped Auram copy reached 85, 103, 98, and 5 FPS before and after three shader reloads at a fixed Overworld camera. Shutting down Voxy's renderer left the client at 5 FPS. A native profile showed time in Apple's texture upload path during Embeddium terrain draws. Pausing Minecraft's block atlas animation tick recovered 103 FPS, and resuming it brought the slowdown back. The Nether portal sprite could trigger the stall on its own.

Auram's block atlas measures 16384 by 16384 pixels. For atlases of this size on GLA, the Forge client uploads animated frames into a small reusable texture and blits them into the atlas on the GPU. Each copy uses Minecraft's original frame rectangle and mip level. The client releases the staging texture and framebuffers at shutdown.

The rebuilt client measured 62 FPS after launch and 80 FPS after eight confirmed shader reloads. Four different portal frames read back from the atlas matched their source pixels exactly. The client reopened the same local world twice without restarting. After another shader reload following each rejoin, it measured 47 and 68 FPS, and portal frames still updated. Remote multiplayer reconnects remain unverified.

## Three-chunk terrain handoff

The macOS smoke client used three chunks of near render distance. It crossed multiple chunk coordinates over the shoreline at Y 102, then toggled shaders off and on and reloaded AuramBliss. The shoreline and distant water were visible after the renderer settled. A temporary magenta material identified pixels where Voxy and Embeddium drew matching opaque surfaces inside the overlap. The regular shader has no diagnostic color.

The screenshots are in `evidence/three-chunk-handoff/` in the Prism smoke profile. A shader reload still rebuilds the Voxy draw data, which can briefly leave the scene incomplete.

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

## GL and shader baseline

Embeddium reported `Apple M4 Max` and `4.6.0 Metal - 91.7 (gla 1.0.1)`. Oculus selected the copied AuramBliss pack and initialized its 128³ raster colored-light volume. The existing compatibility jar logged missing optional integration classes, including its unused Distant Horizons mixins. The launch also logged a `GL_INVALID_VALUE` from `glTexImage2D`; shader selection and raster light volume initialization continued.

## Forge source and native build

The standalone Forge edition under `editions/forge-1.20.1-client/` and its shared seed-generation sources originate from [Neo Voxy commit `4ba81de`](https://github.com/NHblock-Johnsnow/neo-voxy-multiversion/tree/4ba81de3fc70cab4d5049a2dda5fce5a7d68fe04). With a Java 17 JDK, run `./gradlew build` from the edition directory. The remapped client jar is under `build/libs/`.

The jar contains ARM64 macOS, x64 Windows, and x64 Linux natives for RocksDB, SQLite, LWJGL LMDB, and LWJGL Zstandard. Windows uses its native OpenGL driver. GLA is a separate Mac test dependency.

The first shaders-off run reached the terrain renderer and failed in JOML 1.10.5's native-address matrix writer. The renderer, Iris uniform uploads, and software rasterizer now marshal their values into native buffers without assuming Java object field offsets.

In the disposable `Voxy Smoke` world, the blue and red landmarks sit 184 and 208 blocks south of the spawn platform. At six chunks of near render distance, both were absent before `/voxy import current` and visible afterward. Both were still visible after a clean shutdown and restart without re-importing. The shaders-off run used `NormalRenderPipeline` and `MDICSectionRenderer`. The local baseline, import, and restart screenshots are in the Prism test instance's `evidence/` directory.

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

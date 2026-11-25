# AuramBliss changes for Voxy

The original pack is recorded in commit `2ad07db5`. The version-1 Voxy integration uses [Bliss Unstable at `5609760`](https://github.com/X0nk/Bliss-Shader/tree/5609760a2083af89fbad18f1a1e85cc93a1fa4b3) as its reference.

| Shader file or pass | Change |
| --- | --- |
| `shaders/dimensions/voxy.json` | Adds the Voxy patch descriptor. Opaque terrain writes to AuramBliss's material, specular, and normal/AO buffers 1, 8, and 15. Translucency writes forward color and material to buffers 2 and 7. The depth textures remain separate from Minecraft's depth textures. |
| `shaders/dimensions/voxy_opaque.glsl` | Packs textured LoD terrain, normals, ambient occlusion, and lightmaps for AuramBliss's deferred lighting. Encodes the normal buffer at the existing Mac compatibility precision. |
| `shaders/dimensions/voxy_translucent.glsl` | Adapts distant water and glass to AuramBliss's forward targets, water normals, reflection options, lighting scale, and alpha. Reconstructs view position at the active render scale. The reference pass produced bright water bloom at night with this pack. The corrected pass was checked from the same camera at night. |
| `shaders/dimensions/voxy_taa.glsl`, `shaders/shaders.properties` | Shares AuramBliss's frame jitter and respects the TAA option. The descriptor reads the existing upscale factor. |
| `shaders/world*/voxy*` | Connects the dimension entry points to the shared patch, with the corresponding shader definitions for the Overworld, Nether, and End. |
| `shaders/lib/auram_lod_depth.glsl`, `shaders/lib/settings.glsl` | Selects the active LoD depth and projection inputs. Normalized Voxy sampler coordinates account for its scaled depth textures. Mac packed depth also covers Voxy frames. |
| `deferred1.fsh`, `deferred2.fsh`, `composite.fsh`, `composite1.fsh`, `composite2.fsh`, `composite3.fsh`, `composite5.fsh`, `composite6.fsh`, `composite11.fsh`, `fogBehindTranslucent_pass.fsh` | Wires the shader's existing fog, depth composition, lighting, and temporal passes to the selected LoD textures. The Distant Horizons shadow and overdraw switches still apply to Distant Horizons. |
| `shaders/lib/waterBump.glsl` | Uses `texture()` for all four noise samples, which lets the shared code compile in Voxy's core-profile fragment shader. |

The Forge bridge exports Voxy's actual near and far planes and its render distance in blocks for these passes. Oculus reload recreates the Voxy renderer after replacing its framebuffers. The Mac raster light-volume capture still comes from Auram's compatibility jar.

The Mac smoke run verified the original 0.8-scale TAA preset, colored distant landmarks, near and distant water and glass, and the stone bridge through the near-to-distant transition. Daylight and nighttime fixtures show shadows and red and blue raster light respectively. The Overworld and Nether both created the patched pipeline. The initial profile excludes the mods that provide Auram's custom dimensions, so their entry points still need full-pack testing.

## View-dependent shadows on distant terrain

The cloud shadow pass reconstructs Voxy pixels from Voxy's opaque depth and inverse projection. It applies the current render scale and TAA offset before converting the position to world space. Previously the pass sampled cloud shadows at Minecraft's far-plane position, which moved the shadow pattern over distant geometry as the camera turned.

In a copy of the Auram world, the same two camera angles showed dark patches on a distant rock face with the old shader. Reloading the patched shader removed those patches while retaining the rock face and nearby terrain. This change takes effect on reload in Oculus.

## Bright ice and glass at night

The Voxy translucent pass referenced an undefined `debug_ALBEDO` mode. GLSL evaluated it as zero, so the branch also ran in the default `debug_OFF` mode and overwrote the lit color with the raw texture color. Removing the unused branch restores the forward lighting and reflection calculation for distant translucent blocks, including water. The midnight smoke fixture showed normal lighting on the ice after an Oculus reload.

## Volumetric clouds in front of and behind Voxy terrain

The cloud ray marcher now stops at Voxy's depth when a pixel contains distant terrain. It uses the same inverse-projection helper as the cloud shadow calculation. At the same camera position, a distant panel below the cloud layer occluded clouds behind it after reload, while a higher panel still had clouds in front.

## Shader water beyond the near render distance

The water passes now reconstruct both the Voxy water surface and the opaque terrain beneath it. This gives absorption, caustics, and underwater fog the depth difference they need. In the daylight smoke test, the distant river regained a visible surface and hid the deeper riverbed. At midnight, the near and distant water stayed dark.

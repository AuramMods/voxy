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

## Water transition and cloud reflections

AuramBliss opts into Voxy's water overlap through `voxy.json`. Voxy uses Bliss's dither pattern across the overlap band for top faces of water. The near pass checks the Voxy front and opaque depths before yielding a pixel, so a distinct surface can still render. Near water gradually reduces its screen-space reflections as the distant pass takes over, and both passes use the same wave strength.

Water reflections map the reflected ray to the lowest cloud layer and aim the camera-centered sky lookup at that point. Both passes also use the sky atlas's vertical coordinate convention. The Mac pond fixture crosses the near render boundary and was checked from two heights. A temporary diagnostic color confirmed that Voxy draws water inside the near section bounds. The overlap requires the matching Voxy jar and a client restart.

## Lighting across the terrain boundary

Voxy converts lightmap sample centers to the same normalized levels as near terrain. Near and distant foliage share the same subsurface-scattering assignments. Voxy now writes raw specular channels to `colortex8` for the compatibility pass to pack, and the lighting pass reconstructs Voxy receivers at their actual depth.

The spruce panel fixture and open terrain were checked across shader reloads. The distant bridge still differed in brightness at the geometry boundary. Opaque terrain continues to switch renderers at the section boundary.

## Depth at the water and lighting handoffs

The Voxy water pass checks the opaque depth from Embeddium before applying the overlap dither. This lets distant water cover a nearer seabed when the water is in front of it. The fog and water passes compare the near and Voxy front depths in view space, so they use the surface that actually covers the pixel.

The penumbra, ambient occlusion, and contact shadow passes now use Voxy's projection for Voxy terrain. With the same camera and daylight, a strip of the test bridge that was visibly darker than the rest of the bridge matched the surrounding blocks after shader reload.

The full Auram fixture uses five chunks of near render distance and includes a pool with shallow and deep sections, a stone pillar, and a continuous bridge. The rebuilt client draws water over both pool floors from two camera positions. A temporary diagnostic color confirmed Voxy water coverage in front of Embeddium's opaque depth. The water change requires the rebuilt jar and a client restart.

## Sky-colored disc below the camera

Bliss's border fog used the straight-line distance to the camera. The vertical distance could push nearby water into full fog, replacing its color and reflections with the sky color. The near and deferred passes now measure horizontal distance and use Voxy's render range when it is active. Distant Horizons retains its original radial fade.

In the copied Auram world, disabling border fog removed the disc around the ship at the original camera position. The patch removed it with border fog enabled at Y 142 and Y 170. Oculus picked up the change on shader reload.

## Lighting filter at the canopy boundary

The ambient occlusion and shadow filter read only Minecraft's depth. A Voxy leaf and an empty sky pixel could both appear at the far plane, so the filter could borrow lighting from across the leaf silhouette. The filter now compares the near and Voxy opaque surfaces in view-space blocks and rejects taps across depth breaks. The macOS compatibility path reads both raw depths from the buffer already produced by Bliss.

The old and new filters ran in the same copied Auram scene at the pumpkin hill. The exact thin line in the original screenshot still needs a comparison from that camera position.

## Terrain handoff at three chunks

The Forge renderer records solid sections that Embeddium added to its current terrain draw batches. In the interior, it hides cached terrain only in those same sections. It keeps one chunk of overlap at the edge so empty near pixels can still show Voxy terrain.

AuramBliss enables the opaque transition in `voxy.json`. The Voxy fragment shader compares each candidate with the near surface in view space. Matching surfaces share a dithered band. Each pixel keeps one material, and the near depth remains available to the deferred passes. Outside the near interior, the water patch keeps Voxy water in front of a nearer renderer's seabed. AuramBliss still checks that the near and Voxy water surfaces match before it yields to Voxy.

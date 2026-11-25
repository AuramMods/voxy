# Server Voxy LODs on Forge 1.20.1

Install the same Voxy build on the Forge server and on clients that should receive its terrain. The server runs without OpenGL, Embeddium, Oculus, or a shader pack. It sends compressed voxel sections. The client resolves block and biome names against its registries, bakes resource-pack models, and builds the GPU meshes locally.

The LOD channel is optional. A Voxy client can still ingest terrain from a server without the channel. Clients without Voxy can use the server when its other mods permit them to join. On a connection with the LOD channel, the streamed cache is authoritative. Minecraft draws ordinary chunks and Voxy consumes the server's LOD updates for distant terrain.

## Server configuration

The same generator runs in singleplayer and on dedicated servers. Open **Video Settings → Voxy Server** in Embeddium to change the settings. Applying changes updates the open singleplayer world and saves a portable template at `defaultconfigs/voxy-server.toml` in the Minecraft instance.

Copy that template to `<world>/serverconfig/voxy-server.toml` for an existing dedicated-server world, then restart the server. Put the file in the server's `defaultconfigs/` directory to use it for new worlds. These settings are independent of `view-distance` and `simulation-distance` in `server.properties`.

| Option | Default | Controls |
| --- | --- | --- |
| `distantGeneration` | `true` | Generate missing chunks. Turn this off to stream cached LODs and backfill chunks Minecraft has already saved. |
| `radiusChunks` | `256` | Horizontal radius in Minecraft chunks for server generation and streaming. The client rendering cutoff also defaults to 256 chunks and can be lowered locally. |
| `chunkJobs` | `4` | Maximum concurrent Voxy generation and backfill jobs with players online, including the singleplayer host. Existing configurations keep this value. |
| `idleChunkJobs` | `4` | Maximum concurrent Voxy generation and backfill jobs when no players are online. |
| `spawnDimensions` | `['minecraft:overworld', 'minecraft:the_nether', 'minecraft:the_end']` | Ordered list of dimensions with spawn anchors. IDs for missing dimensions can stay in the list. |
| `spawnAnchors` | `[]` | Optional overrides in `dimension=x,z` form using block coordinates. The Overworld uses its saved spawn and other dimensions default to X/Z `0,0`. |

With players online, the generator considers only dimensions with at least one player. Within an occupied dimension, every player is an anchor. A listed dimension also contributes its spawn anchor. The closest eligible missing chunk across those anchors wins the next ticket, with deterministic ties.

When nobody is online, Voxy fills the spawn radius of the first available dimension with unfinished coverage. It completes that dimension before issuing a ticket for the next one. The console reports chunks per second every 60 seconds, including newly generated and reused chunks, dimensions that were generating, and active ticket count. Start a disposable server with `-Dvoxy.traceServerLodJobs=true` to log individual ticket acquisitions and releases.

The server switches job limits as players join or leave. Previously started work can finish when the limit drops. Minecraft manages the world-generation threads and can load dependency chunks for each Voxy ticket.

## Persistence and reconnects

- The world identity is stored in `<world>/voxy-server/world-id`. Each dimension stores its LODs and coverage in `voxy-server/lod.sqlite` within that dimension's save directory. For example, the Nether uses `<world>/DIM-1/voxy-server/lod.sqlite`.
- Prism clients store downloaded terrain under `minecraft/.voxy/saves/<server-address>/remote-lod/<world-and-dimension>/lod.sqlite`. A warm reconnect checks cached content before downloading tile payloads. The client can keep displaying cached coarse geometry during refinement.
- Include the world ID and LOD databases with vanilla region data in a coordinated backup or restore. If restoring only region files, stop the server and move the corresponding `voxy-server` directories out of the save before starting it. Voxy can backfill the restored regions with generation disabled.
- The optional channel scopes packets by world, dimension, and connection. Portable content fingerprints reconcile caches across server restarts and restores, even when the restored server has lower revision numbers.

The renderer uses the current camera, viewport, and render precision to estimate the section detail it needs. The client prioritizes visible groups with missing data and follows the cached child masks to locate finer geometry. It also includes the coarse ancestors required to draw those groups. Turning, moving, and zooming can change the priority. The server checks every requested tile against the player's actual dimension and position. Fingerprint reconciliation keeps cached payloads reusable when the view priority changes.

Background delivery sends the coarsest level across the entire server radius. Its finer levels reach approximately 12, 24, 48, and 96 chunks for LODs 0 through 3. A visible request can refine terrain farther away when its projected size calls for it. The client keeps coarse geometry available while it receives the finer tiles needed to replace it. Stream payloads and offers share a 512 KiB/s per-client and 2 MiB/s global budget.

## Verification

Run `test build` from `editions/forge-1.20.1-client` with Java 17. For a smoke test, use a disposable Forge server with saved terrain beyond three chunks, a 12-chunk Voxy radius, and distant generation disabled. Join with an empty Voxy cache and a three-chunk client view distance. Capture the horizon, then reconnect after the stream finishes to measure the downloaded payload again. To test live edits, force-load a target outside vanilla view distance and add and remove a visible landmark there.

Measured on macOS with Forge 47.4.10, Java 17, GLA, Embeddium, Oculus, and AuramBliss. The test used a pregenerated flat world with structures. Timings start when the client reports that it opened the LOD stream.

| Measurement | Result |
| --- | --- |
| First visible distant terrain | By 2.8 seconds. The eight coarsest tiles were cached by 1.8 seconds. |
| Refinement | The first LOD 0 tile arrived by 15.7 seconds. All 1,722 scoped tiles were confirmed by 104 seconds. |
| Cold tile payload | 232,422 compressed bytes received, including retransmissions. |
| Server tick time | 5.69 ms mean at 20 TPS near the end of the cold run. An overlapping client launch and generation test on the same host produced a 56.43 ms sample at 17.72 TPS. |
| Offline generation throughput | 1.06 newly generated columns per second in one measured 60-second window, with a four-ticket peak. |

The same camera showed only sky in the control run on a Forge client without Voxy. A distant tower added and removed seven chunks from the client appeared and disappeared live. After restoring the regions and Voxy database together, the client accepted the older authoritative revisions. The ticket trace recorded four releases during shutdown. It also recorded four releases followed by retries after a deliberately induced timeout.

In a separate 128-chunk test, a landmark 50 chunks away was cached at LODs 3 and 4 while its LOD 0 tile stayed out of the client cache. After teleporting to within 10 chunks, the fine tile arrived in 15.2 seconds. The vanilla view distance stayed at three chunks. After the cache caught up, reconnecting at the same position received zero compressed tile payload bytes during the 44-second check.

The 541-jar Auram client connected at three-chunk vanilla distance and rendered a streamed tower with AuramBliss. Both the server and the client recorded block light levels of 15, 14, and 13 at the glowstone and the two voxels above it. The disposable server used the pack's server-side exclusions, retained GuideME for Applied Energistics 2, and used a 240-second startup watchdog threshold to let WorldEdit finish indexing block states. Distant Horizons rendering was disabled on the client. The available Windows VM entries were invalid, so the platform smoke test was on macOS.

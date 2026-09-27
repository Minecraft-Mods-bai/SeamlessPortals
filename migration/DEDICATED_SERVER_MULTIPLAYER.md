# Dedicated Server / Multiplayer — the 2026-09-27 engagement

The user's report: both loaders' dedicated servers died at boot —
`NoClassDefFoundError: net/minecraft/client/multiplayer/ClientLevel` thrown from
`BlockStateBaseFractionalMixin.cutCollisionShape` inside `Blocks.<clinit>` (NeoForge 26.2.0.88
and Fabric 0.19.x alike). This document is the ledger: mechanism, the oracle that found every
sibling, the fixes, the multiplayer harness, and what was measured.

## 1. Mechanism (the dist doctrine, re-proven — NEOFORGE_PARITY.md §3)

The mixin handler itself names no client class; it calls `SeamFractional`, and THAT class links
(is verified by HotSpot) the first time the handler runs — during `Blocks.<clinit>`. Two bodies in
it needed an assignability proof involving a client-only class (`ClientLevel` handed to a `Level`
parameter in `tryTwoObjectPlacement`), and HotSpot can only complete such a proof by LOADING the
class. On a dedicated server it does not exist. NeoForge strips nothing; Fabric strips only
`@Environment`-annotated members, and these bodies (and every lambda) carried no annotation.

Everything found had the same shape and the same origin: code added after the August parity
pass, written and tested only in singleplayer, where client and server share one JVM.

## 2. The oracle (scratch tools; method recorded in memory `dedicated-server-link-oracle`)

`LinkCheck`: for every non-mixin class, `Class.forName(name, false, loader)` against a
SERVER-ONLY classpath (loom's `minecraft-extracted_server.jar` / the moddev patched jar filtered
to the server dist), then force LINKING with `Class.getDeclaredFields0(true)`. Exactly the
verifier the real server runs. `RefScan`: per-method client references (vanilla client types AND
the mod's own client-only classes) for the execution-time cases the verifier cannot see, and for
server-applied mixins (whose handler bodies are verified inside the target class).

Result before the fixes: 96 classes fail to link under the strict (NeoForge) condition, of
which 5 are server-reachable; after the fixes: 0 server-reachable (the remaining 90-odd are
GUI/render/gametest/RPC-target classes only a client ever loads).

## 3. Fixes (all in `:common`, both loaders)

| Class | Hazard | Split |
|---|---|---|
| `SeamFractional` | `outlineShape` read `Minecraft.hitResult` + `SeamCounterpartOutline` statics; `tryTwoObjectPlacement` cast `ClientLevel` + `ClientWorldLoader.peekWorld` | `SeamFractionalClient` (Level-typed entries), reached only inside `isClientSide()` |
| `AperturePassthroughInit` | client lambdas (SameDimRemesh drain, seam-clip flush, ride sampler with `LocalPlayer→Entity`, client-view probe) registered from server/common init | `AperturePassthroughClientInit.init()`, called only when `!Platform.isDedicatedServer()`, same registration slot/order |
| `ScaleUtils` (IP) | `onClientPlayerTeleported` body (`LocalPlayer→Entity`) made the whole class fail to link on NeoForge; reached by `ServerTeleportationManager` on the first teleport | `ScaleUtilsClient`, delegating stub kept for IP's call site |
| `LevelChunkSetBlockStateMixin` | `instanceof ClientLevel` EXECUTED on every server block change + `ClientLevel→Level` proof inside the woven `LevelChunk` | `isClientSide()` + `SeamMirrorClient.onSeamCellChanged(Level, BlockPos)` |

Plus one multiplayer-correctness change: **`passthroughExtras` is server-authoritative over a
connection** (`SeamPassthroughSync`, payload `seamlessportals:seam_passthrough_config`, sent on
join before the occupancy burst and on every server-side config change; the client forgets it
when a new connection starts). Before, each side read its own file — a mismatch would cut seam
collision on one side only, a permanent rubber-band at every seam cell with no message.

## 4. Measured

| Leg | Result |
|---|---|
| Fabric `runServer` (worktree, flat world) | boots, `Done (2.636s)`, only vanilla OSHI perf-counter noise, clean `stop` |
| NeoForge `runServer` | boots, `Done (2.351s)`, 0 mod errors, all init lines present |
| Link oracle, both loaders, after fixes | 0 server-reachable failures |
| In-process dedicated server smoke (`runDedicatedServerGametest`) | join · portal sync · seam binding · same-dim crossing by WALKING (client/server gap 0.0) · overworld→nether and back by walking (gap 0.0 both ways) · remote-dim block sync · rejoin — see the run log for the exact lines |
| Separate-process legs | see §5 |

Pre-existing, NOT a multiplayer regression: `ImmPtlChunkTickets` "Chunk loading failure"
lines — present in the user's singleplayer client logs too.

Harness bound: vanilla's `LevelRenderer.hasRenderedAllSections()` never settles while a
same-dim portal is in view (SameDimRemesh re-meshes the destination region every tick, by
design), so the client-gametest framework's `waitForChunksRender` times out on any join AFTER
portals exist; the smoke waits for chunk DOWNLOAD instead and carries its own waits.

## 5. Separate-process topology (the user's actual setup)

Scripts (scratch): `fabric-server-standalone.sh` / `nf-server-standalone.sh` launch the exact
`runServer` command lines minus Gradle (ModDev's run task does not forward stdin, Loom's does;
standalone launch gives the console back). `tierb-fabric.sh` = standalone Fabric server + the
same gametest in EXTERNAL mode (`-PexternalServer=127.0.0.1:25565`: staging by chat commands as
the pre-opped `SmokePlayer`, verdicts from the client's own observations + position stability).
`tierb-neoforge.sh` = standalone NeoForge server + NeoForge client quick-play join, portals
created from the console, a server-side entity crossing, both logs read.

Results (2026-09-27, all with the SHIPPED jars from `:fabric:build` / `:neoforge:build`):

| Topology | Result |
|---|---|
| PRODUCTION Fabric server (loader 0.19.5, fabric-api 0.152.1+26.2, cloth 26.2.155 from Modrinth/maven, flat world, offline) | boots `Done (0.435s)`; the dev client joins as SmokePlayer (config-phase ImmPtl handshake logged both sides, passthrough switch synced), and the EXTERNAL smoke passes **ALL LEGS**: portal sync, seam binding (3 portals after rejoin), same-dim walk (drift 0.0), overworld→nether walk and back (drift 0.0), remote-dim block sync, rejoin |
| PRODUCTION NeoForge **26.2.0.88** server (the user's version; installer from maven, cloth jar-in-jar) | boots `Done (0.597s)`, full mod init, 0 mod errors (the `kqueue/epoll Native` lines are vanilla netty transport probing on Windows). A dev CLIENT (26.2.0.1-beta) is refused by NeoForge itself — `unknown registry key neoforge:gliding_flight` — a NeoForge version mismatch, so the join leg ran against… |
| PRODUCTION NeoForge 26.2.0.1-beta server (installer from maven, same shipped jar) | boots `Done (0.418s)`; the NeoForge dev client quick-play joins as SmokePlayer: config-phase ImmPtl handshake both sides, **passthrough switch synced server→client across a real mismatch** (`server says passthroughExtras=true (local config false)`), both portal pairs created from the console with IP's own commands (`Added Portal{...}` / `Created 3.0x3.0 portal`), the client created its nether view world for the cross-dim portal, a server-side entity crossing (summoned cow) and a nether block change were processed; the client's log then shows four client-detected crossings through the cross-dim pair (IP's "Client Teleported Statically" = a crossing detected in the tick, i.e. the client player moved) — no harness drove input on this loader, so those were a hand at the (interactive, focused) client window; 0 mod errors on either side |

Bound: the dev toolchain cannot build against 26.2.0.88 (ModDev/NeoForm's `createMinecraftArtifacts` recompile fails), so client-side behaviour on 0.88 specifically was not exercised; the mod's client↔server protocol is loader-neutral and was exercised on Fabric.

Harness notes: IP's `make_portal <origin> <rotation> …` overload is unreachable from chat (Brigadier resolves the shared literal to the `width height to dest` overload and silently keeps that partial parse — measured with the in-process parse probe), so external staging uses IP's player-relative recipe; `tp @s` is wrong for a console source (`@a` works for both); vanilla `waitForChunksDownload` reads a storage view centre IP's chunk map does not maintain.

## 6. How to re-run

```
.\gradlew.bat :fabric:runServer                      # eula/server.properties seeded in fabric/runs/server
.\gradlew.bat :neoforge:runServer
.\gradlew.bat :fabric:runDedicatedServerGametest      # in-process dedicated server + real socket
.\gradlew.bat :fabric:runDedicatedServerGametest -PexternalServer=127.0.0.1:25565
.\gradlew.bat :neoforge:runClient -PquickPlayServer=127.0.0.1:25565   # joins as SmokePlayer
```

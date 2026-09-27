package com.warwa.seamlessportals.fabric.gametest;

import com.warwa.seamlessportals.EntityPortalsFlag;
import com.warwa.seamlessportals.SeamlessPortalsConstants;
import com.warwa.seamlessportals.passthrough.AperturePassthroughInit;
import com.warwa.seamlessportals.passthrough.SeamPassthroughSync;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalManipulation;

import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * DEDICATED-SERVER / MULTIPLAYER SMOKE (2026-09-27) — the mod driven over a REAL network
 * connection to a REAL {@code DedicatedServer}, in one of two topologies:
 * <ul>
 *   <li><b>in-process</b> (default): the Fabric client-gametest API's
 *       {@code TestWorldBuilder.createServer()} starts a {@code DedicatedServer} inside this JVM
 *       (class-loading is NOT what this measures — that is the {@code runServer} boot legs + the
 *       link oracle), every packet crosses a real socket, staging runs as the server console;</li>
 *   <li><b>external</b> ({@code -Dseamlessportals.gametest.externalServer=host:port}): the client
 *       joins a dedicated server running in ANOTHER process — the real topology — as the opped
 *       fixed user {@code SmokePlayer}; staging goes through chat commands and every verdict is
 *       the client's own observation (dimension, position stability, entity/world contents).</li>
 * </ul>
 *
 * <p>Legs, all asserted, all with a diagnostic on failure:
 * <ol>
 *   <li><b>join</b> — connected, no integrated server, world rendered;</li>
 *   <li><b>portal sync</b> — a server-spawned bi-way same-dim pair appears as entities in the
 *       client world, and (passthrough extras ON) the client-side seam registry binds them;</li>
 *   <li><b>same-dim crossing by WALKING</b> — the client detects the crossing, tells the server,
 *       the server teleports; client and server agree afterwards (no rubber-band);</li>
 *   <li><b>cross-dim crossing by walking</b> (overworld → nether above the roof) and back;</li>
 *   <li><b>remote-dim block sync</b> — a block set in the nether while the player stands in front
 *       of the overworld portal reaches the client's per-dimension nether world;</li>
 *   <li><b>rejoin</b> — disconnect, reconnect: the portals are synced again.</li>
 * </ol>
 *
 * <p>Selection: runs only under {@code -Dseamlessportals.gametest.only=dedicated} (the
 * {@code dedicatedServerGametest} run config); it never joins the default suite.
 */
public class DedicatedServerSmoke implements FabricClientGameTest {

    private static final String LOG = "[DEDICATED SMOKE] ";

    /** Nether dest, above the bedrock roof (roof top y=127 is solid bedrock: flat, lava-free). */
    private static final Vec3 NETHER_DEST = new Vec3(0.5, 129.5, 0.5);

    @Override
    public void runTest(ClientGameTestContext context) {
        String only = System.getProperty("seamlessportals.gametest.only", "");
        if (!only.equals("dedicated")) {
            SeamlessPortalsConstants.LOGGER.info(LOG + "skipped (selected test: {})", only);
            return;
        }
        if (!EntityPortalsFlag.isOn()) {
            throw new AssertionError(LOG + "entityPortals flag is OFF — the dedicatedServerGametest "
                + "run config must seed entityPortals=true into <runDir>/config before launch.");
        }
        context.runOnClient(mc -> mc.options.renderDistance().set(8));
        // Passthrough extras ON for the seam-binding leg. In-process: one JVM, one config object,
        // so this is both sides' setting. External: the SERVER's file decides and its value is
        // synced to us on join (SeamPassthroughSync) — the server run dir seeds it ON.
        context.runOnClient(mc -> IPConfig.getConfig().passthroughExtras = true);

        String external = System.getProperty("seamlessportals.gametest.externalServer", "");
        if (external.isEmpty()) {
            Properties props = new Properties();
            props.setProperty("gamemode", "creative");
            props.setProperty("level-type", "minecraft:flat");
            props.setProperty("view-distance", "8");
            props.setProperty("spawn-protection", "0");
            try (TestDedicatedServerContext server = context.worldBuilder().createServer(props)) {
                SeamlessPortalsConstants.LOGGER.info(LOG + "in-process dedicated server started; connecting");
                Stage stage = new InProcessStage(context, server);
                try (TestServerConnection conn = server.connect()) {
                    settleAfterJoin(context, conn);
                    runLegs(context, stage);
                }
                SeamlessPortalsConstants.LOGGER.info(LOG + "reconnecting");
                try (TestServerConnection conn = server.connect()) {
                    settleAfterJoin(context, conn);
                    runRejoinLegs(context, stage);
                }
            }
        } else {
            SeamlessPortalsConstants.LOGGER.info(LOG + "EXTERNAL mode: joining {}", external);
            Stage stage = new ExternalStage(context);
            connectExternal(context, external);
            runLegs(context, stage);
            SeamlessPortalsConstants.LOGGER.info(LOG + "reconnecting");
            disconnectExternal(context);
            connectExternal(context, external);
            runRejoinLegs(context, stage);
            disconnectExternal(context);
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "ALL LEGS PASS");
    }

    // ------------------------------------------------------------------ the legs

    private void runLegs(ClientGameTestContext context, Stage stage) {
        legJoin(context);

        BlockPos feet = context.computeOnClient(mc -> mc.player.blockPosition());
        int px = feet.getX(), py = feet.getY(), pz = feet.getZ();
        SeamlessPortalsConstants.LOGGER.info(LOG + "player at ({},{},{}); staging", px, py, pz);
        for (String c : new String[] {
            // (no gamerules: their 26.2 command syntax differs and none is load-bearing here)
            "time set 1000", "weather clear",
            fill(px - 12, py, pz - 12, px + 12, py + 8, pz + 6, "minecraft:air"),
            "forceload add " + (px + 90) + " " + (pz - 12) + " " + (px + 110) + " " + (pz + 4),
            "execute in minecraft:the_nether run forceload add -16 -16 16 16",
            "execute in minecraft:the_nether run fill -8 128 -8 8 134 8 minecraft:air"
        }) {
            stage.command(c);
        }
        context.waitTicks(40);

        // ---- LEG 2: same-dim bi-way pair → synced + seam-bound on the client ----
        Vec3 aOrigin = new Vec3(px + 0.5, py + 1.5, pz - 4.5);
        Vec3 aDest = new Vec3(px + 100.5, py + 1.5, pz - 4.5);
        stage.spawnBiWayPair(Level.OVERWORLD, aOrigin, Level.OVERWORLD, aDest);
        stage.sameDimOrigin = aOrigin;
        legPortalSync(context, aOrigin, "same-dim");
        legSeamBinding(context, 2);
        aimAt(context, aOrigin.x, aOrigin.y, aOrigin.z);
        screenshot(context, "dedicated-1-samedim-view");

        // ---- LEG 3: same-dim crossing by walking ----
        walkThrough(context, stage, "same-dim", Level.OVERWORLD,
            new Vec3(px + 0.5, py, pz - 2.0), 180f, Level.OVERWORLD, aDest);
        screenshot(context, "dedicated-2-samedim-arrived");

        // ---- LEG 4: cross-dim (overworld -> nether above the roof) and back ----
        Vec3 bOrigin = new Vec3(px + 6.5, py + 1.5, pz - 4.5);
        stage.spawnBiWayPair(Level.OVERWORLD, bOrigin, Level.NETHER, NETHER_DEST);
        legPortalSync(context, bOrigin, "cross-dim (overworld side)");
        walkThrough(context, stage, "cross-dim ow->nether", Level.OVERWORLD,
            new Vec3(px + 6.5, py, pz - 2.0), 180f, Level.NETHER, NETHER_DEST);
        screenshot(context, "dedicated-3-nether-arrived");
        walkThrough(context, stage, "cross-dim nether->ow", Level.NETHER,
            new Vec3(NETHER_DEST.x, 128.0, NETHER_DEST.z - 2.0), 0f, Level.OVERWORLD, bOrigin);
        screenshot(context, "dedicated-4-back-in-overworld");

        // ---- LEG 5: remote-dim block sync through the portal view ----
        stage.command(String.format(Locale.ROOT, "execute in minecraft:overworld run tp @a %.2f %.2f %.2f 180 0",
            px + 6.5, (double) py, pz - 1.5));
        context.waitTicks(20);
        aimAt(context, bOrigin.x, bOrigin.y, bOrigin.z);
        legRemoteBlockSync(context, stage);
        screenshot(context, "dedicated-5-nether-view-with-gold");
    }

    private void runRejoinLegs(ClientGameTestContext context, Stage stage) {
        legJoin(context);
        legPortalSync(context, stage.sameDimOrigin, "same-dim after rejoin");
        legSeamBinding(context, 2);
        screenshot(context, "dedicated-6-rejoined");
    }

    private static void legJoin(ClientGameTestContext context) {
        String state = context.computeOnClient(mc -> "level=" + (mc.level == null ? null : mc.level.dimension().identifier())
            + " connection=" + (mc.getConnection() != null)
            + " integratedServer=" + (mc.getSingleplayerServer() != null)
            + " isLocalServer=" + mc.isLocalServer()
            + " player=" + (mc.player == null ? null : mc.player.getGameProfile().name())
            + " serverPassthroughExtras=" + SeamPassthroughSync.serverValue());
        SeamlessPortalsConstants.LOGGER.info(LOG + "join: {}", state);
        boolean ok = context.computeOnClient(mc -> mc.level != null && mc.player != null
            && mc.getConnection() != null && mc.getSingleplayerServer() == null && !mc.isLocalServer());
        if (!ok) {
            throw new AssertionError(LOG + "join FAILED — expected a remote (dedicated) connection: " + state);
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "leg join PASS");
    }

    /** A Portal entity within 1 block of {@code origin} exists in the client world. */
    private static void legPortalSync(ClientGameTestContext context, Vec3 origin, String what) {
        try {
            context.waitFor(mc -> clientPortalNear(mc, origin) != null, 400);
        } catch (Throwable t) {
            String seen = context.computeOnClient(mc -> {
                StringBuilder sb = new StringBuilder();
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof Portal p) sb.append(p.getUUID()).append('@').append(p.getOriginPos()).append(' ');
                }
                return sb.length() == 0 ? "(no portal entities in the client world)" : sb.toString();
            });
            throw new AssertionError(LOG + "portal sync FAILED (" + what + "): no portal near " + origin
                + " in the client world within 400 ticks. Client portals: " + seen, t);
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "leg portal sync PASS ({})", what);
    }

    private static void legSeamBinding(ClientGameTestContext context, int expected) {
        try {
            context.waitFor(mc -> AperturePassthroughInit.trackedPortalCount(mc.level) >= expected, 200);
        } catch (Throwable t) {
            int n = context.computeOnClient(mc -> AperturePassthroughInit.trackedPortalCount(mc.level));
            throw new AssertionError(LOG + "seam binding FAILED: client-side seam registry tracks " + n
                + " portal(s), expected >= " + expected + " (effective passthroughExtras="
                + SeamPassthroughSync.enabledForClient() + ", server value=" + SeamPassthroughSync.serverValue() + ")", t);
        }
        int tracked = context.computeOnClient(mc -> AperturePassthroughInit.trackedPortalCount(mc.level));
        SeamlessPortalsConstants.LOGGER.info(LOG + "leg seam binding PASS (client tracks {} portals)", tracked);
    }

    /**
     * Park the player at {@code standAt} looking along {@code yaw}, hold FORWARD, and require the
     * player to end up in {@code expectDim} near {@code expectNear} — as seen by the SERVER when we
     * can ask it, and in every case as seen by the client, which must then hold still (a server
     * correction would move it).
     */
    private static void walkThrough(
        ClientGameTestContext context, Stage stage, String leg,
        ResourceKey<Level> fromDim, Vec3 standAt, float yaw, ResourceKey<Level> expectDim, Vec3 expectNear
    ) {
        stage.command(String.format(Locale.ROOT, "execute in %s run tp @a %.2f %.2f %.2f %.0f 0",
            fromDim.identifier(), standAt.x, standAt.y, standAt.z, yaw));
        context.waitTicks(30);
        context.runOnClient(mc -> {
            mc.player.setYRot(yaw);
            mc.player.setXRot(0f);
            mc.player.yRotO = yaw;
            mc.player.xRotO = 0f;
        });
        context.waitTicks(5);
        SeamlessPortalsConstants.LOGGER.info(LOG + "{}: walking from {}", leg, describeClient(context));
        context.getInput().holdKeyFor(options -> options.keyUp, 45);

        String expectDimId = expectDim.identifier().toString();
        if (stage.canReadServer()) {
            String[] serverSeen = stage.pollServer(400, s -> {
                ServerPlayer p = s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
                if (p == null) return new String[] {"(no player)", "", ""};
                return new String[] {p.level().dimension().identifier().toString(),
                    String.valueOf(p.position().distanceTo(expectNear)), p.position().toString()};
            }, v -> v[0].equals(expectDimId) && Double.parseDouble(v[1]) < 8.0,
                leg + ": server never reported the player in " + expectDimId + " within 8 of " + expectNear);
            SeamlessPortalsConstants.LOGGER.info(LOG + "{}: server has the player in {} at {} (dist {})",
                leg, serverSeen[0], serverSeen[2], serverSeen[1]);
        }
        try {
            context.waitFor(mc -> mc.level != null && mc.player != null
                && mc.level.dimension().identifier().toString().equals(expectDimId)
                && mc.player.position().distanceTo(expectNear) < 8.0, 400);
        } catch (Throwable t) {
            throw new AssertionError(LOG + leg + " FAILED: the client never arrived in " + expectDimId
                + " within 8 of " + expectNear + "; client now " + describeClient(context), t);
        }

        context.waitTicks(40);   // let the confirmation round-trip and any correction settle
        Vec3 clientPos = context.computeOnClient(mc -> mc.player.position());
        String clientDim = context.computeOnClient(mc -> mc.level.dimension().identifier().toString());
        if (stage.canReadServer()) {
            Vec3 serverPos = stage.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).position());
            String serverDim = stage.computeOnServer(s -> s.getPlayerList().getPlayers().get(0).level().dimension().identifier().toString());
            double gap = clientPos.distanceTo(serverPos);
            SeamlessPortalsConstants.LOGGER.info(LOG + "{}: settled — client {} @ {} / server {} @ {} (gap {})",
                leg, clientDim, clientPos, serverDim, serverPos, gap);
            if (!clientDim.equals(serverDim) || gap > 2.0) {
                throw new AssertionError(LOG + leg + " FAILED: client " + clientDim + " @ " + clientPos
                    + " vs server " + serverDim + " @ " + serverPos + " (gap " + gap + ") — rubber-banding");
            }
        } else {
            // No server to ask: a correction would MOVE the idle client. Hold still and compare.
            context.waitTicks(40);
            Vec3 later = context.computeOnClient(mc -> mc.player.position());
            String laterDim = context.computeOnClient(mc -> mc.level.dimension().identifier().toString());
            double drift = clientPos.distanceTo(later);
            SeamlessPortalsConstants.LOGGER.info(LOG + "{}: settled — client {} @ {}, 40 ticks later {} @ {} (drift {})",
                leg, clientDim, clientPos, laterDim, later, drift);
            if (!clientDim.equals(expectDimId) || !laterDim.equals(expectDimId) || drift > 0.5) {
                throw new AssertionError(LOG + leg + " FAILED: client " + clientDim + " @ " + clientPos
                    + " then " + laterDim + " @ " + later + " (drift " + drift + ") — server correction / rubber-banding");
            }
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "leg {} PASS", leg);
    }

    private static void legRemoteBlockSync(ClientGameTestContext context, Stage stage) {
        BlockPos probe = new BlockPos(3, 129, 3);
        try {
            context.waitFor(mc -> ClientWorldLoader.peekWorld(Level.NETHER) != null
                && ClientWorldLoader.peekWorld(Level.NETHER).hasChunk(probe.getX() >> 4, probe.getZ() >> 4), 600);
        } catch (Throwable t) {
            throw new AssertionError(LOG + "remote-dim sync FAILED: the client's nether world never received the dest "
                + "chunk (nether world present=" + (ClientWorldLoader.peekWorld(Level.NETHER) != null) + ")", t);
        }
        stage.command("execute in minecraft:the_nether run setblock 3 129 3 minecraft:gold_block");
        try {
            context.waitFor(mc -> ClientWorldLoader.peekWorld(Level.NETHER) != null
                && ClientWorldLoader.peekWorld(Level.NETHER).getBlockState(probe).is(Blocks.GOLD_BLOCK), 300);
        } catch (Throwable t) {
            String now = String.valueOf(ClientWorldLoader.peekWorld(Level.NETHER) == null ? null
                : ClientWorldLoader.peekWorld(Level.NETHER).getBlockState(probe));
            throw new AssertionError(LOG + "remote-dim sync FAILED: gold block set on the server at nether "
                + probe + " never reached the client's nether world (client sees " + now + ")", t);
        }
        SeamlessPortalsConstants.LOGGER.info(LOG + "leg remote-dim block sync PASS");
    }

    // ------------------------------------------------------------------ stages

    /** How the test talks to the server: console + API in-process, chat commands externally. */
    private abstract static class Stage {
        final ClientGameTestContext context;
        Vec3 sameDimOrigin;

        Stage(ClientGameTestContext context) { this.context = context; }

        abstract void command(String cmd);
        abstract void spawnBiWayPair(ResourceKey<Level> fromDim, Vec3 origin, ResourceKey<Level> toDim, Vec3 dest);
        abstract boolean canReadServer();
        abstract <T> T computeOnServer(Function<MinecraftServer, T> read);

        <T> T pollServer(int timeoutTicks, Function<MinecraftServer, T> read, Predicate<T> ok, String failure) {
            T last = null;
            for (int waited = 0; waited <= timeoutTicks; waited += 5) {
                last = computeOnServer(read);
                if (ok.test(last)) return last;
                context.waitTicks(5);
            }
            throw new AssertionError(LOG + failure + " within " + timeoutTicks + " ticks. Last server view: "
                + (last instanceof Object[] a ? java.util.Arrays.toString(a) : String.valueOf(last))
                + "; client: " + describeClient(context));
        }
    }

    private static final class InProcessStage extends Stage {
        private final TestServerContext server;

        InProcessStage(ClientGameTestContext context, TestServerContext server) {
            super(context);
            this.server = server;
        }

        @Override void command(String cmd) { server.runCommand(cmd); }

        @Override boolean canReadServer() { return true; }

        @Override <T> T computeOnServer(Function<MinecraftServer, T> read) { return server.computeOnServer(read::apply); }

        @Override
        void spawnBiWayPair(ResourceKey<Level> fromDim, Vec3 origin, ResourceKey<Level> toDim, Vec3 dest) {
            // DIAGNOSTIC (log-only): how the console parses the chat-style make_portal text the
            // EXTERNAL stage sends — which node rejects it and where. Never affects the leg.
            for (String probe : new String[] {
                ExternalStage.makePortalCommand(fromDim, origin, toDim, dest, 0),
                ExternalStage.makePortalCommand(fromDim, origin, toDim, dest, 0).replaceFirst("^execute in \\S+ run ", "")
            }) {
                String verdict = server.computeOnServer(s -> {
                    var dispatcher = s.getCommands().getDispatcher();
                    var results = dispatcher.parse(probe, s.createCommandSourceStack());
                    StringBuilder sb = new StringBuilder();
                    sb.append("leftover='").append(results.getReader().getRemaining()).append("'");
                    sb.append(" nodes=").append(results.getContext().getNodes().size());
                    sb.append(" executable=").append(results.getContext().getCommand() != null);
                    results.getExceptions().forEach((node, ex) -> sb.append(" | ").append(node.getName()).append(": ")
                        .append(ex.getMessage()));
                    return sb.toString();
                });
                SeamlessPortalsConstants.LOGGER.info(LOG + "parse probe: {} -> {}", probe, verdict);
            }
            String ids = server.computeOnServer(s -> {
                ServerLevel from = s.getLevel(fromDim);
                Portal p = Portal.ENTITY_TYPE.create(from, EntitySpawnReason.COMMAND);
                if (p == null) throw new AssertionError(LOG + "Portal.ENTITY_TYPE.create returned null");
                p.setOriginPos(origin);
                p.setDestinationDimension(toDim);
                p.setDestination(dest);
                p.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 1, 0), 3, 3);
                McHelper.spawnServerEntity(p);
                Portal q = PortalManipulation.createReversePortal(p, Portal.ENTITY_TYPE);
                McHelper.spawnServerEntity(q);
                return p.getUUID() + " / " + q.getUUID();
            });
            SeamlessPortalsConstants.LOGGER.info(LOG + "spawned bi-way pair {} -> {} ({} -> {}): {}",
                fromDim.identifier(), toDim.identifier(), origin, dest, ids);
        }
    }

    /** External process: chat commands as the opped SmokePlayer; no server-side reads. */
    private static final class ExternalStage extends Stage {
        ExternalStage(ClientGameTestContext context) { super(context); }

        @Override
        void command(String cmd) {
            context.runOnClient(mc -> {
                if (mc.getConnection() == null) throw new AssertionError(LOG + "not connected; cannot send /" + cmd);
                mc.getConnection().sendCommand(cmd);
            });
            context.waitTicks(3);
        }

        @Override boolean canReadServer() { return false; }

        @Override <T> T computeOnServer(Function<MinecraftServer, T> read) { throw new UnsupportedOperationException(); }

        /**
         * IP's explicit-origin make_portal: origin, rotation (pitch yaw), width, height, scale, nbt.
         * Yaw 0 = the identity orientation (axisW +X, axisH +Y, normal +Z) — exactly the in-process
         * stage's setOrientationAndSize; yaw 180 = the reverse portal's flipped normal
         * (createReversePortal negates axisW).
         */
        static String makePortalCommand(ResourceKey<Level> inDim, Vec3 origin, ResourceKey<Level> toDim, Vec3 dest, int yaw) {
            return String.format(Locale.ROOT,
                "execute in %s run portal make_portal %.2f %.2f %.2f 0 %d 3 3 1 {dimensionTo:\"%s\",destinationX:%.2f,destinationY:%.2f,destinationZ:%.2f}",
                inDim.identifier(), origin.x, origin.y, origin.z, yaw, toDim.identifier(), dest.x, dest.y, dest.z);
        }

        /**
         * IP's own user recipe, because the explicit-origin {@code make_portal} overload is
         * unreachable from chat: Brigadier resolves the shared {@code make_portal} literal to the
         * FIRST overload ({@code width height to dest} — its dimension argument accepts any
         * identifier-shaped token at parse time), and silently keeps that partial parse (measured
         * with the in-process parse probe). So: stand the player where {@code placePortal}'s
         * ray (eye height 1.62, pitch 45°, facing -Z) hits the TOP FACE of the block under the
         * wanted origin — the portal then stands on that block, 3×3, normal +Z, centre exactly
         * {@code origin} — then level the look and complete the pair with the targeted command.
         */
        @Override
        void spawnBiWayPair(ResourceKey<Level> fromDim, Vec3 origin, ResourceKey<Level> toDim, Vec3 dest) {
            command(String.format(Locale.ROOT, "execute in %s run tp @a %.2f %.2f %.2f 180 45",
                fromDim.identifier(), origin.x, origin.y - 1.5, origin.z + 1.6));
            context.waitTicks(20);
            command(String.format(Locale.ROOT, "portal make_portal 3 3 %s %.2f %.2f %.2f",
                toDim.identifier(), dest.x, dest.y, dest.z));
            context.waitTicks(10);
            command(String.format(Locale.ROOT, "execute in %s run tp @a %.2f %.2f %.2f 180 0",
                fromDim.identifier(), origin.x, origin.y - 1.5, origin.z + 1.6));
            context.waitTicks(10);
            command("portal complete_bi_way_portal");
            SeamlessPortalsConstants.LOGGER.info(LOG + "requested bi-way pair via chat: {} {} -> {} {}",
                fromDim.identifier(), origin, toDim.identifier(), dest);
        }
    }

    /**
     * Wait for the chunk DATA, not for vanilla's "all sections rendered" flag: with a same-dim
     * portal in view the mod re-meshes the destination region every tick (SameDimRemesh, by
     * design), so {@code LevelRenderer.hasRenderedAllSections()} never settles and the
     * framework's {@code waitForChunksRender} times out on any join AFTER portals exist (measured
     * on the rejoin leg: 96/105 sections rendered, steady, for 60 s). The flag is logged for the
     * record; the legs carry their own waits.
     */
    private static void settleAfterJoin(ClientGameTestContext context, TestServerConnection conn) {
        // Not the framework's waitForChunksDownload either: it reads vanilla's ClientChunkCache
        // storage view centre, which IP's ImmPtlClientChunkMap does not maintain (it keeps its
        // own) — the check happens to pass when the player stands in chunk (0,0) and never
        // otherwise (rejoin at chunk (0,-1): 322 chunks held, check red for 60 s). Wait for the
        // player's own chunk plus a working set instead.
        context.waitFor(mc -> mc.level != null && mc.player != null
            && mc.level.hasChunk(mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4)
            && mc.level.getChunkSource().getLoadedChunksCount() >= 100, 1200);
        for (int i = 0; i < 3; i++) {
            context.waitTicks(20);
            String flags = context.computeOnClient(mc -> "hasRenderedAllSections=" + mc.levelRenderer.hasRenderedAllSections()
                + " loadedChunks=" + mc.level.getChunkSource().getLoadedChunksCount());
            SeamlessPortalsConstants.LOGGER.info(LOG + "post-join settle {}: {}", i, flags);
        }
    }

    // ------------------------------------------------------------------ external connection

    private static void connectExternal(ClientGameTestContext context, String address) {
        context.runOnClient(mc -> {
            ServerData data = new ServerData("dedicated smoke", address, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address), data, false, null);
        });
        try {
            context.waitFor(mc -> mc.level != null && mc.player != null && mc.getConnection() != null, 1200);
        } catch (Throwable t) {
            String state = context.computeOnClient(mc -> "level=" + (mc.level != null) + " player=" + (mc.player != null)
                + " connection=" + (mc.getConnection() != null));
            throw new AssertionError(LOG + "external join FAILED: no level within 60s (" + state + ")", t);
        }
        context.waitTicks(100);   // chunks + the join burst
    }

    private static void disconnectExternal(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            if (mc.level != null) mc.level.disconnect(Component.literal("smoke: rejoin"));
            mc.disconnectWithProgressScreen();
        });
        context.waitFor(mc -> mc.level == null, 600);
        context.setScreen(TitleScreen::new);
        context.waitTicks(40);
    }

    // ------------------------------------------------------------------ helpers

    private static @Nullable Portal clientPortalNear(Minecraft mc, Vec3 origin) {
        if (mc.level == null) return null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Portal p && p.getOriginPos().distanceTo(origin) < 1.0) return p;
        }
        return null;
    }

    private static String describeClient(ClientGameTestContext context) {
        return context.computeOnClient(mc -> mc.player == null ? "(no player)"
            : mc.level.dimension().identifier() + " @ " + mc.player.position() + " yaw " + mc.player.getYRot());
    }

    private static void aimAt(ClientGameTestContext context, double tx, double ty, double tz) {
        context.runOnClient(mc -> {
            if (mc.player == null) return;
            Vec3 eye = mc.player.getEyePosition();
            double dx = tx - eye.x, dy = ty - eye.y, dz = tz - eye.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
            mc.player.setYRot(yaw);
            mc.player.setXRot(pitch);
            mc.player.yRotO = yaw;
            mc.player.xRotO = pitch;
        });
        context.waitTicks(10);
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        try {
            context.waitTicks(10);
            SeamlessPortalsConstants.LOGGER.info(LOG + "screenshot {} -> {}", name, context.takeScreenshot(name));
        } catch (Throwable t) {
            SeamlessPortalsConstants.LOGGER.warn(LOG + "screenshot {} failed: {}", name, t.toString());
        }
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block;
    }
}

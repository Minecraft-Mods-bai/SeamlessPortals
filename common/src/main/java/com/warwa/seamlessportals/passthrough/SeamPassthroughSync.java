package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import com.warwa.seamlessportals.network.ModPayloads;
import com.warwa.seamlessportals.network.PlatformHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.platform_specific.IPConfig;

/**
 * ★ PASSTHROUGH EXTRAS IS SERVER-AUTHORITATIVE OVER A CONNECTION (multiplayer, 2026-09-27).
 *
 * <p>The master switch ({@code IPConfig.passthroughExtras}) used to be read from each side's OWN
 * config file. In singleplayer that is one object, so it never mattered; on a dedicated server a
 * client whose file disagrees with the server's would cut seam collision shapes the server keeps
 * whole (or the reverse) — a permanent rubber-band at every seam cell, with no message anywhere.
 *
 * <p>Rule: while connected, the client uses the value the server sent; the server always uses its
 * own config. The server pushes {@link ModPayloads.SeamPassthroughConfigPayload} on join (before
 * the occupancy burst, so the gate is set before the first cell arrives) and again whenever its
 * config changes at runtime ({@code IPConfig.onConfigChanged} → {@link #broadcastFromServer()} —
 * the singleplayer "toggling takes effect within a tick" contract is kept through the integrated
 * server's own connection). The client forgets the value when a new connection starts, so a
 * server that never sends it (an older build) leaves the client on its local file, as before.
 *
 * <p>Server-safe by construction: no client type anywhere in this class.
 */
public final class SeamPassthroughSync {

    private SeamPassthroughSync() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The value the current server sent; null = not (yet) synced → the local config applies. */
    private static volatile @Nullable Boolean serverValue = null;

    /** The effective master switch for {@code level}'s side. */
    public static boolean enabled(@Nullable Level level) {
        if (level != null && level.isClientSide()) {
            return enabledForClient();
        }
        return IPConfig.getConfig().passthroughExtras;
    }

    /** The effective master switch on the CLIENT side (server-sent value first, then local). */
    public static boolean enabledForClient() {
        Boolean synced = serverValue;
        return synced != null ? synced : IPConfig.getConfig().passthroughExtras;
    }

    public static @Nullable Boolean serverValue() {
        return serverValue;
    }

    /** Client receiver. */
    public static void applyServerValue(boolean value) {
        Boolean previous = serverValue;
        serverValue = value;
        if (previous == null || previous != value) {
            LOGGER.info("[RS-SEAM-SYNC] server says passthroughExtras={} (local config {})",
                value, IPConfig.getConfig().passthroughExtras);
        }
    }

    /** Client: a new connection is starting — forget the previous server's value. */
    public static void reset() {
        serverValue = null;
    }

    /** Server: one player's copy (join, or the world-change re-send). */
    public static void sendTo(ServerPlayer player) {
        try {
            PlatformHelper.getInstance().sendToClient(player,
                new ModPayloads.SeamPassthroughConfigPayload(IPConfig.getConfig().passthroughExtras));
        } catch (Throwable t) {
            // Display/consistency concern; never break a join.
            LOGGER.warn("[RS-SEAM-SYNC] could not send passthroughExtras to {}: {}",
                player.getGameProfile().name(), t.toString());
        }
    }

    /** Server (incl. the integrated one): the config changed — push the new value to everyone. */
    public static void broadcastFromServer() {
        MinecraftServer server;
        try {
            server = qouteall.q_misc_util.MiscHelper.getServer();
        } catch (Throwable t) {
            return;
        }
        if (server == null) {
            return;
        }
        server.execute(() -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sendTo(player);
            }
        });
    }
}

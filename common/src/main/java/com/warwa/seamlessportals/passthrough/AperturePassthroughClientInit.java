package com.warwa.seamlessportals.passthrough;

import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * The CLIENT-ONLY registrations of {@link AperturePassthroughInit} — dedicated-server dist split
 * (2026-09-27). {@code AperturePassthroughInit.init()} runs at server/common init on both loaders
 * (a dedicated server included), and the lambdas it used to register here were compiled into
 * synthetic methods of THAT class: the ride sampler assigns {@code mc.player} (a
 * {@code LocalPlayer}) to an {@code Entity}, an assignability proof the verifier can only finish
 * by loading {@code LocalPlayer}, so the whole init class failed to LINK on a dedicated server
 * (NeoForge strips nothing; Fabric strips only annotated members, and a lambda cannot be
 * annotated). Everything that touches {@code Minecraft}/{@code ClientLevel}/{@code LocalPlayer}
 * now lives here; the common init resolves this class only on a physical client.
 *
 * <p>Registration ORDER is preserved exactly: the common init calls {@link #init()} at the slot
 * the client registrations used to occupy (after the dispose signal, before the server-tick
 * drain), and the four {@code POST_CLIENT_TICK_EVENT} listeners keep their relative order
 * (SameDimRemesh drain, seam-clip flush, ride sampler, client-view probe).
 */
@Environment(EnvType.CLIENT)
public final class AperturePassthroughClientInit {

    private AperturePassthroughClientInit() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialised = false;

    static void init() {
        if (initialised) {
            return;
        }
        initialised = true;

        // SAME-DIMENSION PORTAL TERRAIN FRESHNESS (com.warwa.seamlessportals.render.SameDimRemesh).
        //
        // A SEPARATE registration on the client tick signal, deliberately NOT folded into
        // AperturePassthroughInit.onPortalTick: that handler returns early on an unchanged geometry
        // fingerprint, which for a stable portal is every tick after the first. SameDimRemesh needs
        // the portal EVERY tick — its destination-region list is rebuilt per tick so a removed
        // portal stops qualifying immediately.
        Portal.CLIENT_PORTAL_TICK_SIGNAL.register(
            com.warwa.seamlessportals.render.SameDimRemesh::onClientPortalTick);
        // POST_CLIENT_TICK fires on the main thread after the world tick, never mid-extract or
        // mid-render — the same ordering guarantee SecondaryWorldRenderCore's own per-tick pump
        // relies on, and the reason the drain can append to the main LevelRenderState safely.
        IPGlobal.POST_CLIENT_TICK_EVENT.register(
            () -> com.warwa.seamlessportals.render.SameDimRemesh.onEndClientTick(
                Minecraft.getInstance()));
        // SEAM CLIP recompile flush — same ordering guarantee, SEPARATE accounting from
        // SameDimRemesh by design (SEAM_CLIP_DESIGN.md §2: sharing its COMPILED set would have
        // masked the RS-DELIVERY arm-3 verdict).
        IPGlobal.POST_CLIENT_TICK_EVENT.register(
            () -> com.warwa.seamlessportals.render.SeamClipRenderer.onEndClientTick(
                Minecraft.getInstance()));
        // (e) DEFECT-B ride sampler. Same ordering guarantee. Costs one boolean test per tick
        // outside a crossing window (SeamRideProbe.windowOpen), and the window is opened only by
        // an actual client-side dimension change and closed after SeamRideProbe.WINDOW_TICKS.
        IPGlobal.POST_CLIENT_TICK_EVENT.register(() -> {
            if (!SeamRideProbe.windowOpen()) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            Entity player = mc == null ? null : mc.player;
            Entity vehicle = player == null ? null : player.getVehicle();
            int watched = SeamRideProbe.watchedVehicleId();
            SeamRideProbe.onEndClientTick(
                player, vehicle,
                mc == null || mc.level == null
                    ? "null" : mc.level.dimension().identifier().toString(),
                mc != null && mc.level != null && watched >= 0
                    && mc.level.getEntity(watched) != null);
        });

        // RS-XTALK live round 3 — CLIENT-VIEW probe (1 Hz, -PseamSignalProbe only): what the
        // CLIENT holds per seam cell — chunk state with POWERED, occupancy mask, side-table
        // secondary with ITS powered bit. The dynamic seam draw renders exactly these
        // (SeamClipRenderer reads live client state per frame), so diffing this line against the
        // server-side "pair truth" line attributes a dark-looking seam rail to the client sync,
        // the side-table fragment, or the render, in one glance. Log-only; touches nothing.
        IPGlobal.POST_CLIENT_TICK_EVENT.register(AperturePassthroughClientInit::clientSeamViewProbe);
    }

    private static long clientSeamViewProbeLast = 0;

    /** RS-XTALK round 3 — the 1 Hz client-view line; see the registration comment. Log-only. */
    private static void clientSeamViewProbe() {
        if (!AperturePassthroughLever.SEAM_SIGNAL_PROBE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - clientSeamViewProbeLast < 1_000_000_000L) {
            return;
        }
        clientSeamViewProbeLast = now;
        var cells = ((SeamIndexHolder) mc.level).seamlessportals$seamCells();
        if (cells.isEmpty()) {
            return;
        }
        var powered = BlockStateProperties.POWERED;
        for (var e : cells.long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.of(e.getLongKey());
            var st = mc.level.getBlockState(pos);
            if (st.isAir()) {
                continue;
            }
            var sec = SeamOccupancy.secondaryOf(mc.level, pos);
            LOGGER.info("[RS-SIGNAL] client view: {} {} powered={} mask={} secondary={}{}",
                pos, st.getBlock(),
                st.hasProperty(powered) ? st.getValue(powered) : "n/a",
                SeamOccupancy.occupancyOf(mc.level, pos),
                sec != null,
                sec == null ? "" : (" secBlock=" + sec.state().getBlock() + " secPowered="
                    + (sec.state().hasProperty(powered) ? sec.state().getValue(powered) : "n/a")
                    + " secHalf=" + sec.half()));
        }
    }
}

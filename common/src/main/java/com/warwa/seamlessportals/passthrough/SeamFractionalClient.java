package com.warwa.seamlessportals.passthrough;

import com.warwa.seamlessportals.render.SeamCounterpartOutline;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;

/**
 * The CLIENT-ONLY half of {@link SeamFractional} — dedicated-server dist split (2026-09-27).
 *
 * <p>{@code SeamFractional} is reached from common mixins the SERVER weaves ({@code
 * BlockStateBaseFractionalMixin} runs inside {@code Blocks.<clinit>} on a dedicated server), so the
 * class must LINK there. Two of its bodies used to reference client-only classes — the outline
 * rule read {@code Minecraft.getInstance().hitResult} and the counterpart-outline statics, and the
 * two-object placement predicted into a {@code ClientLevel} from {@code ClientWorldLoader} — and a
 * {@code ClientLevel} handed to a {@code Level} parameter is an assignability proof the verifier
 * can only complete by loading {@code ClientLevel}: {@code NoClassDefFoundError} at
 * {@code Blocks.<clinit>}, the exact crash of both loaders' dedicated servers.
 *
 * <p>The rule (migration/NEOFORGE_PARITY.md §3, the dist doctrine): NeoForge strips NOTHING, so a
 * server-loaded class may not carry such a body anywhere. Every client reference now lives here,
 * behind {@code Level}-typed entry points, and the callers reach this class only inside an
 * {@code isClientSide()} branch — an {@code invokestatic} is resolved when EXECUTED, never at link
 * time, so a physical server never loads this class.
 */
@Environment(EnvType.CLIENT)
public final class SeamFractionalClient {

    private SeamFractionalClient() {}

    /** {@link SeamCounterpartOutline#extractingOutline}, read through a client-only frame. */
    public static boolean extractingOutline() {
        return SeamCounterpartOutline.extractingOutline;
    }

    /**
     * ★ ONE OBJECT, ONE OUTLINE — the viewer's targeted half of a two-object seam cell (user round
     * 21/26; the body moved verbatim from {@code SeamFractional.outlineShape}). Which occupant is
     * targeted is the same crosshair-side rule everything else uses ({@code halfFromHit} on the
     * actual hit); the through-window counterpart swap's synthetic cell-centre hit carries the
     * REAL remote hit's side instead. Falls back to {@code fallback} (the primary) when the current
     * hit is not a genuine location for this cell.
     */
    public static byte outlineTargetHalf(
        BlockPos pos, Direction.Axis axis, double planeOffset, byte fallback
    ) {
        HitResult curHit = Minecraft.getInstance().hitResult;
        if (curHit == SeamCounterpartOutline.nearHit && SeamCounterpartOutline.nearHitHalf != 0) {
            // Round 26: the through-window swap's hit is a synthetic cell CENTRE — a point ON the
            // plane picks a side arbitrarily, which is how targeting a two-object cell's dest half
            // lost its near half. The swap now carries the REAL remote hit's side, mapped through
            // the binding; use it directly.
            return SeamCounterpartOutline.nearHitHalf;
        }
        if (curHit instanceof BlockHitResult bhr
            && bhr.getType() != HitResult.Type.MISS
            && bhr.getBlockPos().equals(pos)) {
            return SeamOccupancy.halfFromHit(bhr.getLocation(), pos, axis, planeOffset);
        }
        return fallback;
    }

    /**
     * The client's level for {@code dim}: {@code level} itself when it IS that dimension, else the
     * loader's per-dim world (peekWorld, not PortalWorldManager — the instance every portal-view
     * consumer reads; the manager's store answered NULL cross-dim and the prediction silently
     * died). Returned as a {@code Level} so the caller never names {@code ClientLevel}.
     */
    public static @Nullable Level peekClientLevel(Level level, ResourceKey<Level> dim) {
        return level.dimension().equals(dim) ? level : ClientWorldLoader.peekWorld(dim);
    }
}

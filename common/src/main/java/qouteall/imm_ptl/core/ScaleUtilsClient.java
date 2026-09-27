package qouteall.imm_ptl.core;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.apache.commons.lang3.Validate;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * NF-PARITY dist split (2026-09-27) of {@link ScaleUtils#onClientPlayerTeleported}, in the same
 * family as {@code CollisionHelper → CollisionHelperClient} (migration/NEOFORGE_PARITY.md §3).
 *
 * <p>{@code ScaleUtils} is a SERVER class too — {@code ServerTeleportationManager} and
 * {@code BlockManipulationServer} reach it on the first entity teleport / reach check — and the
 * old body assigned {@code client.player} (a {@code LocalPlayer}) to the {@code Entity} parameter
 * of {@code doScalingForEntity}: an assignability proof the verifier can only finish by loading
 * {@code LocalPlayer}, i.e. the whole class fails to LINK on a NeoForge dedicated server (which
 * strips nothing; Fabric hid this by stripping the annotated method). Body moved verbatim.
 */
@Environment(EnvType.CLIENT)
public class ScaleUtilsClient {

    public static void onClientPlayerTeleported(Portal portal) {
        if (portal.hasScaling() && portal.isTeleportChangesScale()) {
            Minecraft client = Minecraft.getInstance();

            LocalPlayer player = client.player;

            Validate.notNull(player, "Player is null");

            ScaleUtils.doScalingForEntity(player, portal);

            IECamera camera = (IECamera) client.gameRenderer.mainCamera();
            camera.ip_setCameraY(
                ((float) (camera.ip_getCameraY() * portal.getScaling())),
                ((float) (camera.ip_getLastCameraY() * portal.getScaling()))
            );
        }
    }
}

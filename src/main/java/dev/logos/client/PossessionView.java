package dev.logos.client;

import org.jspecify.annotations.Nullable;

import dev.logos.runtime.Possession;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.phys.Vec3;

/**
 * How possession looks (see {@link Possession}). The player looks out of the creature's eyes, so
 * the creature is not drawn for them while the camera really is there (not in third person, nor
 * from a free camera mod's detached view). Their body, a mannequin, is drawn as the player
 * themself standing in its place: the player's own render state, moved there, so that whatever
 * dresses the player (a player-model mod such as Yes Steve Model, armour, capes) dresses the body
 * too. A body whose owner this client cannot see stays a plain mannequin with their skin.
 */
public final class PossessionView {
    private PossessionView() {}

    /** Whether {@code e} is the creature the local player is looking out of right now. */
    public static boolean lookingOutOf(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        return mc.player != null && camera.entity() == mc.player && !camera.isDetached() && Possession.possessor(e) == mc.player;
    }

    /** The possessing player whose body {@code e} is, if this client has them. */
    public static @Nullable AbstractClientPlayer owner(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        if (!(e instanceof Mannequin m) || mc.level == null) return null;
        var p = mc.level.getPlayerByUUID(m.getProfile().partialProfile().id());
        return p instanceof AbstractClientPlayer a && Possession.possessing(a) ? a : null;
    }

    /** Moves {@code state}, extracted from the body's owner, to where the body stands, standing as it stands. */
    public static void standIn(EntityRenderState state, Mannequin body, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 at = body.getPosition(partialTick);
        state.x = at.x;
        state.y = at.y;
        state.z = at.z;
        state.distanceToCameraSq = mc.gameRenderer.getMainCamera().position().distanceToSqr(at);
        state.lightCoords = mc.getEntityRenderDispatcher().getPackedLightCoords(body, partialTick);
        // Computed where the owner rides: no shadow, leash or seat offset belongs to the body.
        state.shadowPieces.clear();
        state.leashStates = null;
        state.passengerOffset = null;
        state.nameTag = null;
        if (state instanceof LivingEntityRenderState l) {
            float head = Mth.rotLerp(partialTick, body.yHeadRotO, body.yHeadRot);
            l.bodyRot = Mth.rotLerp(partialTick, body.yBodyRotO, body.yBodyRot);
            l.yRot = Mth.wrapDegrees(head - l.bodyRot);
            l.xRot = body.getXRot(partialTick);
            l.pose = Pose.STANDING;
            l.walkAnimationSpeed = 0;
            l.isInWater = body.isInWater();
        }
        if (state instanceof HumanoidRenderState h) {
            h.isPassenger = false;
            h.isCrouching = false;
            h.isUsingItem = false;
            h.isFallFlying = false;
            h.isVisuallySwimming = false;
            h.swimAmount = 0;
        }
    }
}

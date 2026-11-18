package dev.logos.client;

import javax.annotation.Nullable;

import dev.logos.runtime.Possession;
import dev.logos.runtime.PossessionBody;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;

/**
 * How possession looks (see {@link Possession}). The player looks out of the creature's eyes, so
 * the creature is not drawn for them while the camera really is there (not in third person, nor
 * from a free camera mod's detached view). Their body ({@link PossessionBody}) is drawn by
 * {@link PossessionBodyRenderer} with its owner's skin.
 */
public final class PossessionView {
    private PossessionView() {}

    /** Whether {@code e} is the creature the local player is looking out of right now. */
    public static boolean lookingOutOf(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        return mc.player != null && camera.getEntity() == mc.player && !camera.isDetached() && Possession.possessor(e) == mc.player;
    }

    /** The possessing player whose body {@code e} is, if this client has them. */
    public static @Nullable AbstractClientPlayer owner(Entity e) {
        Minecraft mc = Minecraft.getInstance();
        if (!(e instanceof PossessionBody body) || body.owner() == null || mc.level == null) return null;
        var p = mc.level.getPlayerByUUID(body.owner());
        return p instanceof AbstractClientPlayer a && Possession.possessing(a) ? a : null;
    }
}

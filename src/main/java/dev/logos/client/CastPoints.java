package dev.logos.client;

import org.jspecify.annotations.Nullable;

import dev.logos.runtime.AlchemicalColossus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Where a caster's spells visibly leave from: a colossus's muzzle bone, the head of a wand as it was
 * drawn this frame ({@link WandTips}), or failing those a point estimated from the body. Only the
 * picture starts here; spells still aim and hit from the caster's eyes, so a beam or ray drawn from
 * the cast point meets the line it really travels at whatever it strikes.
 */
public final class CastPoints {
    private CastPoints() {}

    /** The cast point as last drawn, or null if nothing marks one on this caster just now. */
    public static @Nullable Vec3 drawn(Entity caster) {
        if (caster instanceof AlchemicalColossus colossus) return colossus.drawnBone("attachment_muzzle_right");
        return WandTips.sample(caster.getId());
    }

    /** The cast point, drawn or estimated, at partial tick {@code pt}. */
    public static Vec3 of(Entity caster, float pt) {
        Vec3 drawn = drawn(caster);
        return drawn != null ? drawn : guess(caster, pt);
    }

    /**
     * Estimated: a held wand's hand (a little forward, to the right and down, a touch closer in first
     * person), or just in front of the body for a caster with no hands (a mana lifeform).
     */
    static Vec3 guess(Entity caster, float pt) {
        Vec3 gaze = caster.getViewVector(pt);
        if (!(caster instanceof Player)) {
            Vec3 at = caster.getPosition(pt);
            return at.add(0, caster.getBbHeight() * 0.55, 0).add(gaze.scale(caster.getBbWidth() * 0.5 + 0.25));
        }
        Vec3 eye = caster.getEyePosition(pt);
        Vec3 right = gaze.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        Minecraft mc = Minecraft.getInstance();
        boolean firstPerson = caster == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
        return eye.add(gaze.scale(firstPerson ? 0.6 : 0.4)).add(right.scale(firstPerson ? 0.32 : 0.25)).add(0, firstPerson ? -0.28 : -0.3, 0);
    }
}

package dev.logos.client;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import dev.logos.net.PossessControlPayload;
import dev.logos.runtime.Possession;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;

/**
 * A possessing player's keys and view. Their movement keys need nothing here (the creature is
 * ridden, see {@link Possession}); left click is its blow and the use button its shot, at whatever
 * they aim at, and their own hands neither show nor act. The inventory key opens the creature's
 * inventory (see {@link dev.logos.runtime.PossessionInventory}) instead of their own. Held, the
 * instinct key (V by default) lets the creature's own AI act, at what they aimed at when they pressed
 * it. Looking out of a creeper, spider or enderman, they see as it does, as a spectator would.
 */
final class PossessionInput {
    /** Active only while possessing, so sharing V with the mount mode key is no conflict. */
    private static final IKeyConflictContext POSSESSING = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return Minecraft.getInstance().screen == null && Possession.possessing(Minecraft.getInstance().player);
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return this == other;
        }
    };
    static final KeyMapping INSTINCT = new KeyMapping("key.logos.instinct", POSSESSING, InputConstants.Type.KEYSYM, InputConstants.KEY_V, LogosClient.CATEGORY);
    /** How far a possessing player's aim reaches; the server checks the creature's own reach and range. */
    private static final double AIM_RANGE = 48;
    private static @Nullable Entity viewing;
    private static boolean instinctHeld;

    private PossessionInput() {}

    /** Each client tick, before the game's own keys: the inventory and instinct keys, and the creature's way of seeing, put on and taken off with the possession. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        Entity now = Possession.possessing(mc.player) ? mc.player.getVehicle() : null;
        if (now != null) {
            while (mc.options.keyInventory.consumeClick()) {
                if (mc.screen == null) ClientPacketDistributor.sendToServer(new PossessControlPayload(PossessControlPayload.INVENTORY, -1));
            }
        }
        boolean held = now != null && INSTINCT.isDown();
        if (held != instinctHeld) {
            Entity target = held ? aimed(mc.player) : null;
            ClientPacketDistributor.sendToServer(new PossessControlPayload(held ? PossessControlPayload.INSTINCT : PossessControlPayload.STEER,
                    target == null ? -1 : target.getId()));
            instinctHeld = held;
        }
        if (now == viewing) return;
        mc.gameRenderer.checkEntityPostEffect(now != null ? now : mc.getCameraEntity());
        viewing = now;
    }

    /** Attack and use go to the creature instead of the player's own hands. */
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Player p = Minecraft.getInstance().player;
        if (!Possession.possessing(p)) return;
        event.setSwingHand(false);
        event.setCanceled(true);
        if (event.isAttack() || event.isUseItem()) {
            Entity target = aimed(p);
            ClientPacketDistributor.sendToServer(new PossessControlPayload(event.isUseItem() ? PossessControlPayload.SHOOT : PossessControlPayload.STRIKE,
                    target == null ? -1 : target.getId()));
        }
    }

    static void onRenderHand(RenderHandEvent event) {
        if (Possession.possessing(Minecraft.getInstance().player)) event.setCanceled(true);
    }

    /** What the player aims at from the creature's eyes, up to a block in the way. */
    private static @Nullable Entity aimed(Player p) {
        Entity mob = p.getVehicle();
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(AIM_RANGE));
        HitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        AABB area = new AABB(eye, end).inflate(1);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(p, eye, end, area,
                e -> e != mob && !e.isSpectator() && e.isPickable(), eye.distanceToSqr(end));
        return hit == null ? null : hit.getEntity();
    }
}

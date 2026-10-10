package dev.logos.client;

import dev.logos.runtime.ConstructEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Constructs as a fulcrum for building: right-clicking a construct's face with a block places that
 * block in the world just outside the face, through the ordinary placement path (so survival
 * costs, spawn protection and adventure mode all apply). The block stays when the construct fades.
 */
final class ConstructPlacement {
    private ConstructPlacement() {}

    static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (!event.isUseItem() || player == null || mc.level == null || mc.gameMode == null || !ConstructEntity.any()) return;
        if (!(player.getItemInHand(event.getHand()).getItem() instanceof BlockItem)) return;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1).scale(player.blockInteractionRange()));
        BlockHitResult best = null;
        for (ConstructEntity c : ConstructEntity.near(mc.level, new AABB(eye, end))) {
            BlockHitResult hit = c.geometry().clip(eye, end);
            if (hit != null && (best == null || hit.getLocation().distanceToSqr(eye) < best.getLocation().distanceToSqr(eye))) best = hit;
        }
        if (best == null) return;
        // Something real in front of the construct takes the click as usual.
        HitResult vanilla = mc.hitResult;
        if (vanilla != null && vanilla.getType() != HitResult.Type.MISS
                && vanilla.getLocation().distanceToSqr(eye) <= best.getLocation().distanceToSqr(eye)) return;
        Vec3 out = Vec3.atLowerCornerOf(best.getDirection().getUnitVec3i());
        BlockPos target = BlockPos.containing(best.getLocation().add(out.scale(0.25)));
        BlockHitResult click = new BlockHitResult(best.getLocation(), best.getDirection(), target, false);
        InteractionResult result = mc.gameMode.useItemOn(mc.player, event.getHand(), click);
        event.setCanceled(true);
        event.setSwingHand(result instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT);
    }
}

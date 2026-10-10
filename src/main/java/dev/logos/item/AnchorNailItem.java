package dev.logos.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 锚钉: a consumable for anchors. Right-click a block face to drive it in and name the anchor
 * (one nail is spent once the name is confirmed); right-click the air to see your anchors.
 * The {@code anchor} rune spends a nail from the inventory just the same.
 */
public final class AnchorNailItem extends Item {
    public AnchorNailItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide()) dev.logos.client.ClientHooks.openAnchorName(ctx.getClickedPos(), ctx.getClickedFace());
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) dev.logos.client.ClientHooks.openAnchorList();
        return InteractionResult.SUCCESS;
    }
}

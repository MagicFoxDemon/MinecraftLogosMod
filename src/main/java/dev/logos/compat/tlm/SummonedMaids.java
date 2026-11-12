package dev.logos.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitItems;

import dev.logos.runtime.Familiars;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * A maid summoned as a familiar is a wild maid bound to her summoner for a day. Tamed with a cake (or the owner
 * conversion tool) she would be a maid her owner gives things to and keeps, and the bond would still unmake her when
 * it ends, with all of it. So she cannot be tamed: she answers to the familiar bond, not to the maid mod's ownership.
 */
final class SummonedMaids {
    /** What tames a wild maid in the maid mod; its own tag, looked up by name. */
    private static final TagKey<Item> TAMING = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("touhou_little_maid", "maid_tamed_item"));

    private SummonedMaids() {}

    @SubscribeEvent
    static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof EntityMaid maid) || !Familiars.isFamiliar(maid) || maid.isTame()) return;
        ItemStack held = event.getItemStack();
        if (!held.is(TAMING) && !held.is(InitItems.OWNER_CONVERSION_TOOL.get())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        if (!event.getLevel().isClientSide()) {
            event.getEntity().sendOverlayMessage(Component.literal("召唤来的使魔女仆无法驯服").withStyle(ChatFormatting.YELLOW));
        }
    }
}

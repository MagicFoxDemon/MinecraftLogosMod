package dev.logos.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.SmithingTemplateItem;

/** A philosopher's stone worked into a consumable netherite template for sage wands and staffs. */
public final class SageUpgradeTemplateItem extends SmithingTemplateItem {
    public SageUpgradeTemplateItem(Properties properties) {
        super(Component.translatable("item.logos.sage_upgrade_smithing_template.applies_to").withStyle(ChatFormatting.BLUE),
                Component.translatable("item.logos.sage_upgrade_smithing_template.ingredients").withStyle(ChatFormatting.BLUE),
                Component.translatable("item.logos.sage_upgrade_smithing_template.base_slot_description"),
                Component.translatable("item.logos.sage_upgrade_smithing_template.additions_slot_description"),
                List.of(Identifier.withDefaultNamespace("container/slot/hoe")),
                List.of(Identifier.withDefaultNamespace("container/slot/ingot")), properties);
    }
}

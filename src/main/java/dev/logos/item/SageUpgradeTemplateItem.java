package dev.logos.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.SmithingTemplateItem;

/** A philosopher's stone worked into a consumable netherite template for sage wands and staffs. */
public final class SageUpgradeTemplateItem extends SmithingTemplateItem {
    /** 1.21.1's template makes its own properties; those given are not used. */
    public SageUpgradeTemplateItem(Properties properties) {
        super(Component.translatable("item.logos.sage_upgrade_smithing_template.applies_to").withStyle(ChatFormatting.BLUE),
                Component.translatable("item.logos.sage_upgrade_smithing_template.ingredients").withStyle(ChatFormatting.BLUE),
                Component.translatable("upgrade.logos.sage_upgrade").withStyle(ChatFormatting.GRAY),
                Component.translatable("item.logos.sage_upgrade_smithing_template.base_slot_description"),
                Component.translatable("item.logos.sage_upgrade_smithing_template.additions_slot_description"),
                List.of(ResourceLocation.withDefaultNamespace("item/empty_slot_hoe")),
                List.of(ResourceLocation.withDefaultNamespace("item/empty_slot_ingot")));
    }
}

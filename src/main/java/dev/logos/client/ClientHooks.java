package dev.logos.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Client-only entry points called from common code behind an {@code isClientSide()} check. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openTableEditor(BlockPos table, ItemStack page) {
        SpellEditor.open(table, page);
    }

    public static void openAnchorName(BlockPos pos, net.minecraft.core.Direction face) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new AnchorScreens.Name(pos, face));
    }

    public static void openAnchorList() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new AnchorScreens.List());
    }
}

package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.SmithingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** Real crafting and smithing screens, server results, component sync and survival material consumption. */
@LDLRegisterClient(name = "logos_wand_recipes", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public final class WandRecipesScenario implements UIScenario {
    private static final BlockPos TABLE = new BlockPos(0, 101, 0);
    private static final BlockPos SMITH = TABLE.offset(2, 0, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "recipes", "equipment_tiers").guiScale(3);
    }

    private static void crafting(ServerPlayer p) {
        p.closeContainer();
        p.openMenu(new SimpleMenuProvider((id, inventory, player) -> new CraftingMenu(id, inventory,
                ContainerLevelAccess.create(player.level(), TABLE)), Component.translatable("container.crafting")));
    }

    private static void takeResult(int slot) {
        var mc = Minecraft.getInstance();
        mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set noon")
                .clearArea(TABLE.offset(0, 5, 0), 8)
                .fill(TABLE.offset(-5, -1, -5), TABLE.offset(5, -1, 5), Blocks.STONE.defaultBlockState())
                .teleportPlayer(1, 101, 2, 180, 20)
                .server("craft the consumable sage template", sc -> {
                    sc.player().level().setBlock(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                    sc.player().level().setBlock(SMITH, Blocks.SMITHING_TABLE.defaultBlockState(), 3);
                    sc.player().getInventory().clearContent();
                    crafting(sc.player());
                    var menu = sc.player().containerMenu;
                    menu.getSlot(1).set(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
                    menu.getSlot(2).set(new ItemStack(LogosMod.PHILOSOPHERS_STONE.get()));
                }).awaitScreen(CraftingScreen.class).ticks(5)
                .step("template result and model arrive", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.check("one sage template", mc.player.containerMenu.getSlot(0).getItem().is(LogosMod.SAGE_UPGRADE_TEMPLATE.get())
                            && mc.player.containerMenu.getSlot(0).getItem().getCount() == 1);
                    ctx.check("template model baked", mc.getModelManager().getItemModel(LogosMod.SAGE_UPGRADE_TEMPLATE.getId())
                            .getClass().getSimpleName().equals("CuboidItemModelWrapper"));
                }).screenshot("sage_template_crafting")
                .step("take the crafted template", ctx -> takeResult(0)).ticks(5)
                .step("template crafting pays both materials", ctx -> {
                    var menu = Minecraft.getInstance().player.containerMenu;
                    ctx.check("template on cursor", menu.getCarried().is(LogosMod.SAGE_UPGRADE_TEMPLATE.get()));
                    ctx.check("both materials consumed", !menu.getSlot(1).hasItem() && !menu.getSlot(2).hasItem() && !menu.getSlot(0).hasItem());
                }).server("upgrade a chalk wand with one diamond", sc -> {
                    crafting(sc.player());
                    var menu = sc.player().containerMenu;
                    menu.getSlot(1).set(new ItemStack(LogosMod.CHALK_WAND.get()));
                    menu.getSlot(2).set(new ItemStack(LogosMod.FIRMAMENT_STONE.get()));
                    menu.getSlot(3).set(new ItemStack(Items.DIAMOND));
                }).awaitScreen(CraftingScreen.class).ticks(5)
                .step("firmament result arrives", ctx -> ctx.check("one diamond crafts firmament wand",
                        Minecraft.getInstance().player.containerMenu.getSlot(0).getItem().is(LogosMod.MASTER_WAND.get())))
                .screenshot("firmament_wand_crafting");
        for (boolean staff : new boolean[] {false, true}) {
            s.server("smith the sage " + (staff ? "staff" : "wand"), sc -> {
                        var p = sc.player();
                        p.closeContainer();
                        p.openMenu(new SimpleMenuProvider((id, inventory, player) -> new SmithingMenu(id, inventory,
                                ContainerLevelAccess.create(player.level(), SMITH)), Component.translatable("container.upgrade")));
                        var base = new ItemStack(staff ? LogosMod.FIRMAMENT_STAFF.get() : LogosMod.MASTER_WAND.get());
                        base.set(DataComponents.CUSTOM_NAME, Component.literal("Kept name"));
                        base.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                                SpellPages.inscribed(PageTier.BASIC, "(spell :mana 5 (heal caster 1))", null), new ItemStack(LogosMod.CHANT_CHARM.get()))));
                        if (staff) dev.logos.item.StaffItem.store(base, 321);
                        p.containerMenu.getSlot(0).set(new ItemStack(LogosMod.SAGE_UPGRADE_TEMPLATE.get()));
                        p.containerMenu.getSlot(1).set(base);
                        p.containerMenu.getSlot(2).set(new ItemStack(Items.NETHERITE_INGOT));
                    }).awaitScreen(SmithingScreen.class).ticks(5)
                    .step("sage result and components arrive", ctx -> {
                        var menu = Minecraft.getInstance().player.containerMenu;
                        var result = menu.getSlot(3).getItem();
                        ctx.check("correct sage result", result.is(staff ? LogosMod.SAGE_STAFF.get() : LogosMod.SAGE_WAND.get()));
                        ctx.check("all components retained over the network", result.getComponentsPatch().equals(menu.getSlot(1).getItem().getComponentsPatch()));
                    }).screenshot(staff ? "sage_staff_smithing" : "sage_wand_smithing")
                    .step("take the sage result", ctx -> takeResult(3)).ticks(5)
                    .step("smithing consumes all three inputs", ctx -> {
                        var menu = Minecraft.getInstance().player.containerMenu;
                        ctx.check("sage result on cursor", menu.getCarried().is(staff ? LogosMod.SAGE_STAFF.get() : LogosMod.SAGE_WAND.get()));
                        ctx.check("template, base and ingot spent", !menu.getSlot(0).hasItem() && !menu.getSlot(1).hasItem() && !menu.getSlot(2).hasItem() && !menu.getSlot(3).hasItem());
                    });
        }
        s.step("close smithing", ctx -> Minecraft.getInstance().player.closeContainer());
    }
}

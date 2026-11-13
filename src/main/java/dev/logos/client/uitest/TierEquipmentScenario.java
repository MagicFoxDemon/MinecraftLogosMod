package dev.logos.client.uitest;

import java.util.ArrayList;
import java.util.List;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.WandTips;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.WandItem;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/** The actual registered items: their models, page UI, creative tab, held appearances and cast points. */
abstract class TierEquipmentScenario implements UIScenario {
    protected abstract boolean staffs();

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "equipment_tiers", staffs() ? "staff" : "wand").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        boolean staff = staffs();
        List<WandItem> equipment = new ArrayList<>();
        if (staff) LogosMod.TIER_STAFFS.forEach(h -> equipment.add(h.get()));
        else LogosMod.TIER_WANDS.forEach(h -> equipment.add(h.get()));
        var origin = new BlockPos(0, 100, 0);
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon").runCommand("weather clear")
                .clearArea(origin.offset(0, 6, 0), 8)
                .fill(origin.offset(-6, 0, -6), origin.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 10)
                .server("hand out the real tier items", sc -> {
                    var p = sc.player();
                    p.getInventory().clearContent();
                    var m = PlayerMagic.of(p);
                    m.maxMana = m.mana = 1000;
                    m.trace = false;
                    for (int i = 0; i < equipment.size(); i++) {
                        ItemStack stack = new ItemStack(equipment.get(i));
                        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
                        stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                                SpellPages.inscribed(PageTier.values()[i + 1], "(spell :name \"Tier test\" :mana 50 (mark caster #tier_ui))", null))));
                        p.getInventory().setItem(i, stack);
                    }
                    p.getInventory().setSelectedSlot(0);
                }).ticks(10)
                .step("registered item models and effect attachment points", ctx -> {
                    var mc = Minecraft.getInstance();
                    for (int i = 0; i < equipment.size(); i++) {
                        var item = equipment.get(i);
                        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
                        var model = mc.getModelManager().getItemModel(id);
                        ctx.check(id + " model baked", model.getClass().getSimpleName().equals("CuboidItemModelWrapper"));
                        var tip = WandTips.tip(item);
                        double expectedY = staff ? 20.75 : i == 6 ? 15.64 : 15.5;
                        ctx.check(id + " central tip", Math.abs(tip.x - 8) < .001 && Math.abs(tip.y - expectedY) < .001 && Math.abs(tip.z - 8) < .001);
                    }
                    if (staff) {
                        for (String key : List.of("echo", "transmuted", "chalk", "firmament", "sage", "principle")) {
                            var id = Identifier.fromNamespaceAndPath("logos", "block/staff/" + key + "_orbit");
                            var sprite = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(id);
                            ctx.check(key + " animated orbit", sprite.contents().isAnimated() && sprite.contents().getUniqueFrames().size() == 32);
                        }
                    }
                })
                .step("open the inventory", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .awaitScreen(InventoryScreen.class).ticks(3).screenshot("tier_items_inventory")
                .step("close inventory", ctx -> Minecraft.getInstance().setScreen(null));

        for (int i = 0; i < equipment.size(); i++) {
            final int index = i;
            s.server("select tier " + (i + 2), sc -> {
                sc.player().getInventory().setSelectedSlot(index);
                PlayerMagic.of(sc.player()).mana = 1000;
            }).step("first person", ctx -> {
                var mc = Minecraft.getInstance();
                mc.player.getInventory().setSelectedSlot(index);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.options.hideGui = false;
            }).ticks(5)
                    .step("selected actual registered item", ctx -> ctx.check("tier " + (index + 2), Minecraft.getInstance().player.getMainHandItem().is(equipment.get(index))))
                    .screenshot("tier_" + (i + 2) + "_held")
                    .step("charge", ctx -> {
                        if (staff) Minecraft.getInstance().options.keyAttack.setDown(true);
                        else Minecraft.getInstance().options.keyUse.setDown(true);
                    }).ticks(8)
                    .step("first-person effect point", ctx -> sample(staff))
                    .screenshot("tier_" + (i + 2) + "_charge_first")
                    .step("third person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                    .ticks(3).step("third-person effect point", ctx -> sample(staff))
                    .screenshot("tier_" + (i + 2) + "_charge_third")
                    .step("release", ctx -> {
                        if (staff) Minecraft.getInstance().options.keyAttack.setDown(false);
                        else Minecraft.getInstance().options.keyUse.setDown(false);
                    }).ticks(2);
        }

        s.server("open the twelve-slot item", sc -> {
            ItemStack stack = sc.player().getMainHandItem();
            new PageContainer(stack, 12, true).setItem(11, new ItemStack(LogosMod.CHANT_CHARM.get()));
            HeldItemUIMenuType.openUI(sc.player(), InteractionHand.MAIN_HAND);
        }).ticks(6)
                .step("both rows of item slots are exposed", ctx -> {
                    var mc = Minecraft.getInstance();
                    ctx.check("page UI opened", mc.screen != null);
                    ctx.check("twelve item slots plus player inventory", mc.player.containerMenu.slots.size() >= 48);
                }).screenshot("twelve_page_slots")
                .step("close page slots", ctx -> Minecraft.getInstance().player.closeContainer());

        s.setGameMode(GameType.CREATIVE).ticks(4)
                .step("open creative supplies", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false));
                }).awaitScreen(CreativeModeInventoryScreen.class)
                .step("all fourteen items are available", ctx -> {
                    var tab = LogosMod.TAB.get();
                    LogosMod.TIER_WANDS.forEach(h -> ctx.check(h.getId() + " in creative tab", tab.getDisplayItems().stream().anyMatch(st -> st.is(h.get()))));
                    LogosMod.TIER_STAFFS.forEach(h -> ctx.check(h.getId() + " in creative tab", tab.getDisplayItems().stream().anyMatch(st -> st.is(h.get()))));
                    try {
                        var select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
                        select.setAccessible(true);
                        select.invoke(ctx.screen(), tab);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                }).ticks(3).screenshot("all_tiers_creative_tab")
                .step("restore the view", ctx -> {
                    Minecraft.getInstance().setScreen(null);
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                });
    }

    private static void sample(boolean staff) {
        var p = Minecraft.getInstance().player;
        var tip = WandTips.sample(p.getId());
        if (tip == null || tip.distanceTo(p.getEyePosition()) > (staff ? 2 : 1.6))
            throw new AssertionError("the tier item's effect point was not rendered near its holder: " + tip);
    }
}

package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.item.RuneBookItem.Kind;
import dev.logos.lang.Unlocks;
import dev.logos.runtime.RuneKnowledge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.GameType;

/**
 * Real-client look at the 符文之书: every book's model bakes with its tinted cover and sign, they are shown in the
 * inventory, and a book's tooltip counts what it still has to teach its holder.
 * Run: {@code gradlew runClient -PldTest=logos_rune_books -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_rune_books", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class RuneBookScenario implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .server("forget everything and hand out every book", sc -> {
                    var p = sc.player();
                    for (String key : Unlocks.all()) RuneKnowledge.forget(p, key);
                    p.getInventory().clearContent();
                    int slot = 0;
                    for (Kind k : Kind.values()) p.getInventory().setItem(slot++, new ItemStack(LogosMod.RUNE_BOOKS.get(k).get()));
                })
                .waitUntil("the client has the books and knows nothing yet", ctx -> {
                    var p = Minecraft.getInstance().player;
                    return p != null && p.getInventory().getItem(13).getItem() == LogosMod.RUNE_BOOKS.get(Kind.ENLIGHTENMENT).get()
                            && !RuneKnowledge.learned(p, Unlocks.rune("freeze"));
                })
                .step("every book's model bakes and its tooltip counts what is left", ctx -> {
                    var mc = Minecraft.getInstance();
                    for (Kind k : Kind.values()) {
                        Item item = LogosMod.RUNE_BOOKS.get(k).get();
                        var id = BuiltInRegistries.ITEM.getKey(item);
                        String model = mc.getModelManager().getItemModel(id).getClass().getSimpleName();
                        ctx.check(id + " model baked (" + model + ")", !model.toLowerCase().contains("missing"));
                    }
                    var change = new ItemStack(LogosMod.RUNE_BOOKS.get(Kind.CHANGE).get());
                    int all = Kind.CHANGE.pool().size();
                    String expected = Component.translatable("logos.rune_book.left", all - 1, all).getString();
                    var lines = change.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
                    ctx.check("the book of change counts " + expected, lines.stream().anyMatch(l -> l.getString().equals(expected)));
                })
                .step("open the inventory", ctx -> Minecraft.getInstance().setScreen(new InventoryScreen(Minecraft.getInstance().player)))
                .awaitScreen(InventoryScreen.class).ticks(3).screenshot("rune_books_inventory")
                .step("close the inventory", ctx -> Minecraft.getInstance().setScreen(null))
                .server("put the books away", sc -> sc.player().getInventory().clearContent())
                .setGameMode(GameType.CREATIVE);
    }
}

package dev.logos.client.uitest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.alchemy.BuffPotions;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * A resource pack that replaces vanilla's potion definition (here with an apple) changes vanilla potions
 * and leaves Logos potions their bottles: neither of us overrides the other.
 * Run: {@code gradlew runClient -PldTest=logos_potion_pack -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_potion_pack", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PotionPackScenario implements UIScenario {
    private static final String PACK = "logos_test_apple_potion";
    private static CompletableFuture<Void> reload = CompletableFuture.completedFuture(null);

    @Override
    public void configure(ScenarioOptions o) {
        // Reloading resources takes a while with every mod's models.
        o.tags("logos", "alchemy", "items").defaultTimeoutMs(180_000).scenarioTimeoutMs(420_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.step("turn the pack on", ctx -> {
                    writePack(ctx.mc());
                    var packs = ctx.mc().getResourcePackRepository();
                    packs.reload();
                    ctx.check("the pack was found", packs.addPack("file/" + PACK));
                    reload = ctx.mc().reloadResourcePacks();
                })
                .waitUntil("the pack is loaded", ctx -> reload.isDone())
                .step("vanilla potions follow the pack", ctx -> {
                    ItemStack vanilla = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
                    ctx.check("a vanilla potion is the pack's apple", PotionLookScenario.textures(vanilla).equals(Set.of("minecraft:item/apple")),
                            "minecraft:item/apple", PotionLookScenario.textures(vanilla));
                })
                .step("Logos potions keep their bottles", ctx -> {
                    ItemStack mana = PotionContents.createItemStack(Items.POTION, BuffPotions.MANA_FLOW_POTION);
                    ctx.check("流魔 keeps its flask", PotionLookScenario.textures(mana).contains(PotionLookScenario.FLASK),
                            PotionLookScenario.FLASK, PotionLookScenario.textures(mana));
                    ItemStack fire = PotionContents.createItemStack(Items.POTION, BuffPotions.ElementBuff.FIRE.potion);
                    ctx.check("an element potion keeps its bottle", PotionLookScenario.textures(fire).contains(PotionLookScenario.DIAMOND),
                            PotionLookScenario.DIAMOND, PotionLookScenario.textures(fire));
                })
                .step("turn the pack off", ctx -> {
                    ctx.mc().getResourcePackRepository().removePack("file/" + PACK);
                    reload = ctx.mc().reloadResourcePacks();
                })
                .waitUntil("the pack is gone", ctx -> reload.isDone())
                .step("vanilla potions are back", ctx -> {
                    ItemStack vanilla = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
                    ctx.check("a vanilla potion is a bottle again", PotionLookScenario.textures(vanilla).contains(PotionLookScenario.BOTTLE));
                });
    }

    private static void writePack(Minecraft mc) {
        Path root = mc.getResourcePackDirectory().resolve(PACK);
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        try {
            Path definition = root.resolve("assets/minecraft/items/potion.json");
            Files.createDirectories(definition.getParent());
            Files.writeString(root.resolve("pack.mcmeta"),
                    "{\"pack\":{\"description\":\"Logos test pack\",\"min_format\":" + format + ",\"max_format\":" + format + "}}");
            Files.writeString(definition, "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"minecraft:item/apple\"}}");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}

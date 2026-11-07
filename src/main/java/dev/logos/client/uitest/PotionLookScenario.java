package dev.logos.client.uitest;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.TestContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import com.mojang.serialization.JsonOps;

import dev.logos.LogosMod;
import dev.logos.alchemy.BuffPotions;
import dev.logos.mixin.ItemStackRenderStateAccessor;
import dev.logos.mixin.LayerRenderStateAccessor;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * A Logos potion beside a vanilla one in first person, in third person and in the inventory, then which textures
 * each resolves to however the stack came to exist: brewed, from an old save, by command, from the creative tab.
 * Logos draws its bottles over vanilla's definition rather than replacing the file, so the file stays vanilla's.
 * Run: {@code gradlew runClient -PldTest=logos_potion_look -PldTestHeadless}.
 */
@LDLRegisterClient(name = "logos_potion_look", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PotionLookScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    static final String FLASK = "logos:item/potion/mana_crystal";
    static final String DIAMOND = "logos:item/potion/attack_diamond";
    static final String BOTTLE = "minecraft:item/potion";

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "alchemy", "items").guiScale(4);
    }

    @Override
    public void define(ScenarioBuilder s) {
        // A lit room with no sky and the clock stopped: nothing moves, so shots from two builds can be compared pixel by pixel.
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("gamerule advance_time false")
                .runCommand("time set noon")
                .runCommand("weather clear")
                .fill(ORIGIN.offset(-7, -1, -7), ORIGIN.offset(7, 6, 7), Blocks.GLOWSTONE.defaultBlockState())
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 5, 6), Blocks.AIR.defaultBlockState())
                .teleportPlayer(ORIGIN.getX() + 0.5, ORIGIN.getY(), ORIGIN.getZ() + 0.5, 0, 0)
                .step("show the HUD (and with it the hand)", ctx -> ctx.mc().options.hideGui = false)
                .ticks(10);
        held(s, "mana", () -> PotionContents.createItemStack(Items.POTION, BuffPotions.MANA_FLOW_POTION));
        held(s, "fire", () -> PotionContents.createItemStack(Items.POTION, BuffPotions.ElementBuff.FIRE.potion));
        held(s, "vanilla", () -> PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
        s.server("fill the inventory", sc -> {
                    var inv = sc.player().getInventory();
                    inv.clearContent();
                    int slot = 9;
                    for (var potion : List.of(BuffPotions.MANA_FLOW_POTION, BuffPotions.STRONG_MANA_FLOW_POTION, BuffPotions.GREATER_MANA_FLOW_POTION))
                        inv.setItem(slot++, PotionContents.createItemStack(Items.POTION, potion));
                    for (var potion : List.of(Potions.SWIFTNESS, Potions.WATER, Potions.POISON))
                        inv.setItem(slot++, PotionContents.createItemStack(Items.POTION, potion));
                    inv.setItem(slot++, new ItemStack(Items.POTION));
                    slot = 18;
                    for (BuffPotions.ElementBuff e : BuffPotions.ElementBuff.values()) {
                        inv.setItem(slot, PotionContents.createItemStack(Items.POTION, e.potion));
                        inv.setItem(slot + 9, PotionContents.createItemStack(Items.POTION, e.strong));
                        slot++;
                    }
                    inv.setItem(0, PotionContents.createItemStack(Items.SPLASH_POTION, BuffPotions.ElementBuff.FIRE.potion));
                    inv.setItem(1, PotionContents.createItemStack(Items.LINGERING_POTION, BuffPotions.MANA_FLOW_POTION));
                    inv.setItem(2, PotionContents.createItemStack(Items.SPLASH_POTION, Potions.SWIFTNESS));
                })
                .ticks(5)
                .step("open the inventory", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setScreen(new InventoryScreen(mc.player));
                })
                .awaitScreen(InventoryScreen.class)
                .ticks(5)
                .screenshot("potion_inventory")
                .step("close", ctx -> {
                    ctx.mc().setScreen(null);
                    ctx.mc().options.hideGui = true;
                })
                .runCommand("gamerule advance_time true");

        s.step("the definition file is vanilla's alone", ctx -> {
            var sources = ctx.mc().getResourceManager().getResourceStack(Identifier.withDefaultNamespace("items/potion.json"));
            ctx.check("only vanilla defines minecraft:potion", sources.size() == 1 && sources.getFirst().sourcePackId().equals("vanilla"),
                    "vanilla", sources.stream().map(r -> r.sourcePackId()).toList());
        });
        s.step("created in code", ctx -> {
            look(ctx, "flask", PotionContents.createItemStack(Items.POTION, BuffPotions.GREATER_MANA_FLOW_POTION), FLASK);
            for (BuffPotions.ElementBuff e : BuffPotions.ElementBuff.values()) {
                look(ctx, e + " level I", PotionContents.createItemStack(Items.POTION, e.potion), DIAMOND);
                look(ctx, e + " level II", PotionContents.createItemStack(Items.POTION, e.strong), DIAMOND);
            }
        });
        s.step("alchemy's output", ctx -> {
            ItemStack dissolved = new ItemStack(Items.POTION);
            dissolved.set(DataComponents.POTION_CONTENTS, new PotionContents(BuffPotions.ElementBuff.LIFE.potion));
            look(ctx, "dissolved powder", dissolved, DIAMOND);
        });
        s.step("brewed", ctx -> {
            var brewing = ctx.level().potionBrewing();
            look(ctx, "from raw mash", brewing.mix(new ItemStack(LogosMod.MANA_SHARD.get()), new ItemStack(LogosMod.RAW_MASH.get())), FLASK);
            look(ctx, "from vital mash", brewing.mix(new ItemStack(BuffPotions.ElementBuff.VOID.ingredient.get()), new ItemStack(LogosMod.VITAL_MASH.get())), DIAMOND);
        });
        s.step("from an old save, which has only the id", ctx -> {
            var ops = ctx.level().registryAccess().createSerializationContext(JsonOps.INSTANCE);
            var json = JsonParser.parseString("{\"id\":\"minecraft:potion\",\"count\":1,\"components\":{\"minecraft:potion_contents\":\"logos:arcane_power\"}}");
            look(ctx, "saved stack", ItemStack.CODEC.parse(ops, json).getOrThrow(), DIAMOND);
        });
        s.step("from the creative tab", ctx -> {
            CreativeModeTabs.tryRebuildTabContents(ctx.player().connection.enabledFeatures(), true, ctx.level().registryAccess());
            var bottles = LogosMod.POTIONS_TAB.get().getDisplayItems().stream().filter(st -> st.is(Items.POTION)).toList();
            ctx.check("the tab holds all 17 bottles", bottles.size() == 17, 17, bottles.size());
            for (ItemStack bottle : bottles) ctx.check(bottle + " keeps a Logos bottle", textures(bottle).stream().anyMatch(t -> t.startsWith("logos:")));
        });
        s.server("clear", sc -> sc.player().getInventory().clearContent())
                .runCommand("give @a minecraft:potion[potion_contents={potion:\"logos:frost_power\"}]")
                .ticks(3)
                .step("given by command", ctx -> look(ctx, "/give", ctx.player().getInventory().getItem(0), DIAMOND));
        s.step("everything else keeps its own look", ctx -> {
            look(ctx, "vanilla potion", PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS), BOTTLE);
            look(ctx, "water", PotionContents.createItemStack(Items.POTION, Potions.WATER), BOTTLE);
            look(ctx, "no contents", new ItemStack(Items.POTION), BOTTLE);
            // The definition matches whole contents, so a Logos potion given its own colour has always been a plain bottle.
            ItemStack coloured = new ItemStack(Items.POTION);
            coloured.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.<Holder<Potion>>of(BuffPotions.MANA_FLOW_POTION), Optional.of(0xFF0000), List.of(), Optional.empty()));
            look(ctx, "a Logos potion with its own colour", coloured, BOTTLE);
            // Only the drunk bottles have a look of their own; thrown ones have always been vanilla's.
            ctx.check("a Logos splash potion is vanilla's", textures(PotionContents.createItemStack(Items.SPLASH_POTION, BuffPotions.ElementBuff.FIRE.potion))
                    .contains("minecraft:item/splash_potion"));
            ctx.check("a Logos lingering potion is vanilla's", textures(PotionContents.createItemStack(Items.LINGERING_POTION, BuffPotions.MANA_FLOW_POTION))
                    .contains("minecraft:item/lingering_potion"));
        });
    }

    /** One held potion: first person, then third, then back. */
    private static void held(ScenarioBuilder s, String name, Supplier<ItemStack> stack) {
        s.server("hold " + name, sc -> sc.player().setItemInHand(InteractionHand.MAIN_HAND, stack.get()))
                .ticks(15)
                .screenshot("potion_hand_" + name)
                .step("third person", ctx -> ctx.mc().options.setCameraType(CameraType.THIRD_PERSON_FRONT))
                .ticks(3)
                .screenshot("potion_third_" + name)
                .step("first person", ctx -> ctx.mc().options.setCameraType(CameraType.FIRST_PERSON))
                .ticks(3);
    }

    /** {@code stack} draws the textures named {@code base} and {@code base_overlay}. */
    private static void look(TestContext ctx, String what, ItemStack stack, String base) {
        ctx.check(what + " looks right", textures(stack).equals(Set.of(base, base + "_overlay")), base, textures(stack));
    }

    /** The textures {@code stack} resolves to, as anything that draws it would resolve it. */
    static Set<String> textures(ItemStack stack) {
        var mc = Minecraft.getInstance();
        var state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, null, 0);
        var access = (ItemStackRenderStateAccessor) state;
        Set<String> names = new TreeSet<>();
        for (int i = 0; i < access.logos$activeLayerCount(); i++) {
            for (var quad : ((LayerRenderStateAccessor) access.logos$layers()[i]).logos$quads())
                names.add(quad.materialInfo().sprite().contents().name().toString());
        }
        return names;
    }
}

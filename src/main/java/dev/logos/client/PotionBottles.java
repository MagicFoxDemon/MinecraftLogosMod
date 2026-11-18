package dev.logos.client;

import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.logos.LogosMod;
import dev.logos.alchemy.BuffPotions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * Logos potions draw the bottles of {@code assets/logos/items/potion.json}; every other {@code minecraft:potion} draws
 * whatever the game's model says, a resource pack's or another mod's. Wrapped around the baked model rather than
 * replacing its file, and decided by the stack's contents, so however a stack came to exist it looks right. On 1.21.1
 * the definition's cases are read here (potion id → bottle model); the liquid takes the potion's colour from the
 * vanilla potion tint.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class PotionBottles extends BakedModelWrapper<BakedModel> {
    private static final ResourceLocation POTION = ResourceLocation.withDefaultNamespace("potion");
    private static final ResourceLocation LOGOS_POTION = ResourceLocation.fromNamespaceAndPath(LogosMod.MODID, "items/potion.json");

    /** Potion id → the bottle it is drawn as, from the definition's cases. */
    private static Map<ResourceLocation, ModelResourceLocation> cases = Map.of();

    private final Map<ResourceLocation, BakedModel> bottles;
    private final ItemOverrides overrides;

    private PotionBottles(BakedModel others, Map<ResourceLocation, BakedModel> bottles) {
        super(others);
        this.bottles = bottles;
        this.overrides = new ItemOverrides() {
            @Override
            public @Nullable BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
                BakedModel bottle = bottle(stack);
                return bottle != null ? bottle : originalModel.getOverrides().resolve(model, stack, level, entity, seed);
            }
        };
    }

    private @Nullable BakedModel bottle(ItemStack stack) {
        if (!BuffPotions.isLogosPotion(stack)) return null;
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion()
                .flatMap(Holder::unwrapKey).map(k -> bottles.get(k.location())).orElse(null);
    }

    @Override
    public ItemOverrides getOverrides() {
        return overrides;
    }

    @SubscribeEvent
    static void register(ModelEvent.RegisterAdditional event) {
        cases = read(Minecraft.getInstance().getResourceManager());
        new HashSet<>(cases.values()).forEach(event::register);
    }

    @SubscribeEvent
    static void wrap(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        ModelResourceLocation potion = ModelResourceLocation.inventory(POTION);
        BakedModel others = models.get(potion);
        if (others == null) return;
        Map<ResourceLocation, BakedModel> bottles = new HashMap<>();
        cases.forEach((id, model) -> {
            BakedModel baked = models.get(model);
            if (baked != null) bottles.put(id, baked);
        });
        if (!bottles.isEmpty()) models.put(potion, new PotionBottles(others, bottles));
    }

    /** The {@code minecraft:select} cases on {@code minecraft:potion_contents}: each listed potion → its case's model. */
    private static Map<ResourceLocation, ModelResourceLocation> read(ResourceManager manager) {
        Map<ResourceLocation, ModelResourceLocation> out = new HashMap<>();
        var resource = manager.getResource(LOGOS_POTION);
        if (resource.isEmpty()) return out;
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject model = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("model");
            for (JsonElement c : model.getAsJsonArray("cases")) {
                JsonObject k = c.getAsJsonObject();
                var bottle = ModelResourceLocation.standalone(ResourceLocation.parse(k.getAsJsonObject("model").get("model").getAsString()));
                for (JsonElement when : k.getAsJsonArray("when")) out.put(ResourceLocation.parse(when.getAsString()), bottle);
            }
        } catch (Exception e) {
            LogosMod.LOGGER.warn("Could not read {}: {}", LOGOS_POTION, e.toString());
        }
        return out;
    }
}

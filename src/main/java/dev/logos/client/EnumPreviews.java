package dev.logos.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Vector2f;
import org.jspecify.annotations.Nullable;

import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.gui.texture.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

import dev.logos.graph.EnumValues.Kind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;

/** Existing game art inside the manuscript. Detached entity previews never join or tick the real world. */
final class EnumPreviews {
    private static final Map<String, LivingEntity> ENTITIES = new LinkedHashMap<>();
    private static final Map<String, IGuiTexture> TEXTURES = new HashMap<>();
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();
    private static @Nullable Level world;

    private EnumPreviews() {}

    static void clear() { ENTITIES.clear(); TEXTURES.clear(); FAILED.clear(); world = null; }

    static GuiTexture icon(Kind kind, String id) {
        return icon(kind, id, true);
    }

    /** The canvas draws into a cached layer; ordinary item icons also work in that layer. */
    static GuiTexture cardIcon(Kind kind, String id) { return icon(kind, id, false); }

    private static GuiTexture icon(Kind kind, String id, boolean model) {
        return (context, x, y, w, h) -> {
            if (model && !context.isInsideScissor(x, y, w, h)) return;
            if (model && kind == Kind.CREATURE && entity(context, id, x, y, w, h)) return;
            IGuiTexture texture = TEXTURES.computeIfAbsent(kind + ":" + id, key -> texture(kind, id));
            context.drawTexture(texture, x, y, w, h);
        };
    }

    private static IGuiTexture texture(Kind kind, String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) return symbol(kind, id);
        if (kind == Kind.CREATURE) return BuiltInRegistries.ENTITY_TYPE.getOptional(key).flatMap(SpawnEggItem::byId)
                .<IGuiTexture>map(egg -> new ItemStackTexture(new ItemStack(egg))).orElseGet(() -> symbol(kind, id));
        if (kind == Kind.ITEM) return BuiltInRegistries.ITEM.getOptional(key)
                .<IGuiTexture>map(ItemStackTexture::new).orElseGet(() -> symbol(kind, id));
        if (kind == Kind.BLOCK) return BuiltInRegistries.BLOCK.getOptional(key).filter(b -> b.asItem() != Items.AIR)
                .<IGuiTexture>map(b -> new ItemStackTexture(b.asItem())).orElseGet(() -> symbol(kind, id));
        if (kind == Kind.EFFECT && BuiltInRegistries.MOB_EFFECT.containsKey(key))
            return (GuiTexture) (context, x, y, w, h) -> context.blitSprite(RenderPipelines.GUI_TEXTURED,
                    key.withPrefix("mob_effect/"), x, y, w, h, 0xFFFFFFFF);
        if (kind == Kind.PARTICLE) {
            // A particle's own texture list is available even when its provider needs extra parameters.
            var resource = Minecraft.getInstance().getResourceManager().getResource(key.withPrefix("particles/").withSuffix(".json"));
            if (resource.isPresent()) try (var reader = resource.get().openAsReader()) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("textures") && !json.getAsJsonArray("textures").isEmpty()) {
                    IGuiTexture[] frames = json.getAsJsonArray("textures").asList().stream()
                            .map(t -> Identifier.parse(t.getAsString()).withPrefix("textures/particle/").withSuffix(".png"))
                            .map(SpriteTexture::of).toArray(IGuiTexture[]::new);
                    return (GuiTexture) (context, x, y, w, h) -> context.drawTexture(frames[(int) ((System.currentTimeMillis() / 150) % frames.length)], x, y, w, h);
                }
            } catch (Exception ignored) { /* Non-textured emitters use their manuscript emblem. */ }
        }
        if (kind == Kind.DIMENSION) return switch (id) {
            case "minecraft:overworld" -> new ItemStackTexture(Items.GRASS_BLOCK);
            case "minecraft:the_nether" -> new ItemStackTexture(Items.NETHERRACK);
            case "minecraft:the_end" -> new ItemStackTexture(Items.END_STONE);
            default -> symbol(kind, id);
        };
        return symbol(kind, id);
    }

    private static boolean entity(GUIContext context, String id, float x, float y, float w, float h) {
        if (w < 14 || h < 14) return false;
        try {
            LivingEntity entity = previewEntity(id);
            if (entity == null) return false;
            Vector2f a = context.currentPose().transformPosition(new Vector2f(x, y));
            Vector2f b = context.currentPose().transformPosition(new Vector2f(x + w, y + h));
            float scale = Math.min(Math.abs(b.x - a.x) / Math.max(.7f, entity.getBbWidth() * 1.7f),
                    Math.abs(b.y - a.y) / Math.max(.7f, entity.getBbHeight() * 1.15f));
            InventoryScreen.renderEntityInInventoryFollowsAngle(context.graphics, (int) a.x, (int) a.y, (int) b.x, (int) b.y,
                    Math.max(1, (int) scale), 0, -.6f, .1f, entity);
            return true;
        } catch (Exception | LinkageError failure) {
            // A third-party renderer must not prevent browsing the remaining registry.
            FAILED.add(id); ENTITIES.remove(id); return false;
        }
    }

    static boolean summonable(String id) { return previewEntity(id) instanceof Mob; }

    private static @Nullable LivingEntity previewEntity(String id) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (world != mc.level) { clear(); world = mc.level; }
        if (FAILED.contains(id)) return null;
        if (ENTITIES.containsKey(id)) return ENTITIES.get(id);
        try {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id)).orElse(null);
            if (type == null || !(type.create(mc.level, EntitySpawnReason.COMMAND) instanceof LivingEntity living)) {
                FAILED.add(id); return null;
            }
            living.setSilent(true);
            if (living instanceof Mob mob) mob.setNoAi(true);
            if (ENTITIES.size() >= 32) ENTITIES.remove(ENTITIES.keySet().iterator().next());
            ENTITIES.put(id, living);
            return living;
        } catch (Exception | LinkageError failure) { FAILED.add(id); return null; }
    }

    /** Pen-and-watercolour marginalia for resources which have no conventional icon. */
    private static GuiTexture symbol(Kind kind, String id) {
        String word = switch (kind) {
            case CREATURE -> "summon"; case DIMENSION -> "in-dimension"; case PARTICLE -> "phantasm";
            case EFFECT -> "potion"; case SHAPE -> "construct"; case WEATHER -> "weather";
            case MOTION -> "projectile"; case KIND -> "entities"; default -> "damage";
        };
        String[] picture = EnumEmblems.of(kind, id);
        if (picture == null) picture = RuneEmblems.of(word);
        if (kind == Kind.PARTICLE && picture == null) picture = new String[]{"....#....","....#....","..+.#+...","...###...","#########","...###...","..+.#+...","....#....","....#...."};
        if (picture == null) picture = RuneEmblems.of("projectile");
        Manuscript.Plan plan = new Manuscript.Plan(0);
        plan.picture(0, 0, picture, Manuscript.INK, 0xFF7F98C4);
        return (context, x, y, w, h) -> plan.draw(context, Math.round(x + (w - 9) / 2), Math.round(y + (h - 9) / 2));
    }
}

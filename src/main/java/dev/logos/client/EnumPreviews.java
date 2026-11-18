package dev.logos.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import javax.annotation.Nullable;

import com.google.gson.JsonParser;
import dev.logos.client.port.GuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

import dev.logos.graph.EnumValues.Kind;
import dev.logos.ui.Manuscript;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.MobSpawnType;
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
            if (model && !insideScissor(context, x, y, w, h)) return;
            if (model && kind == Kind.CREATURE && entity(context, id, x, y, w, h)) return;
            IGuiTexture texture = TEXTURES.computeIfAbsent(kind + ":" + id, key -> texture(kind, id));
            context.drawTexture(texture, x, y, w, h);
        };
    }

    private static IGuiTexture texture(Kind kind, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) return symbol(kind, id);
        if (kind == Kind.CREATURE) return BuiltInRegistries.ENTITY_TYPE.getOptional(key).map(SpawnEggItem::byId)
                .<IGuiTexture>map(egg -> new ItemStackTexture(new ItemStack(egg))).orElseGet(() -> symbol(kind, id));
        if (kind == Kind.ITEM) return BuiltInRegistries.ITEM.getOptional(key)
                .<IGuiTexture>map(ItemStackTexture::new).orElseGet(() -> symbol(kind, id));
        if (kind == Kind.BLOCK) return BuiltInRegistries.BLOCK.getOptional(key).filter(b -> b.asItem() != Items.AIR)
                .<IGuiTexture>map(b -> new ItemStackTexture(b.asItem())).orElseGet(() -> symbol(kind, id));
        if (kind == Kind.EFFECT && BuiltInRegistries.MOB_EFFECT.containsKey(key))
            return (GuiTexture) (context, x, y, w, h) -> context.graphics.blit((int) x, (int) y, 0, (int) w, (int) h,
                    Minecraft.getInstance().getMobEffectTextures().get(BuiltInRegistries.MOB_EFFECT.getHolderOrThrow(
                            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.MOB_EFFECT, key))));
        if (kind == Kind.PARTICLE) {
            // A particle's own texture list is available even when its provider needs extra parameters.
            var resource = Minecraft.getInstance().getResourceManager().getResource(key.withPrefix("particles/").withSuffix(".json"));
            if (resource.isPresent()) try (var reader = resource.get().openAsReader()) {
                var json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("textures") && !json.getAsJsonArray("textures").isEmpty()) {
                    IGuiTexture[] frames = json.getAsJsonArray("textures").asList().stream()
                            .map(t -> ResourceLocation.parse(t.getAsString()).withPrefix("textures/particle/").withSuffix(".png"))
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
            float scale = Math.min(w / Math.max(.7f, entity.getBbWidth() * 1.7f), h / Math.max(.7f, entity.getBbHeight() * 1.15f));
            following(context, x + w / 2, y + h / 2, Math.max(1, (int) scale), -.6f, .1f, entity);
            return true;
        } catch (Exception | LinkageError failure) {
            // A third-party renderer must not prevent browsing the remaining registry.
            FAILED.add(id); ENTITIES.remove(id); return false;
        }
    }

    /**
     * InventoryScreen.renderEntityInInventoryFollowsAngle in the context's own pose, without its screen scissor: on
     * 1.21.1 the entity is drawn through the GUI pose (the canvas' pan and zoom), and the canvas clips it already.
     */
    private static void following(GUIContext context, float cx, float cy, int scale, float angleX, float angleY, LivingEntity entity) {
        Quaternionf turn = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf tilt = new Quaternionf().rotateX(angleY * 20 * (float) (Math.PI / 180));
        turn.mul(tilt);
        float bodyRot = entity.yBodyRot, yRot = entity.getYRot(), xRot = entity.getXRot(), headRotO = entity.yHeadRotO, headRot = entity.yHeadRot;
        entity.yBodyRot = 180 + angleX * 20;
        entity.setYRot(180 + angleX * 40);
        entity.setXRot(-angleY * 20);
        entity.yHeadRot = entity.getYRot();
        entity.yHeadRotO = entity.getYRot();
        float s = entity.getScale();
        InventoryScreen.renderEntityInInventory(context.graphics, cx, cy, scale / s, new Vector3f(0, entity.getBbHeight() / 2, 0), turn, tilt, entity);
        entity.yBodyRot = bodyRot;
        entity.setYRot(yRot);
        entity.setXRot(xRot);
        entity.yHeadRotO = headRotO;
        entity.yHeadRot = headRot;
    }

    /** Whether any of the box shows through the scissor (LDLib2 1.21.1's context has no isInsideScissor). */
    private static boolean insideScissor(GUIContext context, float x, float y, float w, float h) {
        var pose = context.graphics.pose().last().pose();
        float[][] points = {{x, y}, {x + w, y}, {x, y + h}, {x + w, y + h}, {x + w / 2, y + h / 2}};
        for (float[] p : points) {
            Vector3f at = pose.transformPosition(new Vector3f(p[0], p[1], 0));
            if (context.graphics.containsPointInScissor((int) at.x, (int) at.y)) return true;
        }
        return false;
    }

    static boolean summonable(String id) { return previewEntity(id) instanceof Mob; }

    private static @Nullable LivingEntity previewEntity(String id) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (world != mc.level) { clear(); world = mc.level; }
        if (FAILED.contains(id)) return null;
        if (ENTITIES.containsKey(id)) return ENTITIES.get(id);
        try {
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(id)).orElse(null);
            if (type == null || !(type.create(mc.level) instanceof LivingEntity living)) {
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

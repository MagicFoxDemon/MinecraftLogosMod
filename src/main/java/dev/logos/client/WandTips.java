package dev.logos.client;

import java.io.Reader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.item.WandItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Where a held wand's head is in the world. Each wand model names its head with a {@code wand_tip}
 * group (see CONTRIBUTING, 6.3): the group's origin, in model units. Whenever a wand is drawn in a main
 * hand, the hand renderers report the pose they draw it with, and this turns the tip into a world
 * position, first person and third person alike; {@link CastPoints} starts charges, beams and rays there.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class WandTips {
    public static final String GROUP = "wand_tip";
    /** A pose reported this many client ticks ago or less still counts. */
    private static final int FRESH_TICKS = 2;

    private record Sample(Vec3 pos, long tick) {}

    private static final Map<Item, Vector3f> TIPS = new HashMap<>();
    private static final Map<Integer, Sample> SAMPLES = new HashMap<>();
    private static final Set<Item> WARNED = new HashSet<>();

    private WandTips() {}

    @EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        static void onReload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) rm -> TIPS.clear());
        }
    }

    /** Forgets wand heads not drawn for a while (their holders out of sight, or gone). */
    @SubscribeEvent
    static void onTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        long now = ClientCharges.ticks();
        if (now % 20 == 0) SAMPLES.values().removeIf(s -> now - s.tick > 20);
    }

    /** The world position of {@code entity}'s wand head, as last drawn, or null if it has not been drawn just now. */
    public static @Nullable Vec3 sample(int entityId) {
        Sample s = SAMPLES.get(entityId);
        return s == null || ClientCharges.ticks() - s.tick > FRESH_TICKS ? null : s.pos;
    }

    /**
     * The pose first-person hand rendering starts from. Vanilla starts it already turned to world axes,
     * a shader pack (Iris) in view space; taking the hand's pose relative to it works for both.
     */
    private static final Matrix4f HAND_BASE = new Matrix4f();

    public static void beginHands(Matrix4fc pose) {
        HAND_BASE.set(pose);
    }

    /** A first-person pose relative to {@link #beginHands}: in view space, with or without a shader pack. */
    static Matrix4f inView(Matrix4fc pose) {
        return new Matrix4f(HAND_BASE).invert().mul(pose);
    }

    /** First person: {@code pose} is the item's pose before its display transform, under {@link #beginHands}. */
    public static void captureFirstPerson(LivingEntity holder, ItemStack stack, ItemDisplayContext context, Matrix4fc pose) {
        boolean leftHand = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        boolean mainHand = leftHand == (holder.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT);
        if (!(stack.getItem() instanceof WandItem) || !castsFrom(mainHand, holder.getMainHandItem())) return;
        capture(holder, stack, context, leftHand, inView(pose), Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
    }

    /**
     * Third person: {@code pose} is camera-relative world space, before the item's display transform; only the hand a
     * wand casts from counts.
     */
    public static void captureThirdPerson(LivingEntity holder, ItemStack stack, ItemDisplayContext context, boolean leftHand, boolean mainHand,
            Matrix4fc pose) {
        if (!(stack.getItem() instanceof WandItem) || !castsFrom(mainHand, holder.getMainHandItem())) return;
        // A shader-pack shadow pass must not replace the local first-person hand sample.
        Minecraft mc = Minecraft.getInstance();
        if (mc.getCameraEntity() != null && holder.getId() == mc.getCameraEntity().getId()
                && mc.options.getCameraType().isFirstPerson()) return;
        capture(holder, stack, context, leftHand, pose, null);
    }

    /**
     * Whether a wand drawn in the main ({@code mainHand}) or off hand is the one that casts: the main hand's when it
     * holds a wand, else the off hand's, as a right-click reaches them.
     */
    private static boolean castsFrom(boolean mainHand, ItemStack mainHandStack) {
        return mainHand || !(mainHandStack.getItem() instanceof WandItem);
    }

    /**
     * {@code viewToWorld}: turns {@code pose}'s space to world axes, or null if it already has them. The tip goes
     * through the item model's display transform and the half-block shift, as ItemRenderer draws it.
     */
    private static void capture(LivingEntity holder, ItemStack stack, ItemDisplayContext context, boolean leftHand, Matrix4fc pose,
            @Nullable Quaternionfc viewToWorld) {
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getItemRenderer().getModel(stack, holder.level(), holder, holder.getId() + context.ordinal());
        PoseStack p = new PoseStack();
        p.last().pose().set(pose);
        ClientHooks.handleCameraTransforms(p, model, context, leftHand);
        p.translate(-0.5f, -0.5f, -0.5f);
        Vector3f at = new Vector3f(tip(stack.getItem())).div(16);
        p.last().pose().transformPosition(at);
        if (viewToWorld != null) viewToWorld.transform(at);
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        SAMPLES.put(holder.getId(), new Sample(camera.add(at.x, at.y, at.z), ClientCharges.ticks()));
    }

    static void forget(int entityId) {
        SAMPLES.remove(entityId);
    }

    /** The wand head in model units (0–16), read from the item's model and remembered until resources reload. */
    public static Vector3f tip(Item item) {
        return TIPS.computeIfAbsent(item, WandTips::load);
    }

    private static Vector3f load(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        @Nullable JsonObject model = null;
        ResourceLocation modelId = modelOf(id);
        Vector3f top = null;
        for (int depth = 0; modelId != null && depth < 6; depth++) {
            model = read(ResourceLocation.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json"));
            if (model == null) break;
            Vector3f found = findGroup(model.get("groups"));
            if (found != null) return found;
            if (top == null) top = topOf(model);
            modelId = model.has("parent") ? ResourceLocation.tryParse(model.get("parent").getAsString()) : null;
        }
        if (WARNED.add(item)) LogosMod.LOGGER.warn("Wand model for {} has no '{}' group; guessing its head at the top", id, GROUP);
        return top != null ? top : new Vector3f(12, 14, 8);
    }

    /**
     * The model an item is drawn with, {@code models/item/<id>.json}; for a composite staff ({@code neoforge:composite})
     * its first child, the solid body.
     */
    private static @Nullable ResourceLocation modelOf(ResourceLocation item) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(item.getNamespace(), "item/" + item.getPath());
        JsonObject model = read(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "models/" + id.getPath() + ".json"));
        if (model != null && model.has("children") && model.get("children").isJsonObject()) {
            for (var child : model.getAsJsonObject("children").entrySet()) {
                if (child.getValue().isJsonObject() && child.getValue().getAsJsonObject().has("parent")) {
                    return ResourceLocation.tryParse(child.getValue().getAsJsonObject().get("parent").getAsString());
                }
            }
        }
        return id;
    }

    private static @Nullable Vector3f findGroup(@Nullable JsonElement groups) {
        if (groups == null || !groups.isJsonArray()) return null;
        for (JsonElement g : groups.getAsJsonArray()) {
            if (!g.isJsonObject()) continue;
            JsonObject o = g.getAsJsonObject();
            if (o.has("name") && GROUP.equals(o.get("name").getAsString()) && o.has("origin")) {
                var a = o.getAsJsonArray("origin");
                return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
            }
            Vector3f inner = findGroup(o.get("children"));
            if (inner != null) return inner;
        }
        return null;
    }

    /** The middle of a cuboid model's highest face, standing in for a missing tip. */
    private static @Nullable Vector3f topOf(JsonObject model) {
        if (!model.has("elements")) return null;
        float minX = 16, maxX = 0, minZ = 16, maxZ = 0, maxY = -1;
        for (JsonElement e : model.getAsJsonArray("elements")) {
            var from = e.getAsJsonObject().getAsJsonArray("from");
            var to = e.getAsJsonObject().getAsJsonArray("to");
            maxY = Math.max(maxY, Math.max(from.get(1).getAsFloat(), to.get(1).getAsFloat()));
            minX = Math.min(minX, Math.min(from.get(0).getAsFloat(), to.get(0).getAsFloat()));
            maxX = Math.max(maxX, Math.max(from.get(0).getAsFloat(), to.get(0).getAsFloat()));
            minZ = Math.min(minZ, Math.min(from.get(2).getAsFloat(), to.get(2).getAsFloat()));
            maxZ = Math.max(maxZ, Math.max(from.get(2).getAsFloat(), to.get(2).getAsFloat()));
        }
        return maxY < 0 ? null : new Vector3f((minX + maxX) / 2, maxY, (minZ + maxZ) / 2);
    }

    private static @Nullable JsonObject read(ResourceLocation location) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) return null;
        try (Reader r = resource.get().openAsReader()) {
            JsonElement json = JsonParser.parseReader(r);
            return json.isJsonObject() ? json.getAsJsonObject() : null;
        } catch (Exception e) {
            LogosMod.LOGGER.warn("Could not read {} for its wand tip", location, e);
            return null;
        }
    }
}

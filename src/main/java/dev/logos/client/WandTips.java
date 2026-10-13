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
import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.item.WandItem;
import dev.logos.mixin.ItemStackRenderStateAccessor;
import dev.logos.mixin.LayerRenderStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;

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

    @SubscribeEvent
    static void onReload(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(LogosMod.MODID, "wand_tips"), (ResourceManagerReloadListener) rm -> TIPS.clear());
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

    /** First person: {@code pose} is the item's pose before its display transform, under {@link #beginHands}. */
    public static void captureFirstPerson(LivingEntity holder, ItemStack stack, ItemDisplayContext context, Matrix4fc pose) {
        if (!(stack.getItem() instanceof WandItem) || context.leftHand() != (holder.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT)) return;
        Minecraft mc = Minecraft.getInstance();
        ItemStackRenderState state = new ItemStackRenderState();
        mc.getItemModelResolver().updateForTopItem(state, stack, context, holder.level(), holder, 0);
        Matrix4f inView = new Matrix4f(HAND_BASE).invert().mul(pose);
        capture(holder.getId(), stack, state, context.leftHand(), inView, mc.gameRenderer.getMainCamera().rotation());
    }

    /** Third person: {@code pose} is camera-relative world space, before the item's display transform; only a main hand counts. */
    public static void captureThirdPerson(int entityId, ItemStack stack, ItemStackRenderState state, boolean leftHand, boolean mainHand, Matrix4fc pose) {
        if (!(stack.getItem() instanceof WandItem) || !mainHand) return;
        capture(entityId, stack, state, leftHand, pose, null);
    }

    /** {@code viewToWorld}: turns {@code pose}'s space to world axes, or null if it already has them. */
    private static void capture(int entityId, ItemStack stack, ItemStackRenderState state, boolean leftHand, Matrix4fc pose,
            @Nullable Quaternionfc viewToWorld) {
        var access = (ItemStackRenderStateAccessor) state;
        if (access.logos$activeLayerCount() <= 0) return;
        var layer = (LayerRenderStateAccessor) access.logos$layers()[0];
        PoseStack.Pose p = new PoseStack.Pose();
        p.pose().set(pose);
        layer.logos$itemTransform().apply(leftHand, p);
        p.mulPose(layer.logos$localTransform());
        Vector3f at = new Vector3f(tip(stack.getItem())).div(16);
        p.pose().transformPosition(at);
        if (viewToWorld != null) viewToWorld.transform(at);
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        SAMPLES.put(entityId, new Sample(camera.add(at.x, at.y, at.z), ClientCharges.ticks()));
    }

    static void forget(int entityId) {
        SAMPLES.remove(entityId);
    }

    /** The wand head in model units (0–16), read from the item's model and remembered until resources reload. */
    public static Vector3f tip(Item item) {
        return TIPS.computeIfAbsent(item, WandTips::load);
    }

    private static Vector3f load(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        @Nullable JsonObject model = null;
        Identifier modelId = modelOf(id);
        Vector3f top = null;
        for (int depth = 0; modelId != null && depth < 6; depth++) {
            model = read(Identifier.fromNamespaceAndPath(modelId.getNamespace(), "models/" + modelId.getPath() + ".json"));
            if (model == null) break;
            Vector3f found = findGroup(model.get("groups"));
            if (found != null) return found;
            if (top == null) top = topOf(model);
            modelId = model.has("parent") ? Identifier.tryParse(model.get("parent").getAsString()) : null;
        }
        if (WARNED.add(item)) LogosMod.LOGGER.warn("Wand model for {} has no '{}' group; guessing its head at the top", id, GROUP);
        return top != null ? top : new Vector3f(12, 14, 8);
    }

    /** The model a client item definition ({@code items/<id>.json}) draws, if it is a plain model. */
    private static @Nullable Identifier modelOf(Identifier item) {
        JsonObject def = read(Identifier.fromNamespaceAndPath(item.getNamespace(), "items/" + item.getPath() + ".json"));
        if (def == null || !def.has("model")) return null;
        JsonElement m = def.get("model");
        return m.isJsonObject() && m.getAsJsonObject().has("model") ? Identifier.tryParse(m.getAsJsonObject().get("model").getAsString()) : null;
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

    private static @Nullable JsonObject read(Identifier location) {
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

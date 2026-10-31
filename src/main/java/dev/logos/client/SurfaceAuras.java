package dev.logos.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.mixin.ItemStackRenderStateAccessor;
import dev.logos.mixin.LayerRenderStateAccessor;
import dev.logos.runtime.SurfaceAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * The effects on items that wear a {@link SurfaceAura}. Every time such an item is drawn (held, in first person,
 * on the ground, in an item frame) it comes through here: what clings to it (flame sheets, the glowing shell, the
 * wound mana threads) is drawn in its pose of the moment, exactly on the item; and where it is in the world is
 * noted, so each tick particles are let off its surface into the world (flames, embers, motes with trails, runes),
 * more in a swing by distance moved. The particles stay in the world, so a swing leaves a trail. Inventory icons
 * draw none.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class SurfaceAuras {
    /** What an item's render state carries (added to {@link ItemStackRenderState} by a mixin). */
    public interface Carrier {
        void logos$aura(@Nullable SurfaceAura aura, int owner);
        @Nullable SurfaceAura logos$aura();
        int logos$owner();
        ItemDisplayContext logos$context();
    }

    /** At most this many particles in all. */
    private static final int CAP = 1200;
    /** Not drawn for this many ticks, an item is taken to be gone. */
    private static final int STALE = 3;

    private static final Map<Long, Emitter> EMITTERS = new HashMap<>();
    static final List<SurfaceAuraVisual.Mote> MOTES = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static long frame;
    private static int ticks;
    private static Level shown;

    private SurfaceAuras() {}

    /** How many items are giving off an aura, and how many particles are in the world (the client scenes check them). */
    public static int emitters() { return EMITTERS.size(); }

    public static int motes() { return MOTES.size(); }

    // ---- from item rendering -------------------------------------------------------------------

    /** An item's model has been resolved: note whether it wears an aura and who holds it. Inventory icons draw none. */
    public static void resolved(ItemStackRenderState out, ItemStack stack, ItemDisplayContext context, @Nullable ItemOwner owner) {
        SurfaceAura aura = stack.isEmpty() || context == ItemDisplayContext.GUI ? null : SurfaceAura.of(stack);
        ((Carrier) out).logos$aura(aura, aura == null ? -1 : ownerId(owner));
    }

    private static int ownerId(@Nullable ItemOwner owner) {
        if (owner instanceof ItemOwner.OffsetFromOwner offset) owner = offset.owner();
        return owner instanceof Entity entity ? entity.getId() : -1;
    }

    /** An item wearing an aura is about to be drawn: note its pose and draw what clings to it. */
    public static void submitItem(Carrier carrier, PoseStack poseStack, SubmitNodeCollector collector) {
        SurfaceAura aura = carrier.logos$aura();
        var state = (ItemStackRenderStateAccessor) carrier;
        if (aura == null || state.logos$activeLayerCount() <= 0) return;
        var layer = (LayerRenderStateAccessor) state.logos$layers()[0];
        if (layer.logos$special() != null) return;
        ItemSurface surface = ItemSurface.of(layer.logos$quads());
        if (surface == null) return;
        Minecraft mc = Minecraft.getInstance();
        ItemDisplayContext context = carrier.logos$context();
        int owner = carrier.logos$owner();
        // In first person one's own third-person body is drawn only in a shader pack's shadow pass; it does not count.
        if (!context.firstPerson() && mc.getCameraEntity() != null && owner == mc.getCameraEntity().getId() && mc.options.getCameraType().isFirstPerson()) return;
        poseStack.pushPose();
        layer.logos$itemTransform().apply(context.leftHand(), poseStack.last());
        poseStack.mulPose(layer.logos$localTransform());
        Matrix4f pose = poseStack.last().pose();
        var camera = mc.gameRenderer.getMainCamera();
        // The item's pose is camera-relative: in third person already on world axes, in first person turned back
        // from view space.
        Matrix4f rel = context.firstPerson()
                ? new Matrix4f().rotation(camera.rotation()).mul(WandTips.inView(pose))
                : new Matrix4f(pose);
        if (owner >= 0) capture(owner, context, aura, surface, rel, camera.position());
        // The camera's position and screen axes in model space, for the camera-facing strips drawn on the item.
        Matrix4f inverse = new Matrix4f(pose).invert();
        Vector3f right = context.firstPerson() ? new Vector3f(1, 0, 0) : camera.rotation().transform(new Vector3f(1, 0, 0));
        Vector3f up = context.firstPerson() ? new Vector3f(0, 1, 0) : camera.rotation().transform(new Vector3f(0, 1, 0));
        VfxParts.View view = new VfxParts.View(new Vec3(inverse.transformPosition(new Vector3f())),
                new Vec3(inverse.transformDirection(right).normalize()), new Vec3(inverse.transformDirection(up).normalize()));
        float t = ticks + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long seed = owner * 0x9E3779B97F4A7C15L + context.ordinal();
        SurfaceAuraVisual.local(aura, surface, view, t, seed, poseStack, collector);
        poseStack.popPose();
    }

    private static void capture(int owner, ItemDisplayContext context, SurfaceAura aura, ItemSurface surface, Matrix4f rel, Vec3 cam) {
        long key = (long) owner << 8 | context.ordinal();
        Emitter e = EMITTERS.computeIfAbsent(key, k -> new Emitter());
        // Only the first time in a frame counts (a stack on the ground is drawn several times, and once more in a
        // shader pack's shadow pass).
        if (e.frame == frame) return;
        e.frame = frame;
        e.aura = aura;
        e.surface = surface;
        e.rel.set(rel);
        e.cam = cam;
        e.seen = ticks;
    }

    /**
     * An item giving off an aura: its pose when last drawn (model space to camera-relative world, with the camera's
     * position then), and its pose when particles were last let off; between the two they are interpolated, so a
     * fast swing does not break into separate clumps.
     */
    static final class Emitter {
        SurfaceAura aura;
        ItemSurface surface;
        final Matrix4f rel = new Matrix4f(), lastRel = new Matrix4f();
        Vec3 cam = Vec3.ZERO, lastCam;
        long frame = -1;
        int seen;

        /** Where a point in model space is in the world; {@code k} 0 is the last tick, 1 now. */
        Vec3 world(Vector3f local, float k) {
            Vector3f now = rel.transformPosition(local, new Vector3f());
            Vec3 at = cam.add(now.x, now.y, now.z);
            if (lastCam == null || k >= 1) return at;
            Vector3f before = lastRel.transformPosition(local, new Vector3f());
            Vec3 was = lastCam.add(before.x, before.y, before.z);
            return was.lerp(at, k);
        }

        /** A direction in model space, in the world (not normalised). */
        Vec3 direction(Vector3f local) {
            Vector3f d = rel.transformDirection(local, new Vector3f());
            return new Vec3(d.x, d.y, d.z);
        }

        /** How long the item's main axis is in the world (about 0.6 blocks for a sword in third person); particle sizes and speeds scale with it. */
        float scale() {
            return rel.transformDirection(new Vector3f(surface.axis), new Vector3f()).length() * surface.length();
        }

        /** How far the item's centre has moved in the world since the last tick. */
        double moved() {
            return lastCam == null ? 0 : world(surface.center, 0).distanceTo(world(surface.center, 1));
        }
    }

    // ---- events --------------------------------------------------------------------------------

    @SubscribeEvent
    static void onReload(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(LogosMod.MODID, "surface_aura"), (ResourceManagerReloadListener) rm -> ItemSurface.clear());
    }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        frame++;
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != shown) { MOTES.clear(); EMITTERS.clear(); shown = mc.level; }
        if (mc.isPaused() || mc.level == null) return;
        ticks++;
        EMITTERS.values().removeIf(e -> ticks - e.seen > STALE);
        for (Emitter e : EMITTERS.values()) {
            if (MOTES.size() < CAP) SurfaceAuraVisual.emit(e, RANDOM, MOTES);
            e.lastRel.set(e.rel);
            e.lastCam = e.cam;
        }
        MOTES.removeIf(m -> !m.tick());
    }

    /** Called after {@link LogosFx#submit}, which begins the ShaderPackOverlay frame. */
    static void submit(SubmitCustomGeometryEvent event) {
        if (MOTES.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        SurfaceAuraVisual.world(MOTES, VfxParts.View.of(camera), pt, pose, VfxParts.layered(raw, out));
        pose.popPose();
    }

    /** Emptied on a level change or when a scene ends. */
    public static void clear() {
        MOTES.clear();
        EMITTERS.clear();
    }
}

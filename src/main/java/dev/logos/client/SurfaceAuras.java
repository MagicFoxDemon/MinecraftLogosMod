package dev.logos.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import dev.logos.runtime.SurfaceAura;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import dev.logos.client.port.BufferSubmitNodeCollector;
import dev.logos.client.port.SubmitNodeCollector;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.RenderType;
import dev.logos.client.port.CameraRenderState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import dev.logos.client.port.SubmitCustomGeometryEvent;

/**
 * The effects on items that wear a {@link SurfaceAura}. Every time such an item is drawn (held, in first person,
 * on the ground, in an item frame) it comes through here: what clings to it (flame sheets, the glowing shell, the
 * wound mana threads) is drawn in its pose of the moment, exactly on the item; and where it is in the world is
 * noted, so each tick particles are let off its surface into the world (flames, embers, motes with trails, runes),
 * more in a swing by distance moved. The particles stay in the world, so a swing leaves a trail. Inventory icons
 * draw none.
 * <p>A mana wrap ({@link ManaWrap}) comes the same way: on whatever its holder's main hand draws, or through
 * {@link WrapFists} on a bare fist, drawn by its element's {@link WrapVisual}.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class SurfaceAuras {
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

    public static int motes() {
        int n = MOTES.size();
        for (WrapVisual w : WrapVisual.all()) n += w.particles();
        return n;
    }

    // ---- from item rendering -------------------------------------------------------------------

    /** Who the items drawn now belong to: the entity being drawn, or the one whose item is drawn in hand (innermost last). */
    private static final List<Entity> OWNERS = new ArrayList<>();
    private static final RandomSource QUADS = RandomSource.create();

    /**
     * From the renderers (entities, held items): items drawn until {@link #doneDrawing} belong to {@code owner}; null
     * (an item drawn for no one, as in a frame) keeps the one they belong to now.
     */
    public static void drawingFor(@Nullable Entity owner) {
        OWNERS.add(owner != null || OWNERS.isEmpty() ? owner : OWNERS.getLast());
    }

    public static void doneDrawing() {
        if (!OWNERS.isEmpty()) OWNERS.removeLast();
    }

    /**
     * ItemRenderer has just drawn an item's model, {@code poseStack} still in the model's space: if the item wears an
     * aura, or the main hand holding it a mana wrap, note its pose and draw what clings to it. Inventory icons draw none.
     */
    public static void renderedItem(ItemStack stack, ItemDisplayContext context, BakedModel model, PoseStack poseStack, MultiBufferSource buffers) {
        if (stack.isEmpty() || context == ItemDisplayContext.GUI || model.isCustomRenderer()) return;
        Entity owner = OWNERS.isEmpty() ? null : OWNERS.getLast();
        SurfaceAura aura = SurfaceAura.of(stack);
        Element wrap = wrap(owner, context);
        if (aura == null && wrap == null) return;
        ItemSurface surface = ItemSurface.of(quads(model, stack));
        if (surface == null) return;
        // 1.21.1 keeps an item's vertices until the pass ends; drawn now, what clings to the item lands on it.
        if (buffers == Minecraft.getInstance().renderBuffers().bufferSource()) flushItems();
        draw(aura, wrap, surface, context, owner == null ? -1 : owner.getId(), poseStack, BufferSubmitNodeCollector.immediate(buffers));
    }

    /** The quads of the model's first pass (a composite staff's solid body), as ItemRenderer draws them. */
    private static List<BakedQuad> quads(BakedModel model, ItemStack stack) {
        List<BakedQuad> quads = new ArrayList<>();
        BakedModel pass = model.getRenderPasses(stack, true).getFirst();
        for (Direction d : Direction.values()) {
            QUADS.setSeed(42);
            quads.addAll(pass.getQuads(null, d, QUADS));
        }
        QUADS.setSeed(42);
        quads.addAll(pass.getQuads(null, null, QUADS));
        return quads;
    }

    /** The element of the mana wrap on the hand an item is drawn in, if that is its holder's main hand. */
    private static @Nullable Element wrap(@Nullable Entity owner, ItemDisplayContext context) {
        boolean left = context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        boolean hand = left || context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        if (!hand || !(owner instanceof LivingEntity living) || left != (living.getMainArm() == HumanoidArm.LEFT)) return null;
        ManaWrap w = ManaWrap.of(living);
        return w == null ? null : w.element();
    }

    /** A bare fist wearing a mana wrap is about to be drawn, {@code poseStack} in the space its {@code fist} surface is in (see {@link WrapFists}). */
    static void submitFist(Element wrap, int owner, ItemDisplayContext context, ItemSurface fist, PoseStack poseStack, SubmitNodeCollector collector) {
        draw(null, wrap, fist, context, owner, poseStack, collector);
    }

    private static void draw(@Nullable SurfaceAura aura, @Nullable Element wrap, ItemSurface surface, ItemDisplayContext context, int owner,
            PoseStack poseStack, SubmitNodeCollector collector) {
        Minecraft mc = Minecraft.getInstance();
        // In first person one's own third-person body is drawn only in a shader pack's shadow pass; it does not count.
        // The holder is compared with the camera entity itself, not by id: a free camera (Tweakeroo's) is an entity
        // of its own that reports its player's id, and then the player's body is really drawn.
        Entity viewer = mc.getCameraEntity();
        if (!context.firstPerson() && viewer != null && mc.level != null && mc.level.getEntity(owner) == viewer
                && mc.options.getCameraType().isFirstPerson()) return;
        Matrix4f pose = poseStack.last().pose();
        var camera = mc.gameRenderer.getMainCamera();
        // The item's pose is camera-relative: in third person already on world axes, in first person turned back
        // from view space.
        Matrix4f rel = context.firstPerson()
                ? new Matrix4f().rotation(camera.rotation()).mul(WandTips.inView(pose))
                : new Matrix4f(pose);
        // The camera's position and screen axes in model space, for the camera-facing strips drawn on the item.
        Matrix4f inverse = new Matrix4f(pose).invert();
        Vector3f right = context.firstPerson() ? new Vector3f(1, 0, 0) : camera.rotation().transform(new Vector3f(1, 0, 0));
        Vector3f up = context.firstPerson() ? new Vector3f(0, 1, 0) : camera.rotation().transform(new Vector3f(0, 1, 0));
        VfxParts.View view = new VfxParts.View(new Vec3(inverse.transformPosition(new Vector3f())),
                new Vec3(inverse.transformDirection(right).normalize()), new Vec3(inverse.transformDirection(up).normalize()));
        float t = ticks + mc.getTimer().getGameTimeDeltaPartialTick(false);
        long seed = owner * 0x9E3779B97F4A7C15L + context.ordinal();
        // Under a shader pack the layers are drawn over the finished frame, so over the item already.
        SubmitNodeCollector out = ShaderPackOverlay.item(collector, context.firstPerson());
        if (aura != null) {
            if (owner >= 0) capture(owner, context, aura, null, surface, rel, camera.getPosition());
            SurfaceAuraVisual.local(aura, surface, view, t, seed, poseStack, out);
        }
        if (wrap != null) {
            if (owner >= 0) capture(owner, context, null, wrap, surface, rel, camera.getPosition());
            if (context.firstPerson() && out == collector) collector.order(0).submitCustomGeometry(poseStack, FLUSH, (p, buf) -> flushItems());
            WrapVisual.of(wrap).local(surface, view, t, seed, poseStack, out);
        }
    }

    /** Carries {@link #flushItems} into the feature pass; it writes no vertices of its own. */
    private static final RenderType FLUSH = VfxParts.add("surface/fire_loop");
    /** The item sheets and glints, which hold the vertices of every item drawn so far this pass. */
    private static final RenderType[] ITEMS = {Sheets.solidBlockSheet(), Sheets.cutoutBlockSheet(), Sheets.translucentCullBlockSheet(),
            Sheets.translucentItemSheet(), RenderType.glint(), RenderType.glintTranslucent(), RenderType.entityGlint(), RenderType.entityGlintDirect()};

    /**
     * Draws the held items now, so a wrap's layers land on their item rather than under it. Items go into fixed
     * buffers drawn only when the pass ends; in the level pass their solid half is drawn before any translucent
     * geometry, but the first-person hand pass draws every feature and only then the fixed buffers, so there the
     * item would cover what is drawn on its own face. Runs at order 0, after that order's items and before the wraps'
     * layers (order 1 and up); drawing a fixed buffer early only moves it forward.
     */
    private static void flushItems() {
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        for (RenderType type : ITEMS) buffers.endBatch(type);
    }

    private static void capture(int owner, ItemDisplayContext context, @Nullable SurfaceAura aura, @Nullable Element wrap, ItemSurface surface, Matrix4f rel, Vec3 cam) {
        long key = (long) owner << 9 | context.ordinal() << 1 | (wrap != null ? 1 : 0);
        Emitter e = EMITTERS.computeIfAbsent(key, k -> new Emitter());
        // Only the first time in a frame counts (a stack on the ground is drawn several times, and once more in a
        // shader pack's shadow pass).
        if (e.frame == frame) return;
        e.frame = frame;
        e.aura = aura;
        e.wrap = wrap;
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
        /** One of the two: an item's own aura, or the element of the wrap on the hand. */
        @Nullable SurfaceAura aura;
        @Nullable Element wrap;
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
    static void onFrame(RenderFrameEvent.Pre event) {
        frame++;
        OWNERS.clear();
    }

    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != shown) { clear(); shown = mc.level; }
        if (mc.isPaused() || mc.level == null) return;
        ticks++;
        EMITTERS.values().removeIf(e -> ticks - e.seen > STALE);
        for (Emitter e : EMITTERS.values()) {
            if (motes() < CAP) {
                if (e.wrap != null) WrapVisual.of(e.wrap).emit(e, RANDOM);
                else SurfaceAuraVisual.emit(e, RANDOM, MOTES);
            }
            e.lastRel.set(e.rel);
            e.lastCam = e.cam;
        }
        MOTES.removeIf(m -> !m.tick());
        for (WrapVisual w : WrapVisual.all()) w.tick();
    }

    /** Called after {@link LogosFx#submit}, which begins the ShaderPackOverlay frame. */
    static void submit(SubmitCustomGeometryEvent event) {
        if (motes() == 0) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        var layers = VfxParts.layered(raw, out);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        if (!MOTES.isEmpty()) SurfaceAuraVisual.world(MOTES, view, pt, pose, layers);
        for (WrapVisual w : WrapVisual.all()) if (w.particles() > 0) w.world(view, pt, pose, layers);
        pose.popPose();
    }

    /** Emptied on a level change or when a scene ends. */
    public static void clear() {
        MOTES.clear();
        EMITTERS.clear();
        for (WrapVisual w : WrapVisual.all()) w.clear();
    }

    /** Its listeners on the mod bus: on 1.21.1 a subscriber listens on one bus. */
    @EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        static void onReload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) rm -> ItemSurface.clear());
        }
    }
}

package dev.logos.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.jspecify.annotations.Nullable;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.LogosMod;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws projectile flight VFX on top of a shader pack's finished image. With an Iris shader pack on, geometry
 * drawn in the level pass goes through the pack's own programs and deferred lighting; our glows write no depth,
 * so the pack paints sky over them, and the pack's programs would drop our shaders anyway. While a pack is on,
 * the visuals submit into {@link #collector} instead, and the kept geometry is drawn with our own render types
 * once Iris has composited the frame (see {@code IrisRenderingPipelineMixin}), still depth-tested against the world.
 * What clings to held and dropped items comes the same way through {@link #item}.
 * Iris is optional, so its classes are only touched inside {@link Bridge}, which is loaded only when Iris is.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ShaderPackOverlay {
    private record Submit(PoseStack.Pose pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {}
    /** The projection and model-view Iris draws the first-person hand with, apart from the level's. */
    private record HandView(GpuBufferSlice projection, ProjectionType type, Matrix4f modelView) {}

    private static final boolean IRIS = ModList.get().isLoaded("iris");
    private static final List<Submit> KEPT = new ArrayList<>();
    /** Kept apart from {@link #KEPT}: entities, and their items, are submitted before {@link #begin}. */
    private static final List<Submit> ITEMS = new ArrayList<>(), HAND = new ArrayList<>();
    private static @Nullable HandView hand;
    /** Between the level's opaque blocks and the overlay; items drawn elsewhere (an inventory's player) are not kept. */
    private static boolean inLevel;
    private static MultiBufferSource.BufferSource buffers;

    private ShaderPackOverlay() {}

    /** Forgets last frame's geometry; called before this frame's VFX are submitted. */
    public static void begin() { KEPT.clear(); }

    /** {@code out} as is, or while a shader pack is on, a collector that keeps custom geometry for the overlay. */
    public static SubmitNodeCollector collector(SubmitNodeCollector out) {
        if (!IRIS || !Bridge.shaderPackInUse()) return out;
        return keeping(out, KEPT);
    }

    /**
     * Like {@link #collector}, for what clings to an item as it is drawn. Not in the pack's shadow pass, whose poses
     * are the sun's. In first person Iris draws the hand within the level with a projection and model-view of its
     * own; they are noted, so the overlay draws the hand's geometry with them.
     */
    public static SubmitNodeCollector item(SubmitNodeCollector out, boolean firstPerson) {
        if (!IRIS || !inLevel || !Bridge.shaderPackInUse() || Bridge.shadowPass()) return out;
        if (!firstPerson) return keeping(out, ITEMS);
        GpuBufferSlice projection = RenderSystem.getProjectionMatrixBuffer();
        if (projection == null) return out;
        hand = new HandView(projection, RenderSystem.getProjectionType(), new Matrix4f(RenderSystem.getModelViewMatrix()));
        return keeping(out, HAND);
    }

    /** Keeps custom geometry in {@code kept}, layers ({@code order}) included, in the order it is submitted. */
    private static SubmitNodeCollector keeping(SubmitNodeCollector out, List<Submit> kept) {
        return (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submitCustomGeometry") && args != null && args.length == 3) {
                        kept.add(new Submit(((PoseStack) args[0]).last().copy(), (RenderType) args[1],
                                (SubmitNodeCollector.CustomGeometryRenderer) args[2]));
                        return null;
                    }
                    if (method.getName().equals("order") && args != null && args.length == 1) return proxy;
                    try {
                        return method.invoke(out, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @SubscribeEvent
    static void onOpaqueBlocks(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        ITEMS.clear();
        HAND.clear();
        inLevel = true;
    }

    /** Draws the kept geometry over the composited frame; called by Iris's pipeline when it finishes a level. */
    public static void draw() {
        inLevel = false;
        if (KEPT.isEmpty() && ITEMS.isEmpty() && HAND.isEmpty()) return;
        if (buffers == null) buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
        render(ITEMS);
        render(KEPT);
        if (hand != null && !HAND.isEmpty()) {
            RenderSystem.backupProjectionMatrix();
            RenderSystem.setProjectionMatrix(hand.projection(), hand.type());
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.set(hand.modelView());
            render(HAND);
            modelView.popMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
        KEPT.clear();
        ITEMS.clear();
        HAND.clear();
    }

    private static void render(List<Submit> kept) {
        for (Submit s : kept) s.renderer().render(s.pose(), buffers.getBuffer(s.type()));
        buffers.endBatch();
    }

    private static final class Bridge {
        static boolean shaderPackInUse() { return IrisApi.getInstance().isShaderPackInUse(); }
        static boolean shadowPass() { return IrisApi.getInstance().isRenderingShadowPass(); }
    }
}

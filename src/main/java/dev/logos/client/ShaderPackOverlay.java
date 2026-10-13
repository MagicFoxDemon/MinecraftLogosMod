package dev.logos.client;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.neoforged.fml.ModList;

/**
 * Draws projectile flight VFX on top of a shader pack's finished image. With an Iris shader pack on, geometry
 * drawn in the level pass goes through the pack's own programs and deferred lighting; our glows write no depth,
 * so the pack paints sky over them, and the pack's programs would drop our shaders anyway. While a pack is on,
 * the visuals submit into {@link #collector} instead, and the kept geometry is drawn with our own render types
 * once Iris has composited the frame (see {@code IrisRenderingPipelineMixin}), still depth-tested against the world.
 * Iris is optional, so its classes are only touched inside {@link Bridge}, which is loaded only when Iris is.
 */
public final class ShaderPackOverlay {
    private record Submit(PoseStack.Pose pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {}

    private static final boolean IRIS = ModList.get().isLoaded("iris");
    private static final List<Submit> KEPT = new ArrayList<>();
    private static MultiBufferSource.BufferSource buffers;

    private ShaderPackOverlay() {}

    /** Forgets last frame's geometry; called before this frame's VFX are submitted. */
    public static void begin() { KEPT.clear(); }

    /** {@code out} as is, or while a shader pack is on, a collector that keeps custom geometry for the overlay. */
    public static SubmitNodeCollector collector(SubmitNodeCollector out) {
        if (!IRIS || !Bridge.shaderPackInUse()) return out;
        return (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submitCustomGeometry") && args != null && args.length == 3) {
                        KEPT.add(new Submit(((PoseStack) args[0]).last().copy(), (RenderType) args[1],
                                (SubmitNodeCollector.CustomGeometryRenderer) args[2]));
                        return null;
                    }
                    try {
                        return method.invoke(out, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /** Draws the kept geometry over the composited frame; called by Iris's pipeline when it finishes a level. */
    public static void draw() {
        if (KEPT.isEmpty()) return;
        if (buffers == null) buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));
        for (Submit s : KEPT) s.renderer().render(s.pose(), buffers.getBuffer(s.type()));
        buffers.endBatch();
        KEPT.clear();
    }

    private static final class Bridge {
        static boolean shaderPackInUse() { return IrisApi.getInstance().isShaderPackInUse(); }
    }
}

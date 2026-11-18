package dev.logos.client.port;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Posts {@link SubmitCustomGeometryEvent} after the world's particles and draws what was submitted, lowest order
 * first and otherwise as it came: through a buffer source of its own that draws at each change of render type, so a
 * translucent effect lands over what was submitted before it.
 */
@EventBusSubscriber(modid = "logos", value = Dist.CLIENT)
public final class WorldGeometry {
    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));

    private WorldGeometry() {}

    @SubscribeEvent
    static void onStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        BufferSubmitNodeCollector collector = new BufferSubmitNodeCollector();
        PoseStack pose = new PoseStack();
        CameraRenderState camera = CameraRenderState.of(event.getCamera());
        NeoForge.EVENT_BUS.post(new SubmitCustomGeometryEvent(pose, collector, new LevelRenderState(camera),
                event.getPartialTick().getGameTimeDeltaPartialTick(false)));
        if (collector.isEmpty()) return;
        collector.flush(BUFFERS);
        BUFFERS.endBatch();
    }
}

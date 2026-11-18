package dev.logos.client.port;

import com.mojang.blaze3d.vertex.PoseStack;

import net.neoforged.bus.api.Event;

/**
 * Stands in for NeoForge 26.1's {@code SubmitCustomGeometryEvent}: once a frame, after the world's translucent
 * geometry and particles, effects hand their geometry to {@link #getSubmitNodeCollector}, positioned relative to the
 * camera with {@link #getPoseStack} (the camera's rotation is already applied). Posted on the game bus by
 * {@link WorldGeometry}, which draws what was submitted once every listener has had its turn.
 */
public final class SubmitCustomGeometryEvent extends Event {
    private final PoseStack poseStack;
    private final SubmitNodeCollector collector;
    private final LevelRenderState levelRenderState;
    private final float partialTick;

    SubmitCustomGeometryEvent(PoseStack poseStack, SubmitNodeCollector collector, LevelRenderState levelRenderState, float partialTick) {
        this.poseStack = poseStack;
        this.collector = collector;
        this.levelRenderState = levelRenderState;
        this.partialTick = partialTick;
    }

    public PoseStack getPoseStack() {
        return poseStack;
    }

    public SubmitNodeCollector getSubmitNodeCollector() {
        return collector;
    }

    public LevelRenderState getLevelRenderState() {
        return levelRenderState;
    }

    public float getPartialTick() {
        return partialTick;
    }
}

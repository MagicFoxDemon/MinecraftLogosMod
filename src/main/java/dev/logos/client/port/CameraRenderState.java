package dev.logos.client.port;

import org.joml.Quaternionf;

import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Stands in for 26.1's {@code net.minecraft.client.renderer.state.level.CameraRenderState}: where the camera is this frame, and which way it looks. */
public class CameraRenderState {
    public BlockPos blockPos = BlockPos.ZERO;
    public Vec3 pos = Vec3.ZERO;
    public float xRot;
    public float yRot;
    public boolean initialized;
    public Quaternionf orientation = new Quaternionf();

    /** The camera as it is now. */
    public static CameraRenderState of(Camera camera) {
        CameraRenderState state = new CameraRenderState();
        state.blockPos = camera.getBlockPosition();
        state.pos = camera.getPosition();
        state.xRot = camera.getXRot();
        state.yRot = camera.getYRot();
        state.initialized = camera.isInitialized();
        state.orientation = new Quaternionf(camera.rotation());
        return state;
    }
}

package dev.logos.client.port;

/** Stands in for 26.1's {@code net.minecraft.client.renderer.state.level.LevelRenderState}, as far as the effects read it: the camera. */
public class LevelRenderState {
    public final CameraRenderState cameraRenderState;

    public LevelRenderState(CameraRenderState cameraRenderState) {
        this.cameraRenderState = cameraRenderState;
    }
}

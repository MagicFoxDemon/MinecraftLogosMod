package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.runtime.Vfx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * What a lightning beam leaves when it ends (see BEAM_VFX.md): the lightning ray's ionised channel along
 * the beam's last line, in the beam's cyan. The beam's after-image thins and fades over the first
 * half-second while a tight channel shows through it; the channel re-strikes four times, breaks into
 * dashes that go out one by one, and small arcs snap on and off along it and over its end, ever sparser,
 * until nothing is left about six seconds after the beam ended. Near a first-person camera it fades out.
 */
final class LightningBeamWake implements RayVisual {
    private final LightningRayVisual channel = LightningRayVisual.beamWake();

    @Override public boolean legacy() { return false; }

    @Override
    public void start(Level level, Vec3 from, Vec3 to, Vfx.RayEnd end) { channel.start(level, from, to, end); }

    @Override
    public boolean tick(Level level) { return channel.tick(level); }

    @Override
    public void submit(Level level, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        channel.submit(level, camera, partialTick, pose, out);
    }

    @Override
    public void end() { channel.end(); }
}

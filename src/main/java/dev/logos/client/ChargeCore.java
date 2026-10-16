package dev.logos.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.Level;

/**
 * The charge every element shares (see CHARGE_VFX.md): {@link ChargeHead} at the cast point and
 * {@link ChargeBody} round the caster, coloured and given their circles by a {@link ChargeStyle}. An
 * element's {@code <Element>ChargeVisual} extends it with its own style, and adds its own layers by
 * overriding {@link #tick}, {@link #submit} and {@link #end} and calling these first.
 */
public class ChargeCore implements ChargeVisual {
    protected final ChargeStyle style;
    private final ChargeHead head;
    private final ChargeBody body;

    protected ChargeCore(ChargeStyle style) {
        this.style = style;
        this.head = new ChargeHead(style);
        this.body = new ChargeBody(style);
    }

    @Override
    public final boolean legacy() { return false; }

    @Override
    public boolean tick(Level level, LiveCharge charge) {
        boolean h = head.tick(level.getRandom(), charge);
        boolean b = body.tick(level, level.getRandom(), charge);
        return h || b;
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        ChargeParts.View view = ChargeParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        body.submit(charge, view, partialTick, pose, out);
        head.submit(charge, view, partialTick, pose, out);
        pose.popPose();
    }
}

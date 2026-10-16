package dev.logos.client;

import static dev.logos.client.ChargeParts.argb;
import static dev.logos.client.ChargeParts.fade;
import static dev.logos.client.ChargeParts.smooth;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.lang.Element;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The void charge (CHARGE_VFX.md): the shared charge in void's colours, its grand and colossal inner circle
 * the ringed eye of MagicCircle 07, and one layer of its own: a glimpse of the open void every void effect
 * shows (the screen-projected starfield) in the glow of the grand and colossal ball, held and as it fills, and
 * faintly through the colossal sphere closing in. It goes with the ball on release and dims out with it if
 * dropped. The stars are added light, so the ball stays as bright as ever and the order layers are drawn in
 * does not matter.
 */
final class VoidChargeVisual extends ChargeCore {
    /** Radius of the held ball by tier, as the shared head draws it ({@code ChargeHead.BALL}). */
    private static final float[] BALL = {.09f, .13f, .19f, .28f};

    /**
     * The starfield (shader {@code core/void_charge_stars}): end-portal sky fixed to the screen as in the void
     * ray, added in a soft disc. Custom geometry through the caller's collector, like everything in the charge,
     * so it goes through {@link ShaderPackOverlay}.
     */
    private static final RenderType STARS = stars();

    VoidChargeVisual() {
        super(ChargeStyle.of(Element.VOID).withCircles("charge/circle_17", "charge/circle_18", "void_charge/circle_07",
                "charge/circle_03", "charge/circle_11", "void_charge/circle_07", "charge/circle_17"));
    }

    @Override
    public void submit(Level level, LiveCharge charge, CameraRenderState camera, float partialTick, PoseStack pose, SubmitNodeCollector out) {
        super.submit(level, charge, camera, partialTick, pose, out);
        int tier = charge.tier();
        if (tier < 2) return;
        ChargeParts.View view = ChargeParts.View.of(camera);
        Vec3 tip = charge.tip(partialTick);
        double now = charge.age(partialTick);
        float near = charge.near(), p = charge.progress(partialTick), since = charge.phaseAge(partialTick);
        float clear = fade(view.cam(), tip, .15, .45);
        LiveCharge.Phase phase = charge.phase();

        // The ball, sized as the shared head draws it; the void opens only once it is most of the way there.
        float r = BALL[tier] * near, a;
        switch (phase) {
            case CHARGING -> {
                float grow = tier == 3 ? smooth(.8f, 1f, p) : (float) Math.pow(p, 1.4);
                r *= .25f + .75f * grow;
                a = smooth(.5f, 1f, tier == 3 ? grow : p);
            }
            case HELD -> {
                r *= 1 + .06f * Mth.sin((float) now * .5f);
                a = 1;
            }
            case RELEASED -> {
                // Gone as the ball goes: the colossal one swells away over 7 ticks, the rest pop within 4.
                a = 1 - smooth(0, 1, since / (tier == 3 ? 5 : 3));
            }
            default -> {
                float k = Math.min(1, since / 20);
                r *= Math.max(.05f, (.25f + .75f * p) * (1 - k * .8f));
                a = (1 - k) * .6f;
            }
        }
        if (charge.firstPerson()) r = Math.min(r, (float) view.cam().distanceTo(tip) * .3f);

        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        float held = a * clear, heldR = r * 1.8f;
        if (held > .004f && heldR > 0)
            out.submitCustomGeometry(pose, STARS, (pp, buf) -> ChargeParts.sprite(pp, buf, view, tip, heldR, 0, argb(held, style.light())));
        // Colossal: the sphere closing in shows the same void, faintly, and closes it with itself.
        if (tier == 3 && phase == LiveCharge.Phase.CHARGING) {
            float show = smooth(.45f, .65f, p) * (1 - smooth(.9f, 1f, p));
            float k = smooth(.45f, 1f, p);
            float sr = Mth.lerp((float) Math.pow(k, 1.4), 4.2f, BALL[3]) * (near < 1 ? .7f : 1);
            float seen = show * fade(view.cam(), tip, sr, sr * 2 + .1) * .85f;
            if (seen > .004f)
                out.submitCustomGeometry(pose, STARS, (pp, buf) -> ChargeParts.sprite(pp, buf, view, tip, sr * 1.05f, 0, argb(seen, style.main())));
        }
        pose.popPose();
    }

    private static RenderType stars() {
        var pipeline = RenderPipeline.builder(RenderPipelines.BEACON_BEAM_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(id("pipeline/void_charge_stars")).withFragmentShader(id("core/void_charge_stars")).withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING)).withSampler("StarTex").build();
        return RenderType.create("logos_void_charge_stars", RenderSetup.builder(pipeline)
                .withTexture("Sampler0", id("textures/vfx/heal/glow.png"))
                .withTexture("StarTex", Identifier.withDefaultNamespace("textures/entity/end_portal/end_portal.png"))
                .sortOnUpload().createRenderSetup());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("logos", path); }
}

package dev.logos.client;

import static dev.logos.client.VfxParts.argb;
import static dev.logos.client.VfxParts.smooth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.logos.client.VfxParts.View;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Mana spiralling up round the caster into a twisting tornado (after NiagaeaBigVFXPack's NS_Tornado_Mesh_red and
 * NS_Tornado_under, layer for layer; FORM_VFX.md): a solid red core in sharp light and dark, blade-like bands winding
 * up round it, deep blue and red threads, blue specks and sparse red cracks close about the core, a white skirt
 * opening upwards at its foot over a turning ground swirl, and round the feet motes whirling and splashing up, dust
 * turning about and debris swept up in the ground block's own texture. The original layers are all Masked materials
 * and most layers here are hard-edged cutouts too ({@link VfxParts#cut}): opaque, sharp-edged, able to be dark, and
 * not washed out by day; only the core's highlight, the blades' glow and the skirt's halo are additive. It follows
 * the caster; within the caster's height the side facing the camera is cut open into a window, and a layer the
 * camera comes close to or inside is cut away too, so the caster stays visible. Its colour may turn in hue over
 * time. Client only, sized by {@code scale}.
 */
public final class ManaTornadoVisual {
    /** Winds up over GROW ticks (each layer a little after the one inside it); once released, gone from the bottom up within FADE. */
    public static final float GROW = 14, FADE = 24;

    /**
     * The UE materials' colours (normalised by brightness): the core's red and pinkish-white highlight, the blades'
     * white-blue and glow, the deep blue threads, the azure specks, the red threads, the white of the skirt and swirl,
     * the navy of the ground light. A cutout's texture RGB is brightness, times these, so its dark parts come out
     * deep red or deep blue.
     */
    private static final int CORE = 0xFF4A66, CORE_HOT = 0xFFB0C4, BLADE = 0xC4D0FF, BLADE_GLOW = 0x8FA4FF,
            BLADE_OUT = 0x8C9CFF, LINE_DEEP = 0x1B2690, AZURE = 0x3A8CFF, CRIMSON = 0xFF2448, FOAM = 0xD6E4FF, NAVY = 0x1C2C96;

    /**
     * The three meshes' outlines: STRAIGHT is SM_Tornado_Tatumaki (a tube slightly narrower at the waist), HOURGLASS
     * is SM_Tornado (straight in the middle, flaring at both ends, its lower part sunk in the ground), SKIRT is
     * SM_Tornado_en2 (a cone opening upwards, its top a third wider than its bottom).
     */
    private enum Shape { STRAIGHT, HOURGLASS, SKIRT }

    /**
     * One mesh layer: material, outline, waist radius (bottom radius for SKIRT), bottom and top in blocks above the
     * ground (may be under it; only what shows above is drawn), turns of twist from bottom to top, texture tiles
     * round it and from bottom to top, tiles turned and scrolled up per tick, alpha, colour, Fresnel (1 makes the
     * side facing the camera nearly clear, only the outline left; unused on cutouts), surface lumps and phase.
     */
    private record Tube(RenderType material, Shape shape, float radius, float bottom, float top, float twist,
            float around, float tall, float spin, float rise, float alpha, int rgb, float fresnel, float lumps, float phase) {}

    /**
     * The cutout layers, from the inside out. The original is about 25 m tall; the core here is 12 blocks. Its outer
     * layers are much larger but mostly cut away by their materials, so it reads as one compact column; here they
     * are all kept within 2.6 blocks.
     */
    private static final Tube[] CUTS = {
            // Center (Tatumaki × MI_Tornado_main_Tatumaki): the solid red core, its vertical streaks sharply light and
            // dark; twisted tightest, its lumps twisting with it into a rope.
            new Tube(VfxParts.cut("tornado/core"), Shape.STRAIGHT, 1.15f, 0, 12, 2.5f, 2, 2, .006f, .05f, 1, CORE, 0, .15f, 0),
            // Center_Side (SM_Tornado × MI_Tornado_under2): deep blue threads close about the core.
            new Tube(VfxParts.cut("tornado/streaks"), Shape.HOURGLASS, 1.3f, -1.9f, 11.9f, 1.6f, 4, 2, .01f, .04f, 1, LINE_DEEP, 0, .12f, 3.1f),
            // Center_Side2 (Tatumaki × MI_Tornado_Sander): blades winding up round the core, white in the middle, deep blue at the edges.
            new Tube(VfxParts.cut("tornado/blades"), Shape.STRAIGHT, 1.45f, 0, 12, 1.8f, 2, 1.3f, .014f, .045f, 1, BLADE, 0, .12f, 1.7f),
            // Tornado3 (SM_Tornado × MI_Tornado_Sander2): red threads.
            new Tube(VfxParts.cut("tornado/streaks"), Shape.HOURGLASS, 1.75f, -1.9f, 11.9f, 1.3f, 3, 2, -.008f, .035f, .45f, CRIMSON, 0, .1f, 5.2f),
            // Tornado8 (Tatumaki × MI_Tornado_Sander): an outer ring of sparser blue blades.
            new Tube(VfxParts.cut("tornado/blades"), Shape.STRAIGHT, 2, 0, 11, 1.4f, 1, 1, .01f, .035f, .85f, BLADE_OUT, 0, .1f, 2.3f),
            // Tornado2 (SM_Tornado × MI_Tornado_under): azure specks.
            new Tube(VfxParts.cut("tornado/specks"), Shape.HOURGLASS, 2.2f, -2.8f, 8.2f, 1, 5, 3, -.006f, .04f, .9f, AZURE, 0, .1f, 4.4f),
            // OutSide (SM_Tornado × MI_Tornado_Sander2): sparse red cracks on the very outside.
            new Tube(VfxParts.cut("pillar_burst/veins"), Shape.HOURGLASS, 2.6f, -4, 11, 1, 3, 1.5f, -.005f, .025f, .25f, CRIMSON, 0, .12f, .6f),
            // Under, MeshMain004 (en2 × under2 / under1): the white skirt opening up from the core's foot, and a ring of white-blue blades outside it.
            new Tube(VfxParts.cut("tornado/core"), Shape.SKIRT, 1.4f, -.3f, 1.1f, .4f, 3, 1, .02f, .04f, 1, FOAM, 0, .06f, 0),
            new Tube(VfxParts.cut("tornado/blades"), Shape.SKIRT, 2, -.2f, .7f, .3f, 3, .6f, -.02f, .03f, .9f, BLADE, 0, .05f, 1)};

    /** Additive glow, after the cutouts: the core's pinkish-white highlight, the glow along the blades (their parameters, slightly larger), the skirt's halo. */
    private static final Tube[] GLOWS = {
            new Tube(VfxParts.add("tornado/streaks"), Shape.STRAIGHT, 1.17f, 0, 12, 2.5f, 3, 2, .006f, .06f, .7f, CORE_HOT, .6f, .15f, 0),
            new Tube(VfxParts.add("tornado/blades"), Shape.STRAIGHT, 1.48f, 0, 12, 1.8f, 2, 1.3f, .014f, .045f, .85f, BLADE_GLOW, 0, .12f, 1.7f),
            new Tube(VfxParts.add("tornado/wisp"), Shape.SKIRT, 1.5f, -.3f, 1.3f, .4f, 3, 1, .02f, .04f, .6f, FOAM, 0, .06f, 0)};

    /**
     * A disc on the ground (SM_Tornado_en, polar UVs): material, radius, extra turns towards the centre (winding it
     * into a swirl), tiles round it, tiles turned per tick, tiles flowing inwards per tick, alpha, colour.
     */
    private record Disc(RenderType material, float radius, float swirl, float around, float spin, float inflow, float alpha, int rgb) {}

    private static final Disc[] DISCS = {
            // MeshMain006 (en × MI_Tornado_Sander1): the white ground swirl, in patches and wisps.
            new Disc(VfxParts.add("pillar_burst/cells"), 3, 2.2f, 6, .01f, .02f, .35f, FOAM),
            new Disc(VfxParts.add("tornado/wisp"), 3.2f, 2.6f, 5, .014f, .03f, .4f, FOAM),
            // MeshMain005 (en × MI_Tornado_under1): the navy ground light, turning the other way.
            new Disc(VfxParts.add("tornado/wisp"), 4, .8f, 4, -.008f, .015f, .35f, NAVY)};

    /** A mote whirling round the feet and splashing up (PointLight001), or dust turning about (Smoke): placed relative to the tornado's centre. */
    private static final class Mote {
        float angle, radius, y, vy, size;
        final float spin, born, life, drift;
        final int tint;

        Mote(float angle, float radius, float y, float vy, float spin, float drift, float size, float born, float life, int tint) {
            this.angle = angle; this.radius = radius; this.y = y; this.vy = vy; this.spin = spin; this.drift = drift;
            this.size = size; this.born = born; this.life = life; this.tint = tint;
        }
    }

    /** A piece of debris swept up (Gareki): turning round the centre, thrown up and falling back, lying a while once landed before it fades. */
    private static final class Rock {
        float angle, radius, y, vy, prevAngle, prevRadius, prevY, landed = -1;
        final float spin, drift, size, born;
        final Quaternionf turn = new Quaternionf(), tumble;
        final TextureAtlasSprite sprite;

        Rock(float angle, float radius, float vy, float spin, float drift, float size, float born, Quaternionf tumble, TextureAtlasSprite sprite) {
            this.angle = prevAngle = angle; this.radius = prevRadius = radius; this.vy = vy;
            this.spin = spin; this.drift = drift; this.size = size; this.born = born; this.tumble = tumble; this.sprite = sprite;
        }
    }

    private final Level level;
    private final int entity;
    private final float scale;
    private final int cycle;
    private final RandomSource random;
    private final List<Mote> dots = new ArrayList<>(), smoke = new ArrayList<>();
    private final List<Rock> rocks = new ArrayList<>();
    private Vec3 base, prevBase;
    private int age;
    /** The tick it starts to fade; infinite while it whirls on, set once it is released. */
    private float end;

    ManaTornadoVisual(Level level, int entity, Vec3 at, int ticks, float scale, int cycle, long seed) {
        this.level = level;
        this.entity = entity;
        this.scale = scale;
        this.cycle = cycle;
        this.random = RandomSource.create(seed);
        this.base = this.prevBase = at;
        this.end = ticks < 0 ? Float.MAX_VALUE : Math.max(GROW, ticks - FADE);
    }

    int entity() { return entity; }

    /** Released: it fades out from now. */
    void release() { end = Math.min(end, age); }

    /** One client tick; false once it has faded. */
    boolean tick() {
        age++;
        prevBase = base;
        if (entity >= 0) {
            Entity e = level.getEntity(entity);
            if (e == null || e.isRemoved()) release();
            else base = e.position();
        }
        boolean spawning = age < end;
        float s = scale;
        // PointLight001: over three hundred a second there, four a tick here, whirling fast round the feet,
        // splashing a block or two up and falling back.
        if (spawning) for (int i = 0; i < 4; i++) {
            dots.add(new Mote(random.nextFloat() * Mth.TWO_PI, (.9f + 1.5f * random.nextFloat()) * s, .05f * s, (.14f + .14f * random.nextFloat()) * s,
                    (.14f + .2f * random.nextFloat()) * (random.nextBoolean() ? 1 : .8f), .01f * s, (.025f + .025f * random.nextFloat()) * s,
                    age, 18 + random.nextInt(18), random.nextInt(3) == 0 ? BLADE : 0xFFFFFF));
        }
        // Smoke: a puff every other tick, drifting up as it turns.
        if (spawning && age % 2 == 0) {
            smoke.add(new Mote(random.nextFloat() * Mth.TWO_PI, (1 + 1.2f * random.nextFloat()) * s, .2f * s, (.03f + .03f * random.nextFloat()) * s,
                    .06f + .06f * random.nextFloat(), .02f * s, (.7f + .5f * random.nextFloat()) * s, age, 16 + random.nextInt(24),
                    VfxParts.groundTint(level, base)));
        }
        // Gareki: a piece torn from the ground round the feet every three ticks.
        if (spawning && age % 3 == 0) rock();
        for (Mote d : dots) {
            d.angle += d.spin;
            d.radius += d.drift;
            d.y = Math.max(0, d.y + d.vy);
            d.vy -= .022f * s;
        }
        for (Mote m : smoke) {
            m.angle += m.spin;
            m.radius += m.drift;
            m.y += m.vy;
        }
        for (Rock r : rocks) {
            r.prevAngle = r.angle; r.prevRadius = r.radius; r.prevY = r.y;
            if (r.landed >= 0) continue;
            r.angle += r.spin;
            r.radius = Math.max(.6f * s, r.radius + r.drift);
            r.y += r.vy;
            r.vy -= .02f * s;
            r.turn.mul(r.tumble);
            if (r.vy < 0 && r.y <= r.size) { r.y = r.prevY = r.size; r.landed = age; }
        }
        dots.removeIf(d -> age - d.born > d.life);
        smoke.removeIf(m -> age - m.born > m.life);
        rocks.removeIf(r -> r.landed >= 0 ? age - r.landed > 22 : age - r.born > 120);
        return age < end + FADE || !dots.isEmpty() && age < end + FADE + 40;
    }

    private void rock() {
        float a = random.nextFloat() * Mth.TWO_PI, r = (1.4f + 3.4f * random.nextFloat()) * scale;
        TextureAtlasSprite sprite = VfxParts.blockSprite(level, base.x + Mth.cos(a) * r, base.y, base.z + Mth.sin(a) * r);
        if (sprite == null) return;
        Quaternionf tumble = new Quaternionf().rotateXYZ((random.nextFloat() - .5f) * .5f, (random.nextFloat() - .5f) * .5f, (random.nextFloat() - .5f) * .5f);
        // Thrown up in a cone of about 32° (Gareki's Cone Angle 32), swept round the centre and slowly drawn in.
        Rock rock = new Rock(a, r, (.22f + .2f * random.nextFloat()) * scale, .07f + .07f * random.nextFloat(), -.015f * scale,
                (.06f + .1f * random.nextFloat()) * scale, age, tumble, sprite);
        rock.turn.rotateXYZ(random.nextFloat() * 6.3f, random.nextFloat() * 6.3f, random.nextFloat() * 6.3f);
        rocks.add(rock);
    }

    /**
     * {@code layered}: the cutouts in the first batch (writing depth), the additive glow in the next, so the glow
     * behind the core is hidden by it (see {@link VfxParts#layered}). The ground discs, motes, dust and debris go to
     * {@code out}.
     */
    void submit(View view, float pt, PoseStack stack, SubmitNodeCollector out, IntFunction<OrderedSubmitNodeCollector> layered) {
        float t = age + pt;
        Vec3 at = prevBase.lerp(base, pt);
        // Released: it drifts up a little and goes from the bottom up.
        float gone = t <= end ? 0 : smooth(end, end + FADE, t);
        float strength = (1 - gone * gone) * (1 + .05f * Mth.sin(t * .31f) + .03f * Mth.sin(t * .83f));
        float turns = cycle > 0 ? t / cycle : 0;
        if (strength > 0) {
            tubes(stack, layered.apply(0), CUTS, view, at, t, gone, strength, turns);
            tubes(stack, layered.apply(1), GLOWS, view, at, t, gone, strength, turns);
            // A wide blue-white pool of light on the ground (UE's point light on the floor).
            int glow = VfxParts.hue(BLADE_GLOW, turns);
            float spreadGlow = Mth.clamp(t / GROW, 0, 1);
            out.submitCustomGeometry(stack, VfxParts.add("heal/glow"), (pose, buf) ->
                    VfxParts.ground(pose, buf, at.add(0, .03, 0), 4.5f * scale * (.5f + .5f * spreadGlow), 0, argb(strength * .55f, glow)));
            float spread = Mth.clamp(t / GROW, 0, 1);
            for (Disc d : DISCS) {
                int rgb = VfxParts.hue(d.rgb(), turns);
                out.submitCustomGeometry(stack, d.material(), (pose, buf) -> disc(pose, buf, at, d, t, spread, strength, rgb));
            }
        }
        motes(stack, out, view, at, t, pt, turns);
        rocks(stack, out, view, at, t, pt);
    }

    /** Submits a set of mesh layers, layer {@code i} starting to grow 0.8 ticks after the one before. */
    private void tubes(PoseStack stack, OrderedSubmitNodeCollector to, Tube[] layers, View view, Vec3 at, float t, float gone,
            float strength, float turns) {
        for (int i = 0; i < layers.length; i++) {
            Tube l = layers[i];
            float grow = 1 - (float) Math.pow(1 - Mth.clamp((t - i * .8f) / GROW, 0, 1), 3);
            if (grow <= 0) continue;
            int rgb = VfxParts.hue(l.rgb(), turns);
            to.submitCustomGeometry(stack, l.material(), (pose, buf) -> tube(pose, buf, view, at, l, t, grow, gone, strength, rgb));
        }
    }

    /** The outline: the radius factor at {@code f} of the full height above the bottom. */
    private static float profile(Shape shape, float f) {
        return switch (shape) {
            case STRAIGHT -> 1 + .38f * Mth.square((f - .45f) / .55f);
            case HOURGLASS -> 1 + 1.3f * (float) Math.pow(Math.max(0, Math.abs(f - .5f) - .25f) / .25f, 1.6);
            case SKIRT -> 1 + f / 3;
        };
    }

    /** Alpha along the height: fading in at the bottom and out at the top (the T_Gradation mask in the UE materials). {@code f0} is where it comes out of the ground. */
    private static float gradient(Shape shape, float f, float f0) {
        if (shape == Shape.SKIRT) return smooth(f0, f0 + .25f, f) * (1 - smooth(.35f, 1, f));
        return smooth(f0, f0 + .08f, f) * (1 - smooth(.62f, 1, f));
    }

    /**
     * Draws one mesh layer: cut into rings up its height, each ring's vertices laid out by the outline, the surface
     * lumps and a slight sway of the whole. A whole number of tiles goes round it, turned further the higher it is
     * (the twist), turning and scrolling up over time. Winding up, it grows up out of the ground; released, it goes
     * from the bottom up.
     */
    private void tube(PoseStack.Pose pose, VertexConsumer buf, View view, Vec3 at, Tube l, float t, float grow, float gone,
            float strength, int rgb) {
        float s = scale, height = l.top() - l.bottom();
        float yLo = Math.max(0, l.bottom()) * s, yHi = l.top() * s;
        float yTop = yLo + (yHi - yLo) * grow;
        float f0 = (Math.max(0, l.bottom()) - l.bottom()) / height;
        float lift = gone * 2.5f * s;
        float near = (float) Math.hypot(view.cam().x - at.x, view.cam().z - at.z);
        int rings = 20, segments = Mth.clamp(Math.round(l.radius() * s * 12), 24, 64);
        Vec3[][] p = new Vec3[rings + 1][segments + 1];
        int[][] c = new int[rings + 1][segments + 1];
        float[] v = new float[rings + 1];
        for (int j = 0; j <= rings; j++) {
            float y = Mth.lerp((float) j / rings, yLo, yTop);
            float f = (y / s - l.bottom()) / height;
            float r = l.radius() * s * profile(l.shape(), f);
            // With the camera inside this ring (the caster's own view) the layer drops to 40%, so the big outer
            // tubes do not fill the view.
            float a = strength * l.alpha() * gradient(l.shape(), f, f0) * Mth.lerp(smooth(r * .8f, r * 1.3f, near), .4f, 1);
            // Growing, its front is soft; fading, the bottom goes first.
            if (grow < 1) a *= 1 - smooth(yTop - 1.5f * s, yTop + .01f, y);
            a *= smooth(gone * 1.2f - .2f, gone * 1.2f, f);
            // The whole column bends gently to one side and sways slowly, more the higher up, like a real tornado.
            float bend = .1f + .25f * f;
            double sx = bend * r * Mth.sin(f * 2.6f + t * .03f + l.phase()), sz = bend * r * Mth.cos(f * 2.1f + t * .026f + l.phase());
            v[j] = t * l.rise() - f * l.tall();
            // The lumps wind up along the twist, so the outline is no straight vertical line.
            float spiral = Mth.TWO_PI * l.twist() * f;
            for (int i = 0; i <= segments; i++) {
                float th = Mth.TWO_PI * i / segments;
                float lump = 1 + l.lumps() * (.6f * Mth.sin(3 * (th - spiral) + t * .05f + l.phase()) + .4f * Mth.sin(5 * (th - spiral) - f * 6 + t * .08f + l.phase() * 2));
                float cos = Mth.cos(th), sin = Mth.sin(th);
                Vec3 q = at.add(sx + cos * r * lump, y + lift, sz + sin * r * lump);
                p[j][i] = q;
                float alpha = a;
                Vec3 to = view.cam().subtract(q);
                double len = to.length();
                float facing = len < 1e-4 ? 1 : (float) Math.abs((to.x * cos + to.z * sin) / len);
                if (l.fresnel() > 0) {
                    // Nearly clear where it faces the camera, brightest seen at a slant, and pulled back again right
                    // at the silhouette so it draws no hard vertical edge.
                    float e = 1 - facing;
                    float edge = .1f + .9f * smooth(0, .75f, e) * (1 - .45f * smooth(.88f, 1, e));
                    alpha *= Mth.lerp(l.fresnel(), 1, edge);
                }
                // The inner layers open a window on the side facing the camera within the caster's height (a cutout
                // goes first where its texture alpha is low, so the window's edge is ragged): the caster shows through.
                if (r < 3.2f * s) {
                    float front = len < 1e-4 ? 0 : (float) Math.max(0, (to.x * cos + to.z * sin) / len);
                    alpha *= 1 - .97f * (float) Math.pow(front, 1.2) * (1 - smooth(1.9f * s, 2.7f * s, y));
                }
                alpha *= VfxParts.fade(view.cam(), q, .7, 2.6);
                c[j][i] = argb(alpha, rgb);
            }
        }
        float uTwist = l.twist() * l.around(), u0 = t * l.spin();
        for (int j = 0; j < rings; j++) {
            float fa = ((float) j / rings * (yTop - yLo) + yLo) / s, fb = ((float) (j + 1) / rings * (yTop - yLo) + yLo) / s;
            float ua = u0 + uTwist * (fa - l.bottom()) / height, ub = u0 + uTwist * (fb - l.bottom()) / height;
            for (int i = 0; i < segments; i++) {
                if ((c[j][i] | c[j][i + 1] | c[j + 1][i] | c[j + 1][i + 1]) >>> 24 == 0) continue;
                float s0 = l.around() * i / segments, s1 = l.around() * (i + 1) / segments;
                VfxParts.vertex(pose, buf, p[j][i], ua + s0, v[j], c[j][i]);
                VfxParts.vertex(pose, buf, p[j + 1][i], ub + s0, v[j + 1], c[j + 1][i]);
                VfxParts.vertex(pose, buf, p[j + 1][i + 1], ub + s1, v[j + 1], c[j + 1][i + 1]);
                VfxParts.vertex(pose, buf, p[j][i + 1], ua + s1, v[j], c[j][i + 1]);
            }
        }
    }

    /** A disc on the ground: the texture laid in polar coordinates, turned more towards the centre into a swirl, turning and flowing inwards over time; faint at the centre and the rim. */
    private void disc(PoseStack.Pose pose, VertexConsumer buf, Vec3 at, Disc d, float t, float spread, float strength, int rgb) {
        int rings = 10, segments = 48;
        float big = d.radius() * scale * (.4f + .6f * spread);
        Vec3 center = at.add(0, .04, 0);
        for (int j = 0; j < rings; j++) {
            float k0 = (float) j / rings, k1 = (float) (j + 1) / rings;
            int c0 = argb(strength * d.alpha() * smooth(.05f, .25f, k0) * (1 - smooth(.6f, 1, k0)), rgb);
            int c1 = argb(strength * d.alpha() * smooth(.05f, .25f, k1) * (1 - smooth(.6f, 1, k1)), rgb);
            if ((c0 | c1) >>> 24 == 0) continue;
            float w0 = d.swirl() * (1 - k0), w1 = d.swirl() * (1 - k1), v0 = k0 * 2 + t * d.inflow(), v1 = k1 * 2 + t * d.inflow();
            for (int i = 0; i < segments; i++) {
                float a0 = Mth.TWO_PI * i / segments, a1 = Mth.TWO_PI * (i + 1) / segments;
                float s0 = d.around() * ((float) i / segments) + t * d.spin(), s1 = d.around() * ((float) (i + 1) / segments) + t * d.spin();
                VfxParts.vertex(pose, buf, center.add(Mth.cos(a0) * big * k0, 0, Mth.sin(a0) * big * k0), s0 + w0 * d.around(), v0, c0);
                VfxParts.vertex(pose, buf, center.add(Mth.cos(a0) * big * k1, 0, Mth.sin(a0) * big * k1), s0 + w1 * d.around(), v1, c1);
                VfxParts.vertex(pose, buf, center.add(Mth.cos(a1) * big * k1, 0, Mth.sin(a1) * big * k1), s1 + w1 * d.around(), v1, c1);
                VfxParts.vertex(pose, buf, center.add(Mth.cos(a1) * big * k0, 0, Mth.sin(a1) * big * k0), s1 + w0 * d.around(), v0, c0);
            }
        }
    }

    private void motes(PoseStack stack, SubmitNodeCollector out, View view, Vec3 at, float t, float pt, float turns) {
        if (!smoke.isEmpty()) out.submitCustomGeometry(stack, VfxParts.blend("physical_ray/smoke"), (pose, buf) -> {
            for (Mote m : smoke) {
                float k = (t - m.born) / m.life;
                if (k < 0 || k >= 1) continue;
                float angle = m.angle + m.spin * pt;
                Vec3 q = at.add(Mth.cos(angle) * m.radius, m.y + m.vy * pt, Mth.sin(angle) * m.radius);
                float a = Math.min(k / .15f, 1) * (1 - k) * (1 - k) * .4f * VfxParts.fade(view.cam(), q, 1.5, 4);
                if (a > 0) VfxParts.sprite(pose, buf, view, q, m.size * (1 + 1.5f * k), m.born + k, argb(a, m.tint));
            }
        });
        if (!dots.isEmpty()) out.submitCustomGeometry(stack, VfxParts.add("heal/glow"), (pose, buf) -> {
            for (Mote d : dots) {
                float k = (t - d.born) / d.life;
                if (k < 0 || k >= 1) continue;
                float angle = d.angle + d.spin * pt;
                Vec3 q = at.add(Mth.cos(angle) * d.radius, Math.max(0, d.y + d.vy * pt), Mth.sin(angle) * d.radius);
                float a = Math.min(k / .1f, 1) * (1 - k * k) * VfxParts.fade(view.cam(), q, .5, 1.5);
                if (a > 0) VfxParts.sprite(pose, buf, view, q, d.size * 2.5f, 0, argb(a, d.tint == 0xFFFFFF ? 0xFFFFFF : VfxParts.hue(d.tint, turns)));
            }
        });
    }

    private void rocks(PoseStack stack, SubmitNodeCollector out, View view, Vec3 at, float t, float pt) {
        if (rocks.isEmpty()) return;
        out.submitCustomGeometry(stack, VfxParts.BLOCKS, (pose, buf) -> {
            for (Rock r : rocks) {
                float gone = r.landed >= 0 ? (t - r.landed - 12) / 10 : 0;
                float angle = Mth.lerp(pt, r.prevAngle, r.angle), radius = Mth.lerp(pt, r.prevRadius, r.radius), y = Mth.lerp(pt, r.prevY, r.y);
                Vec3 q = at.add(Mth.cos(angle) * radius, y, Mth.sin(angle) * radius);
                float a = (1 - Mth.clamp(gone, 0, 1)) * Math.min(1, (t - r.born) / 3) * VfxParts.fade(view.cam(), q, 1, 2.5);
                if (a <= 0) continue;
                TextureAtlasSprite s = r.sprite;
                float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
                VfxParts.cube(pose, buf, q, r.size, r.turn, s.getU0() + du, s.getV0() + dv, s.getU0() + du * 2.5f, s.getV0() + dv * 2.5f, 0xFFFFFF, a);
            }
        });
    }
}

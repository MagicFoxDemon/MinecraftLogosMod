#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The void field's magic-circle lines as the shared core draws them (FieldCore, FieldParts; paired with
// core/field_line): only the violet glow at the lips of the cut. Over the big circles VoidFieldSlit lays the
// cut itself - the screen-space starfield, opaque - on top, so what this shows there is the glow spilling out
// round the lines; the clock's small circle has no cut and glows whole. Sampler0 is the circle (its alpha is
// where the lines are), FillTex the void nebula (void_beam/aura), drifting through the glow and breaking the
// lines up as they dissolve - the same flow as void_field_slit.fsh, so the cut and its glow break up together.
// fieldParam.x is how far out a pulse's light has swept (circle radii), fieldParam.y how far the circle has
// dissolved. The vertex colour is the violet; its alpha how much shows.
uniform sampler2D Sampler0;
uniform sampler2D FillTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

float flowAt(vec2 uv, float seconds) {
    vec2 d = uv - .5;
    float r = length(d) * 2;
    float a = atan(d.y, d.x) / 6.2831853;
    float f1 = texture(FillTex, vec2(a * 3 + seconds * .012, r * 1.2 - seconds * .02)).r;
    float f2 = texture(FillTex, vec2(a * 5 - seconds * .02, r * 2 - seconds * .035)).r;
    return f1 * .6 + f2 * .4;
}

void main() {
    float mask = texture(Sampler0, texCoord0).a;
    // The glow round the lines: the mask spread a few texels out.
    float halo = 0;
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * .785398;
        vec2 o = vec2(cos(ang), sin(ang));
        halo += texture(Sampler0, texCoord0 + o * .003).a * .045 + texture(Sampler0, texCoord0 + o * .006).a * .015;
    }
    float glow = max(mask, halo);
    if (glow < .01) discard;
    float seconds = GameTime * 1200;
    float flow = flowAt(texCoord0, seconds);
    if (fieldParam.y > 0 && flow < fieldParam.y * 1.1 - .05) discard;
    float r = length(texCoord0 - .5) * 2;
    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .07, 2)) * 1.6 + smoothstep(sweep - .35, sweep, r) * step(r, sweep) * .35;
    vec3 violet = vertexColor.rgb;
    vec3 color = violet * (.4 + .7 * flow + lit) + vec3(.55, .35, 1) * lit * .4;
    fragColor = vec4(color, clamp(glow * vertexColor.a, 0, 1)) * ColorModulator;
}

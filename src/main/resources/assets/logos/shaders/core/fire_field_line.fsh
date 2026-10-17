#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The fire field's magic-circle lines (FireFieldVisual): fire rolling along inside every line. Sampler0 is the
// circle (alpha is where the lines are), FillTex a tileable noise (fire_ray/noise, in alpha). In the circle's
// polar coordinates two octaves of the noise, warped by a third, run round the circle and creep outwards, so
// tongues of flame chase each other along the lines with a heat shimmer through them. The heat is hottest
// (gold to white) deep inside a line and cools to dark red at its edges. Same inputs as field_line.fsh:
// fieldParam.x is how far out a pulse's light has swept (the lines flare up as it passes), fieldParam.y how
// far the circle has dissolved (it burns away along the fire's own pattern, its burning edge glowing).
// The vertex colour's alpha is how much shows; its colour only tints the flame a little.
uniform sampler2D Sampler0;
uniform sampler2D FillTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

float noise(vec2 p) { return texture(FillTex, p).a; }

vec3 heat(float h) {
    vec3 c = mix(vec3(.35, .02, .0), vec3(.95, .22, .02), smoothstep(.0, .35, h));
    c = mix(c, vec3(1.0, .58, .12), smoothstep(.3, .65, h));
    return mix(c, vec3(1.0, .93, .72), smoothstep(.75, 1.0, h));
}

void main() {
    float mask = texture(Sampler0, texCoord0).a;
    if (mask < .01) discard;
    // How deep inside a line: the least of the mask a couple of texels round.
    vec2 px = 2.0 / vec2(textureSize(Sampler0, 0));
    float inner = min(min(texture(Sampler0, texCoord0 + vec2(px.x, 0)).a, texture(Sampler0, texCoord0 - vec2(px.x, 0)).a),
            min(texture(Sampler0, texCoord0 + vec2(0, px.y)).a, texture(Sampler0, texCoord0 - vec2(0, px.y)).a));
    inner = min(inner, mask);

    float seconds = GameTime * 1200;
    vec2 d = texCoord0 - .5;
    float r = length(d) * 2;
    float a = atan(d.y, d.x) / 6.2831853;
    // The shimmer: a slow, coarse warp rising through the fire.
    float w = noise(vec2(a * 3 + seconds * .02, r * 1.2 - seconds * .16));
    vec2 warp = vec2((w - .5) * .22, (w - .5) * .35);
    // Flames running round the circle and creeping outwards, a faster finer layer over a slower broad one.
    float f1 = noise(vec2(a * 7 + seconds * .07, r * 2.2 - seconds * .30) + warp);
    float f2 = noise(vec2(a * 15 + seconds * .16, r * 4.5 - seconds * .62) + warp * 1.6);
    float flow = f1 * .62 + f2 * .48;

    // Burning away along the fire's pattern once it has ended, the burning edge glowing.
    float burnt = fieldParam.y;
    float cut = burnt * 1.15 - .05;
    if (burnt > 0 && flow < cut) discard;
    float edge = burnt > 0 ? 1 - smoothstep(0, .12, flow - cut) : 0;

    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .08, 2)) * 1.4 + smoothstep(sweep - .35, sweep, r) * step(r, sweep) * .3;

    // White-hot only where the pulse's light or the burning edge passes: the steady fire stays orange-gold.
    float h = clamp(min(.08 + flow * (.35 + .45 * inner) + inner * .12, .74) + lit * .4 + edge * .4, 0, 1);
    vec3 color = heat(h) * mix(vec3(1), vertexColor.rgb * 1.3, .2);
    // The edges and the cool parts of the flame show less, so the fire breaks up as it runs.
    float show = mask * (.3 + .7 * smoothstep(.25, .65, h)) * (1.1 + lit * .8 + edge);
    fragColor = vec4(color, clamp(show * vertexColor.a, 0, 1)) * ColorModulator;
}

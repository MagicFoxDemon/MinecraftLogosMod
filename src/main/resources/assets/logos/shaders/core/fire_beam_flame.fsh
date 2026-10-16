#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The fire beam's rolling flame (FireRay's Fire_Alpha_Colored_StrongWarp stream, rebuilt as strips).
// U runs along the beam in blocks / 4 (plus a per-strip seed), V across it (0..1). Vertex alpha is how much
// of the layer to show at that point; it also raises the erosion threshold so a dying flame breaks apart.
//   FIRE_FLAME  - additive turbulent flame: two noise octaves rolling toward the target, edges eaten away
//   FIRE_CORE   - additive white-gold heart that flickers
//   FIRE_SHEATH - alpha-blended dark-red soot licking the outside (Smoke_HarshBlack_Alpha), for contrast in daylight
//   FIRE_SMOKE  - alpha-blended smoke left behind (the wake): wisps eaten from their thin parts inward
//   FIRE_EMBER  - additive smouldering glow of the wake's first seconds
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

vec3 heat(float h) {
    vec3 c = mix(vec3(.45, .03, .0), vec3(1.0, .30, .02), smoothstep(.0, .4, h));
    c = mix(c, vec3(1.0, .66, .16), smoothstep(.35, .7, h));
    return mix(c, vec3(1.0, .95, .78), smoothstep(.72, 1.0, h));
}

void main() {
    float seconds = GameTime * 1200.0;
    vec2 uv = texCoord0;
    float amount = vertexColor.a;
#ifdef FIRE_FLAME
    float n1 = texture(NoiseTex, vec2(uv.x * .45 - seconds * 1.15, uv.y * .55 + seconds * .21)).a;
    float n2 = texture(NoiseTex, vec2(uv.x * 1.3 - seconds * 2.7, uv.y * 1.05 - seconds * .47)).a;
    float v = uv.y + (n1 - .5) * .42 + (n2 - .5) * .16;
    float across = abs(v * 2.0 - 1.0);
    float tongue = texture(Sampler0, vec2(uv.x * .8 - seconds * 2.0, v)).a;
    float body = (1.0 - across * across) * (.45 + .75 * n2) + tongue * .45 - .18;
    float cut = .2 + (1.0 - amount) * .45;
    float d = smoothstep(cut, cut + .3, body);
    float h = clamp(body * .95 + n1 * .25 - .1, 0.0, 1.0);
    float edge = smoothstep(.0, .12, uv.y) * smoothstep(1.0, .88, uv.y);
    vec3 color = heat(h);
    float alpha = d * edge * min(1.0, amount * 1.6);
#elif defined(FIRE_CORE)
    float n = texture(NoiseTex, vec2(uv.x * .9 - seconds * 3.2, uv.y * .4 + seconds * .5)).a;
    float across = abs(uv.y * 2.0 - 1.0 + (n - .5) * .3);
    float core = exp(-across * across * 5.0) * (1.0 - smoothstep(.75, 1.0, across)) * (.75 + .45 * n);
    vec3 color = mix(vec3(1.0, .55, .12), vec3(1.0, .97, .86), smoothstep(.35, .85, core));
    float alpha = clamp(core, 0.0, 1.0) * amount;
#elif defined(FIRE_SHEATH)
    float n1 = texture(NoiseTex, vec2(uv.x * .35 - seconds * .8, uv.y * .5 + seconds * .3)).a;
    float n2 = texture(NoiseTex, vec2(uv.x * 1.1 - seconds * 1.6, uv.y * .9 + seconds * .6)).a;
    float wisp = texture(Sampler0, vec2(uv.x * .5 - seconds * .9, uv.y + (n1 - .5) * .3)).a;
    float across = abs(uv.y * 2.0 - 1.0 + (n1 - .5) * .35);
    // Densest a little off the middle (the flame covers the middle), thin to nothing at the rims.
    float band = smoothstep(1.0, .55, across) * (.35 + .65 * smoothstep(.1, .5, across));
    float body = band * (wisp * .7 + n2 * .6);
    float alpha = smoothstep(.25, .6, body) * amount * .7;
    vec3 color = mix(vec3(.30, .03, .01), vec3(.06, .02, .02), smoothstep(.3, .9, across));
#elif defined(FIRE_SMOKE)
    float wisp = texture(Sampler0, vec2(uv.x * .5 + seconds * .04, uv.y)).a;
    float noise = texture(NoiseTex, vec2(uv.x * .3 - seconds * .025, uv.y * .5 + seconds * .06)).a;
    float noise2 = texture(NoiseTex, vec2(uv.x * .9 + seconds * .05, uv.y * .8 + seconds * .1)).a;
    float edge = smoothstep(0.0, .3, uv.y) * smoothstep(1.0, .7, uv.y);
    float body = (wisp * .55 + noise * .45 + noise2 * .2) * edge;
    float cut = (1.0 - amount) * .8;
    float alpha = smoothstep(cut, cut + .28, body) * min(1.0, amount * 2.0) * .8;
    vec3 color = vec3(1.0);
#else
    float n = texture(NoiseTex, vec2(uv.x * .6 - seconds * .12, uv.y * .7 + seconds * .2)).a;
    float across = abs(uv.y * 2.0 - 1.0);
    float glow = exp(-across * across * 4.0) * smoothstep(.25, .75, n + (1.0 - amount) * -.3);
    vec3 color = mix(vec3(.6, .06, .0), vec3(1.0, .42, .06), smoothstep(.3, .9, glow * amount));
    float alpha = glow * amount;
#endif
    fragColor = vec4(color * vertexColor.rgb, alpha) * ColorModulator;
    if (fragColor.a < .004) discard;
}

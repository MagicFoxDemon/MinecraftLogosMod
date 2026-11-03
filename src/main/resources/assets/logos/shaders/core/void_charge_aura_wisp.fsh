#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The dark parts of the colossal void aura (VoidChargeAura, vertex stage core/charge_aura_evoke): wisps of open void
// flying in to the caster, their dark trails and specks, and the smoke rising behind it. After DarkSummon's
// M_Vfx_Stuff_Fang_Blood, a masked (cut-out) material: smoke noise (NoiseTex, T_Vfx_Noise_fire04) in a soft shape,
// eaten away by panned cloud noise (Sampler0, T_Vfx_Noise_cloudy); where Fang_Blood sprinkles a dense dot texture,
// here the screen-space starfield of every void effect (StarTex the end portal) over its dark nebula (AuraTex). It
// is solid and writes depth, so nothing glowing washes over the void. The vertex colour is the violet of the eaten
// edge; its alpha how much is left (1 whole, 0 gone); auraParam.x a seed. RIBBON: a trail, texCoord0.x running
// from its head (0) to its tail (1), else a quad round its middle.
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
uniform sampler2D StarTex;
uniform sampler2D AuraTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 auraParam;
in vec3 viewPosition;
out vec4 fragColor;

vec3 stars(vec2 screen, float seconds) {
    vec3 field = vec3(0);
    for (int i = 0; i < 6; i++) {
        float layer = float(i + 1), angle = layer * layer * 1.73;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = rotate * screen * (.55 + layer * .23);
        uv += vec2(layer * .713, seconds * (.002 + layer * .001));
        vec3 sampleColor = texture(StarTex, uv).rgb;
        float star = smoothstep(.12, .75, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += star * mix(vec3(.42, .16, .95), vec3(.92, .78, 1), layer / 6) * .85;
    }
    return field;
}

void main() {
    float seconds = GameTime * 1200;
    vec2 uv = texCoord0;
    float seed = auraParam.x;
#ifdef RIBBON
    float across = 1 - abs(uv.y * 2 - 1);
    float shape = smoothstep(0, .75, across) * (1 - smoothstep(.45, 1, uv.x)) * smoothstep(0, .06, uv.x + .02);
    vec2 p = vec2(uv.x * 1.6, uv.y * .7);
#else
    vec2 p = uv;
#endif
    float smoke = texture(NoiseTex, p * .7 + vec2(seed, -seconds * .21)).r;
#ifndef RIBBON
    // A ragged blob rather than a disc: the smoke pushes its edge in and out.
    float shape = 1 - smoothstep(.05, .5, length(uv - .5) + (smoke - .5) * .4);
#endif
    float erode = texture(Sampler0, p * .9 + vec2(seed * 2.3, seconds * .5)).r;
    float left = vertexColor.a;
    float mask = shape * (.6 + .4 * smoke) - (1 - erode) * .5 - (1 - left) * .9;
    if (mask < .12) discard;
    vec3 violet = vertexColor.rgb;
    float edge = 1 - smoothstep(.12, .22, mask);
    vec2 screen = (gl_FragCoord.xy - ScreenSize * .5) / ScreenSize.y;
    float nebula = texture(AuraTex, screen * .8 + vec2(seconds * .008, 0)).r;
    vec3 color = vec3(.035, .005, .085) + violet * (nebula * .11 + smoke * .05) + stars(screen, seconds) * 1.6 + violet * edge * 1.5;
    fragColor = vec4(color, 1) * ColorModulator;
}

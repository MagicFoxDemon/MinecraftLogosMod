#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// A band of light rising round a colossal arcane charge (ArcaneChargeAura, vertex stage core/arcane_charge_aura):
// MagicLift's M_Vfx_UVFade_Pan_BlackBody. U runs up the band from its root on the ground, V across it. Two noises
// pan up it (Sampler0 = T_Vfx_Noise_cell_, DetailTex = T_Vfx_Noise_07, tiled and panned as the reference's dynamic
// parameters set them); their sum is burnt with a root term into a heat that is greatest at the root, and the
// heat's blackbody brightness (~ heat^4) lights it - deep, then the element's colour, then its light, then white,
// in place of the blackbody's hue-shifted reds. Faded by the reference's border mask along and across it.
// The vertex colour is the element's main colour (its deep colour for ARCANE_AURA_UNDERLAY, a blended pass under
// the glow); its alpha the particle's.
// auraParam.x is a per-band offset into the noises.
uniform sampler2D Sampler0;
uniform sampler2D DetailTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 auraParam;
out vec4 fragColor;

void main() {
    float seconds = GameTime * 1200.0;
    float u = clamp(texCoord0.x, 0.0, 1.0), v = clamp(texCoord0.y, 0.0, 1.0);
    vec2 seed = vec2(auraParam.x, fract(auraParam.x * 1.618));
    float n = texture(Sampler0, vec2(u * 0.5 - seconds * 1.2, v * 1.7 - seconds * 0.1) + seed).r;
    float d = texture(DetailTex, vec2(u * 0.75 - seconds * 1.0, v * 1.2 + seconds * 0.1) + seed.yx).r;
    // LinearBurn(pow(RootPOW / u, 0.1) + 1 - u, noise + detail), RootPOW = 0.1.
    float burn = n + d + pow(0.1 / max(u, 0.001), 0.1) - u;
    // BlackBody(burn x 1800 K): brightness (0.0004 T)^4.
    float heat = max(burn, 0.0) * 0.72;
    float bright = heat * heat * heat * heat;
    vec3 tint = vertexColor.rgb;
    // MF_MathBorderMask's shape, eased (the reference's HeadFadePOW 1.8, TailFadePOW 2, SideFadeControl 50) so the
    // band is bright from the feet to above the head and soft at its sides rather than cut off.
    float across = 4.0 * v * (1.0 - v);
    float border = pow(across, 1.5) * pow(1.0 - u, 0.9) * pow(u, 0.6) * 2.8;
    float alpha = clamp(burn, 0.0, 1.0) * clamp(border, 0.0, 1.0) * vertexColor.a;
#ifdef ARCANE_AURA_UNDERLAY
    // Blended under the glow in the deep colour (the vertex colour), so the band reads against a bright sky.
    fragColor = vec4(tint * (0.7 + 0.3 * n), clamp(alpha * 0.75, 0.0, 1.0)) * ColorModulator;
#else
    vec3 deep = tint * vec3(0.6, 0.44, 0.75), light = mix(tint, vec3(1.0), 0.78);
    vec3 color = mix(deep, tint, smoothstep(0.35, 0.75, heat));
    color = mix(color, light, smoothstep(1.0, 1.6, heat));
    // Unreal lights it far past 1 and lets bloom carry it; here the blackbody brightness is mapped into range.
    color = 1.0 - exp(-color * (0.8 + bright * 3.5));
    fragColor = vec4(color, clamp(alpha, 0.0, 1.0)) * ColorModulator;
#endif
    if (fragColor.a < 0.003) discard;
}

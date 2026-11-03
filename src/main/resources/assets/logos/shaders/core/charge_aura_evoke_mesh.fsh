#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The meshes of the colossal lightning and void auras (ChargeAuraEvoke, vertex stage core/charge_aura_evoke), drawn by
// ChargeAuraMesh, after NS_Charge_EvokeEnergy_Root's materials. Sampler0 is T_Vfx_Noise_01. The vertex colour is the
// tint, its alpha how much shows. One of:
//   SPIRAL  M_Vfx_UvFade_Pan (the spirals, on the mesh's second UV set): the noise panned at -0.75 a second, colour-
//           burnt by how much shows, so a fading spiral breaks up into the noise rather than turning thin, then to
//           the power 0.54.
//   RAY     M_Vfx_BrustRay (the cone mesh): the noise tiled twice round and panned up at 2 a second, to the power
//           0.5, times V^2 (bright at the wide top).
// and the element's accent:
//   ELECTRIC  the noise flickers to a new place a dozen times a second.
//   STARRY    the screen-space starfield of every void effect shows through (StarTex the end portal).
uniform sampler2D Sampler0;
#ifdef STARRY
uniform sampler2D StarTex;
#endif
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 auraParam;
in vec3 viewPosition;
out vec4 fragColor;

#ifdef STARRY
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
#endif

void main() {
    float seconds = GameTime * 1200;
    vec2 uv = texCoord0;
    vec3 color = vertexColor.rgb;
    vec2 jump = vec2(0);
#ifdef ELECTRIC
    float key = floor(seconds * 12);
    jump = vec2(fract(sin(key * 12.9898) * 43758.5453), fract(sin(key * 78.233) * 43758.5453)) * .15;
#endif
    float mask;
#if defined(SPIRAL)
    float noise = texture(Sampler0, uv * 2 + vec2(0, -.75 * seconds) + jump).r;
    float shows = vertexColor.a;
    mask = pow(clamp(1 - (1 - noise) / max(shows, .02), 0, 1), .54) * smoothstep(0, .25, shows);
    color = mix(color, min(color * 1.6 + .1, vec3(1)), mask * mask);
#else
    float noise = texture(Sampler0, uv * vec2(2, 1) + vec2(0, 2 * seconds) + jump).r;
    mask = pow(noise, .5) * uv.y * uv.y * vertexColor.a;
#endif
#ifdef STARRY
    vec2 screen = (gl_FragCoord.xy - ScreenSize * .5) / ScreenSize.y;
    color += stars(screen, seconds) * 1.3 * mask;
#endif
    if (mask < .003) discard;
    fragColor = vec4(color, clamp(mask, 0, 1)) * ColorModulator;
}

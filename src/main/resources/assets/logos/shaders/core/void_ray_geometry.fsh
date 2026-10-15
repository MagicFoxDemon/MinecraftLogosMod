#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// One fragment shader for every void ray layer, picked by a define:
//   VOID_RAY_BAND  the ray itself: a band of open void (screen-projected starfield) with a ragged violet rim.
//                  U runs along the ray in blocks, V across it; the colour's floor (its lowest channel) is how hot the band
//                  still is (a pale core burns down its middle while the burst lasts).
//   VOID_RAY_RIFT  the same void seen through a round tear where the ray struck; UV spans the disc.
//   VOID_RAY_GLOW  the burst's additive wisps around the band (U along in blocks, V across).
//   VOID_RAY_SPRITE an additive textured sprite (flare, glint, ring, head, motes).
uniform sampler2D Sampler0; // band and rift: silk noise; glow: wisps; sprite: its mask
uniform sampler2D StarTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

// Five layers of the end-portal sky fixed to the screen, each turned and drifting on its own, so the
// stars stay put while the band narrows across them.
vec3 stars(vec2 screen, float seconds) {
    vec3 field = vec3(0.0);
    for (int i = 0; i < 5; i++) {
        float layer = float(i + 1);
        float angle = layer * layer * 1.37 + 0.9;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = rotate * screen * (0.6 + layer * 0.25);
        uv += vec2(layer * 0.537, seconds * (0.0025 + layer * 0.0012));
        vec3 sampleColor = texture(StarTex, uv).rgb;
        float star = smoothstep(0.14, 0.78, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += star * mix(vec3(0.45, 0.18, 1.0), vec3(0.95, 0.86, 1.0), layer / 5.0);
    }
    return field;
}

vec3 interior(float seconds) {
    vec2 screen = (gl_FragCoord.xy - ScreenSize * 0.5) / ScreenSize.y;
    return vec3(0.03, 0.004, 0.075) + stars(screen, seconds) * 1.5;
}

void main() {
    float seconds = GameTime * 1200.0;
    vec2 uv = texCoord0;
    vec4 result;
#if defined(VOID_RAY_BAND)
    float across = abs(uv.y * 2.0 - 1.0);
    // The rim wavers: silk noise flowing along the ray eats into the edge.
    float silk = texture(Sampler0, vec2(uv.x * 0.35 - seconds * 0.6, uv.y * 0.5 + seconds * 0.15)).a;
    float edge = across + (silk - 0.5) * 0.22;
    float mask = 1.0 - smoothstep(0.78, 1.0, edge);
    float rim = smoothstep(0.42, 0.86, edge);
    float heat = min(vertexColor.r, min(vertexColor.g, vertexColor.b));
    vec3 color = mix(interior(seconds), vertexColor.rgb, rim * 0.9);
    // While the burst is hot a pale thread burns down the middle of the void.
    float core = exp(-pow(across / 0.3, 2.0)) * smoothstep(0.35, 0.78, heat);
    color = mix(color, vec3(0.93, 0.84, 1.0), clamp(core, 0.0, 1.0));
    result = vec4(color, mask * vertexColor.a);
#elif defined(VOID_RAY_RIFT)
    vec2 p = uv * 2.0 - 1.0;
    float r = length(p);
    float a = atan(p.y, p.x) / 6.2831853 + 0.5;
    float silk = texture(Sampler0, vec2(a * 2.0 + seconds * 0.05, r * 0.6 - seconds * 0.2)).a;
    float edge = r + (silk - 0.5) * 0.3;
    float mask = 1.0 - smoothstep(0.72, 0.95, edge);
    float rim = smoothstep(0.4, 0.82, edge);
    vec3 color = mix(interior(seconds), vertexColor.rgb, rim * 0.9);
    result = vec4(color, mask * vertexColor.a);
#elif defined(VOID_RAY_GLOW)
    float across = abs(uv.y * 2.0 - 1.0);
    float a = texture(Sampler0, vec2(uv.x * 0.18 - seconds * 2.2, uv.y)).a;
    float b = texture(Sampler0, vec2(uv.x * 0.11 - seconds * 1.4 + 0.37, 1.0 - uv.y)).a;
    float profile = 1.0 - smoothstep(0.2, 1.0, across);
    float wisps = clamp(a * 0.8 + b * 0.6, 0.0, 1.0);
    result = vec4(vertexColor.rgb, vertexColor.a * profile * (0.4 + 0.6 * wisps));
#else
    result = vec4(vertexColor.rgb, vertexColor.a * texture(Sampler0, uv).a);
#endif
    fragColor = result * ColorModulator;
    if (fragColor.a < 0.004) discard;
}

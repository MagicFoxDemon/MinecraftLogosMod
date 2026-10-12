#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

// Match the flight starfield's screen projection and independently drifting six layers.
vec3 stars(vec2 screen, float seconds) {
    vec3 field = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        float layer = float(i + 1);
        float angle = layer * layer * 1.73;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = rotate * screen * (0.55 + layer * 0.23);
        uv += vec2(layer * 0.713, seconds * (0.002 + layer * 0.001));
        vec3 sampleColor = texture(Sampler1, uv).rgb;
        float star = smoothstep(0.12, 0.75, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += star * mix(vec3(0.42, 0.16, 0.95), vec3(0.92, 0.78, 1.0), layer / 6.0) * 0.85;
    }
    return field;
}

void main() {
    float seconds = GameTime * 1200.0;
    vec2 screen = (gl_FragCoord.xy - ScreenSize * 0.5) / ScreenSize.y;
    vec2 centered = texCoord0 - 0.5;
    float radius = length(centered);
    float cloud = texture(Sampler0, texCoord0).a;
    float rim = smoothstep(0.16, 0.42, radius) * cloud;
    float mask = (1.0 - smoothstep(0.22 + cloud * 0.08, 0.49, radius));
    vec3 violet = vec3(0.604, 0.157, 1.0);
    vec3 interior = vec3(0.035, 0.005, 0.085) + stars(screen, seconds) * 1.6;
    vec3 color = mix(interior, violet * 1.2, clamp(rim * 0.8, 0.0, 0.85));
    vec4 result = vec4(color, mask * vertexColor.a * 0.94) * ColorModulator;
    if (result.a < 0.005) discard;
    float distance = -ProjMat[3].z / (gl_FragCoord.z * -2.0 + 1.0 - ProjMat[2].z);
    fragColor = apply_fog(result, distance, distance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

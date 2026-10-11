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
void main() {
    float seconds = GameTime * 1200.0;
    vec2 uv1 = texCoord0 * vec2(0.8, 1.0) - vec2(seconds * 2.4, 0.0);
    vec2 uv2 = texCoord0 - vec2(seconds * 2.6, 0.0);
    float intensity = min(1.0, texture(Sampler0, uv1).r + texture(Sampler0, uv2).r);
    float mask = texture(Sampler1, texCoord0).r;
    vec3 rgb = mix(vertexColor.rgb, vec3(1.0, 1.0, 0.96), pow(intensity, 4.0) * 0.5);
    vec4 color = vec4(rgb, vertexColor.a * intensity * mask) * ColorModulator;
    if (color.a < 0.005) discard;
    float distance = -ProjMat[3].z / (gl_FragCoord.z * -2.0 + 1.0 - ProjMat[2].z);
    fragColor = apply_fog(color, distance, distance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float seconds = GameTime * 1200.0;
#ifdef ARES_NOISE
    vec2 uv1 = texCoord0 * vec2(1.0, 1.5) - vec2(seconds * 2.0, 0.0);
    vec2 uv2 = texCoord0 * vec2(2.0, 2.0) - vec2(seconds * 2.5, 0.0);
    float opacity = 1.0;
#else
    vec2 uv1 = texCoord0 * vec2(0.4, 1.0) - vec2(seconds * 2.2, 0.0);
    vec2 uv2 = texCoord0 * vec2(0.7, 1.0) - vec2(seconds * 2.5, 0.0);
    float opacity = 1.0;
#endif
    float intensity = (texture(Sampler0, uv1).r + texture(Sampler0, uv2).r) * 0.5;
    float mask = texture(Sampler1, texCoord0).r;
    // The grayscale texture is an opacity mask. Multiplying RGB as well makes its soft edges dirty.
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * intensity * mask * opacity);
    color *= ColorModulator;
    if (color.a < 0.003) discard;
    float distance = -ProjMat[3].z / (gl_FragCoord.z * -2.0 + 1.0 - ProjMat[2].z);
    fragColor = apply_fog(color, distance, distance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

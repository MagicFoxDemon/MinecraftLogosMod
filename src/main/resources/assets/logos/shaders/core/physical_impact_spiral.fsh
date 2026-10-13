#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// Fragment stage of a Photon custom shader (vertex stage photon:particle); the material binds the textures.
uniform sampler2D SpiralNoise;
uniform sampler2D SpiralDistortion;
uniform sampler2D SpiralMask;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    vec2 p = (texCoord0 - 0.5) * 2.0;
    float radius = length(p);
    float angle = atan(p.y, p.x) / 6.2831853 + 0.5;
    // Freeze the polar texture coordinates so the ring flashes without rotating or flowing.
    float distortion = texture(SpiralDistortion, fract(vec2(angle, radius * 0.7))).r;
    vec2 uv = vec2(angle * 2.0 + radius * 0.45,
            (radius - 0.5) * 1.6 + distortion * 0.248);
    float noise = texture(SpiralNoise, fract(uv)).r;
    float radial = clamp((radius - 0.5) / 0.46, 0.0, 1.0);
    float mask = texture(SpiralMask, vec2(0.5, radial)).r;
    float annulus = smoothstep(0.5, 0.51, radius) * (1.0 - smoothstep(0.95, 0.96, radius));
    // Tight transition cuts the wispy glow into curved, pointed fragments.
    float detail = noise * mask;
    float alpha = smoothstep(0.25, 0.36, detail) * smoothstep(0.1, 0.85, detail)
            * annulus * vertexColor.a;
    float brightness = max(vertexColor.r, max(vertexColor.g, vertexColor.b));
    vec3 color = mix(vertexColor.rgb, vec3(brightness), smoothstep(0.4, 0.85, detail));
    vec4 result = vec4(color, alpha) * ColorModulator;
    if (result.a < 0.005) discard;
    fragColor = apply_fog(result, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

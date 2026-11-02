#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

// A living vine a lingering life vein grew (LifeLingeringVeinVisual), on the particle vertex stage: vertexColor is
// its colour already lit by the world. The texture is white with its shape and shading in alpha; here that alpha is
// both the cutout (so the vine is solid, not a veil) and how bright the surface is, so the stems keep their roundness.

uniform sampler2D Sampler0;

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a * vertexColor.a < 0.45) {
        discard;
    }
    vec3 color = vertexColor.rgb * tex.rgb * (0.4 + 0.6 * smoothstep(0.45, 1.0, tex.a));
    fragColor = apply_fog(vec4(color, 1.0) * ColorModulator, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

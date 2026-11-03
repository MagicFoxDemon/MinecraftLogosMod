#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The colossal arcane charge's aura (ArcaneChargeAura): the beacon-beam vertex stage, plus two numbers each quad
// hands its fragment stage through the second UV, in thousandths (a band's random offset; a fan's blade width
// and its blade count + falloff / 10).
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec4 vertexColor;
out vec2 texCoord0;
out vec2 auraParam;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    texCoord0 = UV0;
    auraParam = vec2(UV2) / 1000.0;
}

#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// The colossal lightning and void auras (ChargeAuraEvokeParts, VoidChargeAura): the beacon-beam vertex stage, plus
// two numbers each vertex hands its fragment stage through the second UV, in thousandths (what they mean is up to
// the fragment stage), and the view-space position for rims and the screen-space starfield.
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec4 vertexColor;
out vec2 texCoord0;
out vec2 auraParam;
out vec3 viewPosition;

void main() {
    viewPosition = (ModelViewMat * vec4(Position, 1.0)).xyz;
    gl_Position = ProjMat * vec4(viewPosition, 1.0);
    vertexColor = Color;
    texCoord0 = UV0;
    auraParam = vec2(UV2) / 1000.0;
}

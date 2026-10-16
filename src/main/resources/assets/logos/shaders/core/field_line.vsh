#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// A field's magic circle (FieldParts): the beacon-beam vertex stage, plus two numbers each circle hands its
// fragment stage through the second UV, in thousandths: how far out a pulse's light has swept (in circle
// radii; 3 or more for none) and how far the circle has dissolved (0 whole, 1 gone).
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec4 vertexColor;
out vec2 texCoord0;
out vec2 fieldParam;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    texCoord0 = UV0;
    fieldParam = vec2(UV2) / 1000.0;
}

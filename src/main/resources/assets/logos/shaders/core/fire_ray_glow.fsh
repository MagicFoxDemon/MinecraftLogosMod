#version 330
#moj_import <minecraft:dynamictransforms.glsl>
// A soft additive sprite (the shot's head): the mask's alpha, tinted by the vertex colour.
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float mask = texture(Sampler0, texCoord0).a;
    vec3 color = mix(vertexColor.rgb, vec3(1.0, .97, .85), smoothstep(.55, 1.0, mask));
    fragColor = vec4(color, mask * vertexColor.a) * ColorModulator;
    if (fragColor.a < .004) discard;
}

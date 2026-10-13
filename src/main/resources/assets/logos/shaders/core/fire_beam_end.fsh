#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float r = length((texCoord0 - .5) * 2.0);
    float core = 1.0 - smoothstep(.24, .50, r);
    float flare = texture(Sampler0, texCoord0).a;
    float alpha = max(core, flare * .8);
    vec3 color = mix(vec3(1, .66, .06), vec3(1, .98, .76), core);
    fragColor = vec4(color, alpha) * vertexColor * ColorModulator;
    if (fragColor.a < .005) discard;
}

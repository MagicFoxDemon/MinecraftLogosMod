#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float t = GameTime * 1200.0;
    vec2 uv = vec2(texCoord0.x - t * .7, texCoord0.y - t * .4);
    float energy = texture(Sampler0, uv).r;
    float fade = smoothstep(0, .15, texCoord0.y) * (1 - smoothstep(.5, 1, texCoord0.y));
    vec3 color = mix(vec3(1, .12, 0), vec3(1, .62, .02), energy);
    fragColor = vec4(color, energy * fade * .6) * vertexColor * ColorModulator;
    if (fragColor.a < .005) discard;
}

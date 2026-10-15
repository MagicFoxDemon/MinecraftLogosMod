#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float mask = texture(Sampler0, texCoord0).a;
#ifdef RAY_LINE
    // A bolt: white-hot down the middle, the vertex colour toward the edges.
    float core = smoothstep(.45, .95, mask);
    vec3 color = mix(vertexColor.rgb, vec3(1), core * .9);
    float alpha = min(1, mask * 1.6) * vertexColor.a;
#else
    vec3 color = vertexColor.rgb;
    float alpha = mask * vertexColor.a;
#endif
    fragColor = vec4(color, alpha) * ColorModulator;
    if (fragColor.a < .004) discard;
}

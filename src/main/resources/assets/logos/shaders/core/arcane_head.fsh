#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float mask = texture(Sampler0, texCoord0).a;
    // One layer owns both the bright center and colored edge, independent of translucent batch order.
    vec3 rgb = mix(vertexColor.rgb, vec3(1.0, 0.88, 1.0), smoothstep(0.4, 0.9, mask));
    vec4 color = vec4(rgb, vertexColor.a * mask) * ColorModulator;
    if (color.a < 0.005) discard;
    float distance = -ProjMat[3].z / (gl_FragCoord.z * -2.0 + 1.0 - ProjMat[2].z);
    fragColor = apply_fog(color, distance, distance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

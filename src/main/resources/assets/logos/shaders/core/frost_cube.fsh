#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float seconds = GameTime * 1200.0;
    vec2 flow = vec2(seconds * 0.3, seconds * 0.49);
    vec2 distortion = (texture(Sampler1, texCoord0 + flow).rg - 0.5) * 0.134;
    float edge = texture(Sampler0, clamp(texCoord0 + distortion, 0.0, 1.0)).r;
    vec3 color = mix(vertexColor.rgb * 0.7, vec3(0.75, 0.96, 1.0), pow(edge, 3.0));
    vec4 result = vec4(color, 0.65 + 0.3 * edge) * ColorModulator;
    float distance = -ProjMat[3].z / (gl_FragCoord.z * -2.0 + 1.0 - ProjMat[2].z);
    fragColor = apply_fog(result, distance, distance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
// The colossal life charge's wind streaks (LifeChargeAura), after Unreal's M_Vfx_FocusBelt: a translucent spiral
// mesh whose noise (Sampler0, charge_aura/noise_disflow_07, red channel) is panned along it.
//   opacity  = vertex alpha * 4 * noise ^ 0.8         (AlphaPOW; the vertex alpha already holds the travelling
//                                                      radial gradient, the side fade and the curves, over 4)
//   emission = vertex colour * noise ^ ColorPOW       (ColorPOW grows over a streak's life: whole at first,
//                                                      only the bright veins at the end)
// ColorPOW comes in the whole part of U: U = 8 * round(ColorPOW * 4) + the noise's own U (under 8), so with the
// texture repeating the noise is sampled as it is. Unreal's emission is far over 1 and over-saturated
// (Satueate -20): here the colour is pushed away from grey and its hottest veins pale towards white, the way
// the HDR ones bloom. Blended premultiplied: the background is partly covered (green on a bright sky) and the
// light added, so strands crossing each other glow brighter.
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float colorPow = floor(texCoord0.x / 8.0) * 0.25;
    float n = texture(Sampler0, texCoord0).r;
    float cover = clamp(vertexColor.a * 4.0 * pow(n, 0.8), 0.0, 1.0);
    if (cover < 0.004) discard;
    vec3 tint = vertexColor.rgb;
    vec3 vivid = clamp(tint + 0.6 * (tint - dot(tint, vec3(0.3, 0.59, 0.11))), 0.0, 1.0);
    float hot = pow(n, colorPow);
    vec3 col = mix(vivid, vec3(0.9, 1.0, 0.82) * max(tint.g, 0.2), smoothstep(0.3, 1.0, hot) * 0.7);
    vec4 color = vec4(col * cover * 1.3, cover * 0.65) * ColorModulator;
    float distance = -ProjMat[3].z / (gl_FragCoord.z * -2.0 + 1.0 - ProjMat[2].z);
    fragColor = apply_fog(color, distance, distance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

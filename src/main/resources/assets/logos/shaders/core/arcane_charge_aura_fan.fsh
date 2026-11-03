#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// A fan of rays flashing as a colossal arcane charge is let go (ArcaneChargeAura, vertex stage
// core/arcane_charge_aura): MagicLift's M_Vfx_Math_Fan. Round the quad's centre, blades where the fraction of
// (angle x blades) is past the width (auraParam.x, rising from 0.2 to 1 as the fan closes), times a radial falloff
// (1 - r)^density; the integer part of auraParam.y is the blade count, ten times its fraction the falloff.
// Sampler0 (T_Vfx_Noise_cell_) breaks the blades up a little. The vertex colour tints it; its alpha is how much shows.
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 auraParam;
out vec4 fragColor;

void main() {
    vec2 d = texCoord0 - 0.5;
    float r = length(d) * 2.0;
    if (r >= 1.0) discard;
    float blades = max(floor(auraParam.y), 1.0), density = fract(auraParam.y) * 10.0;
    float angle = atan(d.y, d.x) / 6.2831853 + 0.5;
    float f = fract(angle * blades);
    float blade = smoothstep(auraParam.x - 0.015, auraParam.x + 0.015, f);
    float radial = pow(1.0 - r, max(density, 0.5));
    float grain = 0.8 + 0.4 * texture(Sampler0, texCoord0 * 1.3).r;
    float alpha = blade * radial * grain * vertexColor.a;
    fragColor = vec4(mix(vertexColor.rgb, vec3(1.0), radial * 0.35), clamp(alpha, 0.0, 1.0)) * ColorModulator;
    if (fragColor.a < 0.003) discard;
}

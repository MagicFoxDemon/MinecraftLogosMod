#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The colossal fire charge's aura shells and spiral bands (FireChargeAura), after the Unreal material
// M_Vfx_FightAura of NS_Charge_AuraBrust_Root: a noise texture (R) to the power ALPHA_POW times a mask texture (R),
// both panning up the mesh (V) at 1 and 1.2 UV a second, the noise squeezed NOISE_V times in V. texCoord0 is
// the mesh UV with the particle's own U / V offset. The vertex alpha carries everything else the material
// multiplies in (the fresnel rim, the fade towards the top, the U ramp of the bands and the particle's alpha),
// divided by GAIN so it fits; the vertex colour is the tint. As in Unreal, where the emissive is the colour times
// ColorBoost (10) over that opacity, a thin flame already shows bright; nearly twice that again here, standing in
// for the bloom and the many more shells Unreal draws. Additive.
//   MASK        - sample the mask (Sampler0); without it the mask is white
//   HARD_EDGE   - the hard-edged opacity of MI_Vfx_FightAura_Flip_Mask: on where the product passes .05
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float seconds = GameTime * 1200.0;
    vec2 uv = texCoord0;
    float noise = pow(texture(NoiseTex, vec2(uv.x, uv.y * NOISE_V + seconds)).r, ALPHA_POW);
#ifdef MASK
    float mask = texture(Sampler0, vec2(uv.x, uv.y + seconds * 1.2)).r;
#else
    float mask = 1.0;
#endif
    float e = vertexColor.a * GAIN * noise * mask;
    float glow = e * 18.0;
#ifdef HARD_EDGE
    float alpha = step(.05, e) * .85;
#else
    float alpha = clamp(glow, 0.0, 1.0);
#endif
    // Fire's heat: where the flame is densest it burns towards white-gold.
    vec3 color = mix(vertexColor.rgb, vec3(1.0, .95, .82), smoothstep(3.0, 14.0, glow) * .7);
    fragColor = vec4(color, alpha) * ColorModulator;
    if (fragColor.a < .004) discard;
}

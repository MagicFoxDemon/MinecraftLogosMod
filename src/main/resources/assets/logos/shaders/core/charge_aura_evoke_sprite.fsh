#version 330
#moj_import <minecraft:dynamictransforms.glsl>
// The quads of the colossal lightning and void auras (ChargeAuraEvoke, vertex stage core/charge_aura_evoke): sparks,
// the glow and circle at the feet, the release ring and streaks, lightning's dark smoke. Sampler0 is one of the
// Niagara masks copied to charge_aura/, its red channel the mask (their alpha is not); the vertex colour is the tint,
// its alpha how much shows. Additive or alpha-blended by the pipeline.
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 auraParam;
in vec3 viewPosition;
out vec4 fragColor;

void main() {
    float alpha = texture(Sampler0, texCoord0).r * vertexColor.a;
    if (alpha < .003) discard;
    fragColor = vec4(vertexColor.rgb, alpha) * ColorModulator;
}

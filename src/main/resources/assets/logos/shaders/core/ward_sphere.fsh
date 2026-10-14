#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// HoneyShield's LiquidSphere material, without the honey: a fresnel rim over a slowly flowing honeycomb.
// The vertex colour carries data, not colour (see WardSphereVisual): r = rim (1 - |n.v|), g = height on the
// sphere (0 at the bottom), b = how far it has formed, a = overall alpha.
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

const vec3 TINT = vec3(.86, .9, .96);

void main() {
#ifdef WARD_RIBBON
    // A spiral band of the forming sphere: u runs tail to head, v across the band.
    float across = 1.0 - abs(texCoord0.y * 2.0 - 1.0);
    float glow = across * across * smoothstep(0.0, 0.7, texCoord0.x) * (1.0 - smoothstep(0.92, 1.0, texCoord0.x));
    fragColor = vec4(mix(TINT, vec3(1), glow), glow * vertexColor.a) * ColorModulator;
#else
    // Whole cycles per day of game time, so GameTime wrapping at dawn never jumps the pattern.
    vec2 flow = vec2(fract(GameTime * 24.0), fract(GameTime * -12.0));
    float comb = texture(Sampler0, texCoord0 * vec2(4.0, 2.0) + flow).r;
    float noise = texture(NoiseTex, texCoord0 * vec2(2.0, 1.0) + vec2(fract(GameTime * -36.0), fract(GameTime * 60.0))).r;
    float edge = smoothstep(.62, .9, comb);
    // Cells twinkle where the drifting noise crosses their own grey level.
    float cell = smoothstep(.25, .0, abs(comb - noise)) * (1.0 - edge) * step(.12, comb);
    // The honeycomb pinches at the poles; let it thin out there and leave them to the rim.
    float poles = smoothstep(.04, .3, sin(texCoord0.y * 3.14159265));
    float rim = clamp(2.0 * pow(vertexColor.r, 3.0), 0.0, 1.0);
    float front = vertexColor.b * 1.3 - .15 + (noise - .5) * .2;
    float formed = smoothstep(vertexColor.g - .06, vertexColor.g, front);
    float seam = smoothstep(.1, 0.0, abs(front - vertexColor.g)) * step(vertexColor.b, .99);
    float lattice = (edge * (.18 + .5 * noise) + cell * .22) * poles;
    // The far side is seen through the near side, so it stays fainter.
    float side = gl_FrontFacing ? 1.0 : .55;
    float alpha = (.05 + rim * .75 + lattice) * side * formed + seam * .8;
    vec3 color = mix(TINT, vec3(1), clamp(rim + edge * .6 + seam, 0.0, 1.0));
    fragColor = vec4(color, clamp(alpha, 0.0, 1.0) * vertexColor.a) * ColorModulator;
#endif
    if (fragColor.a < .004) discard;
}

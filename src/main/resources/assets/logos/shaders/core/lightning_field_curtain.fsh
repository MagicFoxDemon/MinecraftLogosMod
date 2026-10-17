#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// Thin upright arcs flashing at random on the lightning field's edge curtain (LightningFieldVisual). Drawn on
// a cylinder whose U counts columns round the edge (one arc slot per column, each about a block wide) and
// whose V runs from 0 at the top to 1 at the ground. Each column, on its own beat, may strike: a jagged
// line wavering up from the ground to a random height, white-hot in the core with a soft glow, flickering
// while it lasts. Sampler0 is a tileable noise (lightning_beam/noise) that bends the line. The vertex
// colour is the glow's tint; its alpha is how much shows, and above 0.5 also how many columns strike
// (the curtain flares on a pulse).
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

float hash(float n) { return fract(sin(n * 12.9898 + 78.233) * 43758.5453); }

void main() {
    float seconds = GameTime * 1200;
    float col = floor(texCoord0.x);
    float x = fract(texCoord0.x);
    float up = 1 - texCoord0.y;
    // Each column's beat: a strike lasts a beat, columns out of step with each other.
    float rate = 6 + 5 * hash(col + 4.2);
    float beat = floor(seconds * rate + hash(col) * 13);
    float surge = clamp(vertexColor.a * 2 - 1, 0, 1);
    float strikes = step(.8 - .45 * surge, hash(col * 7.31 + beat * 1.93));
    if (strikes < .5) discard;
    float seed = hash(col * 3.7 + beat * .61);
    float top = .45 + .55 * hash(seed * 91 + 2);
    if (up > top) discard;
    // The line: a random centre bent by two octaves of the noise, new shape every beat.
    float bend = (texture(Sampler0, vec2(seed * 7 + col * .13, up * .9)).g - .5) * .5
            + (texture(Sampler0, vec2(seed * 3 + .5, up * 3.1 + seed)).g - .5) * .22;
    float cx = .5 + (seed - .5) * .3 + bend;
    float dx = abs(x - cx);
    float core = exp(-pow(dx / .018, 2));
    float glow = exp(-pow(dx / .09, 2)) * .35;
    // Fades out towards its tip and flickers while it lasts.
    float tip = 1 - smoothstep(top * .55, top, up);
    float flicker = .55 + .45 * step(.4, hash(floor(seconds * 40) + col));
    float k = (core + glow) * tip * flicker * min(1, vertexColor.a * 2);
    vec3 color = mix(vertexColor.rgb, vec3(.85, .97, 1), core);
    fragColor = vec4(color * (1 + core), clamp(k, 0, 1)) * ColorModulator;
    if (fragColor.a < .004) discard;
}

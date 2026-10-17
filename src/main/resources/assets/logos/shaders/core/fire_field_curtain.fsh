#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The fire field's edge (FireFieldVisual): a low see-through curtain of flame licking upwards. Drawn with the
// field's line material (vertex stage core/field_line); Sampler0 and FillTex are both tileable noise in alpha
// (fire_ray/noise). U runs round the field (about one unit per two blocks), V from 0 at the ground to 1 at
// the top of the strip. Noise rising through it, swaying more the higher it gets, eats a ragged line of
// tongues out of the top; it is hottest (gold) at the foot and cools to dark red at the tips.
// fieldParam.x is how much the curtain flares (a pulse): the tongues leap higher and burn brighter.
// The vertex colour's alpha is how much shows.
uniform sampler2D Sampler0;
uniform sampler2D FillTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

vec3 heat(float h) {
    vec3 c = mix(vec3(.30, .02, .0), vec3(.92, .2, .02), smoothstep(.0, .35, h));
    c = mix(c, vec3(1.0, .56, .1), smoothstep(.3, .7, h));
    return mix(c, vec3(1.0, .9, .66), smoothstep(.75, 1.0, h));
}

void main() {
    float seconds = GameTime * 1200;
    float u = texCoord0.x, v = texCoord0.y;
    float flare = fieldParam.x;
    // Swaying more the higher it gets: the heat bending the flames.
    float sway = texture(FillTex, vec2(u * .35 + seconds * .03, v * .3 - seconds * .25)).a;
    float uu = u + (sway - .5) * .45 * v;
    float n1 = texture(Sampler0, vec2(uu * .9, v * .55 - seconds * .55)).a;
    float n2 = texture(FillTex, vec2(uu * 2.3 + .37, v * 1.3 - seconds * 1.25)).a;
    // Tall at a few places round the field, low between them.
    float reach = .45 + .55 * texture(FillTex, vec2(u * .18 - seconds * .015, .5)).a + flare * .35;
    float body = (1 - v / reach) * 1.25 + (n1 - .5) * .7 + (n2 - .5) * .55;
    float flame = smoothstep(.4, .7, body);
    if (flame < .004) discard;
    float h = clamp(body * .7 - .1 + flare * .25, 0, .9);
    float foot = smoothstep(0, .05, v);
    float alpha = flame * foot * (.3 + .7 * h) * (1 - v * .45) * (1 + flare * .8);
    fragColor = vec4(heat(h) * mix(vec3(1), vertexColor.rgb * 1.3, .15), clamp(alpha * vertexColor.a, 0, 1)) * ColorModulator;
}

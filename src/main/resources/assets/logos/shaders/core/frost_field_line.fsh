#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The frost field's magic-circle lines (FrostFieldVisual): ice. Sampler0 is the circle (its alpha is where the
// lines are); FillTex is frost/winter_trail, a tileable net of bright crack-like ridges between soft cells.
// Inside the lines: cold mist drifting slowly round and outwards, and over it a fixed net of hairline cracks
// that catch the light as a slow refracting glint runs across them, with a few frost points twinkling.
// fieldParam.x is how far out a pulse's light has swept (circle radii, 3 or more for none): the ice flashes
// white as it passes. fieldParam.y is how far the circle has dissolved: it shatters, the cells melting out
// first and the cracks last, gone at 1. Same inputs and outputs as field_line.fsh.
uniform sampler2D Sampler0;
uniform sampler2D FillTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float mask = texture(Sampler0, texCoord0).a;
    if (mask < .01) discard;
    float seconds = GameTime * 1200;
    vec2 d = texCoord0 - .5;
    float r = length(d) * 2;
    float a = atan(d.y, d.x) / 6.2831853;

    // Cold mist: two soft layers drifting slowly round the circle and creeping outwards.
    float m1 = texture(FillTex, vec2(a * 3 + seconds * .012, r * 1.2 - seconds * .025)).r;
    float m2 = texture(FillTex, texCoord0 * 1.3 + vec2(seconds * .011, -seconds * .008)).r;
    float mist = smoothstep(.05, .7, m1 * .55 + m2 * .45);

    // The cracks: a fixed net in the ice, sharpened from the fill's ridges.
    float c = texture(FillTex, texCoord0 * 2.6).r;
    float c2 = texture(FillTex, texCoord0 * 5.3 + .37).r;
    float crack = smoothstep(.5, .85, c) + smoothstep(.62, .9, c2) * .5;

    // A slow glint running across the ice, bent by the cracks as if refracted.
    float phase = dot(texCoord0, vec2(.78, .62)) * 9 - seconds * .35 + c * 2.5;
    float glint = pow(.5 + .5 * sin(phase * 6.2831853), 12);
    float shimmer = .55 + .45 * sin(seconds * 1.7 + c2 * 9);

    // Frost points twinkling here and there.
    vec2 cell = floor(texCoord0 * 56);
    float h = hash(cell);
    float blink = pow(max(0, sin(seconds * (1.2 + h * 1.8) + h * 40)), 24);
    float dot2 = 1 - smoothstep(.05, .32, length(fract(texCoord0 * 56) - .5));
    float sparkle = step(.82, h) * blink * dot2;

    // Shattering: the cells' insides melt out first, the cracks last.
    if (fieldParam.y > 0) {
        float shard = texture(FillTex, texCoord0 * 1.4 + .21).r * .7 + c * .3;
        if (shard < fieldParam.y * 1.15 - .08) discard;
    }

    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .07, 2)) * 1.6 + smoothstep(sweep - .35, sweep, r) * step(r, sweep) * .35;

    vec3 ice = vec3(.82, .94, 1.0);
    vec3 body = vertexColor.rgb * (.3 + .65 * mist);
    vec3 color = body + ice * crack * (.12 + 1.5 * glint * shimmer) + ice * sparkle * 1.6;
    color += (vertexColor.rgb * .6 + ice * .7) * lit * (.6 + .6 * crack);
    float alpha = mask * vertexColor.a * (.8 + .4 * crack);
    fragColor = vec4(color, clamp(alpha, 0, 1)) * ColorModulator;
}

#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The frost field's edge (FrostFieldVisual): a see-through curtain of ice mist, drawn with the field-line
// vertex stage. Sampler0 is frost/winter_trail (crack-like ridges between soft cells), FillTex frost/cube_distortion
// (soft noise), both tileable. texCoord0.x runs round the edge (one unit per 2.5 blocks), texCoord0.y from the
// ground (0) to the top (1). fieldParam.x is the curtain's height in blocks, fieldParam.y how much a pulse
// lights it (0 to 1). The vertex colour is the tint; its alpha how much shows.
// From the bottom up: a jagged crust of frost on the ground with cracks in it, then cold mist rising slowly
// and thinning out, with a few ice glints drifting up in it.
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
    float seconds = GameTime * 1200;
    float height = max(fieldParam.x, .1);
    float flare = fieldParam.y;
    float u = texCoord0.x;
    float v = clamp(texCoord0.y, 0, 1);
    float y = v * height;

    // Frost on the ground: a crust with a jagged top, cracked.
    float edge = .1 + .22 * texture(FillTex, vec2(u * .9, .31)).r * texture(FillTex, vec2(u * 2.7, .77)).r * 1.8;
    float crust = 1 - smoothstep(edge * .35, edge, y);
    float crack = smoothstep(.45, .85, texture(Sampler0, vec2(u * 1.6, y * 1.6)).r);
    float frost = crust * (.18 + .55 * crack);

    // Cold mist rising slowly, in two layers, thinning out towards the top.
    float n1 = texture(FillTex, vec2(u * .55 + seconds * .006, y * .35 - seconds * .07)).r;
    float n2 = texture(FillTex, vec2(u * 1.15 - seconds * .011, y * .7 - seconds * .12)).r;
    float wisp = texture(Sampler0, vec2(u * .4 + n1 * .3, y * .3 - seconds * .05)).r;
    float mist = smoothstep(.25, .95, n1 * n2 * 1.7) * (.6 + .6 * wisp);
    mist *= pow(1 - v, 1.4) * smoothstep(0, .08, y);

    // Ice glints drifting up, each twinkling once in a while.
    vec2 g = vec2(u * 5, y * 3 - seconds * .25);
    vec2 cell = floor(g);
    float h = hash(cell);
    vec2 at = vec2(.25 + .5 * hash(cell + 7.1), .25 + .5 * hash(cell + 3.7));
    float blink = pow(max(0, sin(seconds * (.9 + h * 1.5) + h * 50)), 16);
    float glint = step(.86, h) * blink * (1 - smoothstep(.0, .09, length(fract(g) - at))) * (1 - v);

    vec3 ice = vec3(.85, .95, 1.0);
    vec3 color = vertexColor.rgb * mist * (1 + flare * 1.5) + mix(vertexColor.rgb, ice, .65) * frost * (1 + flare)
            + ice * glint * 2;
    float alpha = (mist * .9 + frost * .5 + glint) * vertexColor.a;
    fragColor = vec4(color, clamp(alpha, 0, 1)) * ColorModulator;
}

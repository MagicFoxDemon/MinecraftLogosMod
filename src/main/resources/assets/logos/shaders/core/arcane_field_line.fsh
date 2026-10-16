#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The arcane field's magic-circle lines (ArcaneFieldVisual, paired with core/field_line). Same inputs as
// field_line.fsh: Sampler0 is the circle (its alpha is where the lines are), FillTex the arcane silk
// (arcane_ray/silk, its alpha the pattern), fieldParam.x how far out a pulse's light has swept (circle radii),
// fieldParam.y how far the circle has dissolved (0 whole, 1 gone), the vertex colour the tint.
//
// Inside the lines: soft strands of mana (the silk, in the circle's polar coordinates) drifting round and
// outwards in pink and violet, bright threads running round like comets, and a dust of small stars
// twinkling as it is carried round. The brightest stars spill a short four-pointed glint past the line's edge.
uniform sampler2D Sampler0;
uniform sampler2D FillTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

vec2 hash2(vec2 p) {
    return fract(sin(vec2(dot(p, vec2(127.1, 311.7)), dot(p, vec2(269.5, 183.3)))) * 43758.5453);
}

// One star in some of the cells of a grid `cells` across, each twinkling at its own pace. x is the soft core,
// y the four-pointed glint round it (its arms along the grid).
vec2 stars(vec2 p, float cells, float t, float density) {
    vec2 g = p * cells, id = floor(g), f = fract(g);
    vec2 sum = vec2(0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(x, y), h = hash2(id + o);
            if (h.x > density) continue;
            vec2 d = o + .15 + h * .7 - f;
            float tw = pow(.5 + .5 * sin(t * (1.3 + 2.4 * h.y) + h.x * 41.0), 5.0);
            float core = exp(-dot(d, d) * 90.0);
            float arms = exp(-abs(d.x) * 18.0 - d.y * d.y * 900.0) + exp(-abs(d.y) * 18.0 - d.x * d.x * 900.0);
            sum += vec2(core, arms) * tw;
        }
    }
    return sum;
}

void main() {
    vec4 circle = texture(Sampler0, texCoord0);
    float mask = circle.a;
    float seconds = GameTime * 1200;
    vec2 d = texCoord0 - .5;
    float r = length(d) * 2;
    float a = atan(d.y, d.x) / 6.2831853;

    // The stars ride round the circle, slower than the mana.
    float turn = seconds * .045;
    mat2 rot = mat2(cos(turn), sin(turn), -sin(turn), cos(turn));
    vec2 sp = rot * d;
    vec2 star = stars(sp, 34.0, seconds, .55) + stars(sp * 1.7 + 3.1, 34.0, seconds * 1.4, .35) * .7;

    // Only fragments on a line, or close enough to one to catch a star's glint, are drawn.
    float near = mask;
    if (mask < .02) {
        if (star.y < .05) discard;
        float o = .012;
        near = max(max(texture(Sampler0, texCoord0 + vec2(o, 0)).a, texture(Sampler0, texCoord0 - vec2(o, 0)).a),
                   max(texture(Sampler0, texCoord0 + vec2(0, o)).a, texture(Sampler0, texCoord0 - vec2(0, o)).a));
        if (near < .1) discard;
    }

    // Two strands of silk drifting round at their own speeds and creeping outwards.
    float s1 = texture(FillTex, vec2(a * 5 + seconds * .025, r * 2.2 - seconds * .05)).a;
    float s2 = texture(FillTex, vec2(a * 9 - seconds * .04, r * 3.6 - seconds * .08)).a;
    float silk = s1 * .6 + s2 * .4;
    // Bright threads running round the circle.
    float thread = pow(.5 + .5 * sin((a * 4 + r * 1.5) * 6.2831853 - seconds * 1.7), 14.0)
            + pow(.5 + .5 * sin((a * 3 - r * 2.0) * 6.2831853 + seconds * 1.1), 18.0) * .7;

    // The lines break up into the silk's pattern as the circle dissolves, edged with a hot glow.
    float cut = fieldParam.y * 1.1 - .05;
    if (fieldParam.y > 0 && silk < cut) discard;
    float edge = fieldParam.y > 0 ? smoothstep(cut + .12, cut, silk) : 0;

    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .07, 2)) * 1.25 + smoothstep(sweep - .35, sweep, r) * step(r, sweep) * .3;

    vec3 pink = vec3(1.0, .52, .93), white = vec3(1.0, .95, 1.0);
    vec3 tint = mix(vertexColor.rgb, pink, smoothstep(.35, .85, silk) * .6);
    float body = .3 + .6 * silk + .5 * thread;
    vec3 color = tint * (body + lit) + white * (lit * .3 + thread * .18 + edge * 1.2);
    // Stars: white cores, glints faintly pink.
    float sparkle = star.x * 2.2 + star.y * .9;
    color += mix(pink, white, .7) * sparkle;

    float alpha = mask * vertexColor.a;
    // Past the line's edge only the glint shows, fading with distance from the line.
    if (mask < .02) {
        alpha = clamp(star.y * near * .8, 0, 1) * vertexColor.a;
        color = mix(pink, white, .6) * 1.4;
    }
    fragColor = vec4(color, clamp(alpha, 0, 1)) * ColorModulator;
}

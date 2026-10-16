#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The life field's magic-circle lines (LifeFieldVisual), paired with core/field_line like field_line.fsh and
// with the same inputs. Sampler0 is the circle: its alpha is where the lines are. FillTex is life_ray/vines
// (its alpha the vines): two strands of it creep round the circle in its polar coordinates, and a slow
// front of growth runs round behind them, so green light seems to grow along the lines. Golden motes of
// light drift along inside them. fieldParam.x is how far out a pulse's light has swept (circle radii; the
// lines light up as it passes, warm), fieldParam.y how far the circle has dissolved (it withers into the
// vines' pattern and is gone at 1). The vertex colour is the tint; its alpha how much shows.
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
    float a = atan(d.y, d.x) / 6.2831853 + .5;
    // Two strands of vine creeping round at different speeds, wandering across their band as they go.
    float v1 = texture(FillTex, vec2(a * 8 - seconds * .045, .5 + .2 * sin(r * 18 + a * 6.2831853 * 2))).a;
    float v2 = texture(FillTex, vec2(a * 13 + seconds * .03 + .37, .5 + .2 * sin(r * 27 - seconds * .4))).a;
    float vine = max(v1, v2 * .8);
    // Growth: a few fronts running slowly round and outwards; just behind each the vines are fresh and bright.
    float g = fract(a * 3 + r * .6 - seconds * .07);
    float grow = pow(g, 3) * .9 + smoothstep(.96, 1., g) * -.9;
    float flow = vine * .7 + grow * .3;
    // The lines wither into the vines' pattern as the circle dissolves.
    if (fieldParam.y > 0 && flow + hash(floor(texCoord0 * 96)) * .15 < fieldParam.y * 1.15 - .05) discard;
    // Golden motes drifting round inside the lines.
    vec2 cell = vec2(a * 90 - seconds * .35, r * 40);
    vec2 id = floor(cell);
    float h = hash(id);
    float spot = h > .8 ? smoothstep(.45, .04, length(fract(cell) - .5 - (vec2(hash(id + 3.1), hash(id + 7.7)) - .5) * .4)) : 0;
    float twinkle = .55 + .45 * sin(seconds * 3 + h * 40);
    float gold = spot * twinkle;
    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .07, 2)) * 1.6 + smoothstep(sweep - .35, sweep, r) * step(r, sweep) * .35;
    // Kept green rather than white: the tint pushed towards leaf green, the light of a pulse warm and soft.
    float body = .3 + .55 * vine + .25 * grow;
    vec3 leaf = vertexColor.rgb * vec3(.78, 1., .7);
    vec3 color = leaf * body + vec3(1., .84, .36) * (gold * 1.5 + lit * .18) + leaf * lit * .6;
    fragColor = vec4(color, clamp(mask * vertexColor.a * (.75 + .25 * vine + gold * .4), 0, 1)) * ColorModulator;
}

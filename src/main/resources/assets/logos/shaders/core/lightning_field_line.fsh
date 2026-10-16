#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The lightning field's magic-circle lines (LightningFieldVisual, paired with core/field_line): current
// crackling through them. Sampler0 is the circle (its alpha is where the lines are); FillTex is a tileable
// cell-edge noise (lightning/trail_noise) whose bright web becomes the arcs. Over a dim steady glow in the
// tint, three things play: a web of fine arcs that jumps to a new place many times a second (the crackle),
// sparks racing round the lines, and whole stretches of line flaring and dying out at random. fieldParam.x is
// how far out a pulse's light has swept (circle radii, 3 or more for none): the lines light up as it passes,
// with a burst of arcs. fieldParam.y how far the circle has dissolved (it breaks up and is gone at 1).
uniform sampler2D Sampler0;
uniform sampler2D FillTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

float hash(float n) { return fract(sin(n * 12.9898 + 78.233) * 43758.5453); }

void main() {
    float mask = texture(Sampler0, texCoord0).a;
    if (mask < .01) discard;
    float seconds = GameTime * 1200;
    vec2 d = texCoord0 - .5;
    float r = length(d) * 2;
    float a = atan(d.y, d.x) / 6.2831853;
    // A slow, smooth drift of the web for the steady glow (and what the circle breaks up along).
    float flow = texture(FillTex, vec2(a * 4 + seconds * .02, r * 2 - seconds * .03)).r;
    if (fieldParam.y > 0 && flow + .25 < fieldParam.y * 1.3) discard;
    // The crackle: the web jumps to a fresh place about 15 times a second, two layers out of step.
    float t1 = floor(seconds * 15), t2 = floor(seconds * 11 + .5);
    float w1 = texture(FillTex, vec2(a * 5 + hash(t1), r * 2.4 + hash(t1 + 7.1))).r;
    float w2 = texture(FillTex, vec2(a * 9 + hash(t2 + 3.3), r * 3.6 + hash(t2 + 9.7))).r;
    // Sparks racing round, each stretch its own way, flickering.
    float lane = floor(r * 9);
    float dir = hash(lane) < .5 ? -1 : 1;
    float run = fract(a * 7 + dir * seconds * (.5 + .4 * hash(lane + 2)) + hash(lane + 5));
    float spark = pow(run, 14) * step(.35, hash(lane * 3 + t1));
    // Whole stretches of line flaring and dying out.
    float sector = floor(a * 28 + r * 3);
    float flare = step(.86, hash(sector * 1.7 + t2)) * (.6 + .4 * hash(sector + t1));
    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .07, 2)) * 1.6 + smoothstep(sweep - .35, sweep, r) * step(r, sweep) * .35;
    // More arcs (and whiter) where the pulse's light is passing.
    float lo = .62 - .2 * min(lit, 1);
    float arcs = smoothstep(lo, lo + .22, w1) + .7 * smoothstep(lo + .05, lo + .27, w2);
    float bolt = clamp(arcs * (.55 + .45 * hash(t1 + floor(a * 12))) + spark * 1.4 + flare * .7, 0, 2);
    float body = .2 + .3 * flow;
    vec3 arcColor = vec3(.78, .95, 1);
    vec3 color = vertexColor.rgb * (body + lit * .5) + arcColor * (bolt * 1.2 + lit * .3);
    float alpha = mask * vertexColor.a * (.45 + .8 * bolt + .3 * min(lit, 1));
    fragColor = vec4(color, clamp(alpha, 0, 1)) * ColorModulator;
}

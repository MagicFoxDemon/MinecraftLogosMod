#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The physical field's magic-circle lines (PhysicalFieldVisual): air squeezed into the lines and racing round
// them. Sampler0 is the circle (its alpha is where the lines are); FillTex is physical_ray/wind, horizontal
// streaks in its alpha, laid along the circle's angle so they rush round it, two layers at different speeds.
// Where a line is wide the streaks show white over a cold steel blue; every line keeps a thin hard white edge
// (the circle's alpha minus a blurred copy of it). fieldParam.x is how far out a pulse's light has swept
// (circle radii; the lines flash white as it passes and the streaks surge), fieldParam.y how far the circle
// has dissolved (it is torn away along the streaks and gone at 1). The vertex colour is the tint; its alpha
// how much shows. Same inputs and outputs as field_line.fsh.
uniform sampler2D Sampler0;
uniform sampler2D FillTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

void main() {
    float mask = texture(Sampler0, texCoord0).a;
    if (mask < .01) discard;
    float seconds = GameTime * 1200;
    vec2 d = texCoord0 - .5;
    float r = length(d) * 2;
    float a = atan(d.y, d.x) / 6.2831853;
    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .07, 2)) * 1.6 + smoothstep(sweep - .35, sweep, r) * step(r, sweep) * .35;
    // The wind: long streaks racing round the circle, a slower broad layer under a fast fine one, the fine one
    // surging while the pulse passes. A slight wobble across keeps the streaks from looking ruled.
    float wobble = sin(a * 37.7 + seconds * 2.3) * .015 + sin(a * 91.1 - seconds * 3.1) * .008;
    float s1 = texture(FillTex, vec2(a * 3 - seconds * .22, r * 2.2 + wobble)).a;
    float s2 = texture(FillTex, vec2(a * 7 - seconds * (.55 + lit * .4), r * 5.5 - wobble * 2 + .37)).a;
    float flow = s1 * .45 + s2 * .55;
    // Torn away along the streaks as the circle dissolves.
    if (fieldParam.y > 0 && flow < fieldParam.y * 1.1 - .05) discard;
    // The hard white edge: the line minus a blurred copy of it.
    float o = 1.6 / 512;
    float blur = (texture(Sampler0, texCoord0 + vec2(o, 0)).a + texture(Sampler0, texCoord0 - vec2(o, 0)).a
            + texture(Sampler0, texCoord0 + vec2(0, o)).a + texture(Sampler0, texCoord0 - vec2(0, o)).a) * .25;
    float edge = clamp((mask - blur) * 4 + (1 - blur) * mask * .6, 0, 1);
    // Cold steel blue in the troughs, white in the streaks and on the edge.
    vec3 steel = vertexColor.rgb * vec3(.56, .66, .86);
    float white = clamp(pow(flow, 1.6) * 1.3 + edge, 0, 1);
    vec3 color = mix(steel, vec3(1), white) * (.45 + .55 * flow + .4 * edge + lit) + vec3(lit * .3);
    // Thin in the troughs so the streaks read as air moving through the line, not a solid fill.
    float body = .3 + .7 * smoothstep(.15, .75, flow);
    fragColor = vec4(color, clamp(mask * vertexColor.a * max(body, edge) * (1 + lit * .6), 0, 1)) * ColorModulator;
}

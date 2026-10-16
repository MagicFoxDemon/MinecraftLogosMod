#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The shared filling of a field's magic-circle lines (FieldParts, FieldCore). Sampler0 is the circle: its alpha
// is where the lines are. FillTex is a tileable texture flowed round and outwards along them in the circle's
// polar coordinates, so the lines look alive. fieldParam.x is how far out a pulse's light has swept (circle
// radii; the lines light up as it passes), fieldParam.y how far the circle has dissolved (it breaks up into
// the fill's pattern and is gone at 1). The vertex colour is the tint; its alpha how much shows.
//
// An element's own line shader keeps these inputs and outputs (it is paired with core/field_line).
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
    // Two layers of the fill drifting round the circle at different speeds and creeping outwards.
    float f1 = texture(FillTex, vec2(a * 6 + seconds * .03, r * 2.5 - seconds * .06)).r;
    float f2 = texture(FillTex, vec2(a * 10 - seconds * .05, r * 4 - seconds * .1)).r;
    float flow = f1 * .6 + f2 * .4;
    // The lines break up into the fill's pattern as the circle dissolves.
    if (fieldParam.y > 0 && flow < fieldParam.y * 1.1 - .05) discard;
    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .07, 2)) * 1.15 + smoothstep(sweep - .35, sweep, r) * step(r, sweep) * .25;
    float body = .45 + .85 * flow;
    vec3 color = vertexColor.rgb * (body + lit) + vec3(lit * .35);
    fragColor = vec4(color, clamp(mask * vertexColor.a, 0, 1)) * ColorModulator;
}

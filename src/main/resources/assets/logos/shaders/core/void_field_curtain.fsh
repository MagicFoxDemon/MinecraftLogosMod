#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The void field's curtain (VoidFieldCurtain, vertex stage core/field_line): a see-through veil of the open void
// at the edge - the same screen-space starfield as every void effect over a thin dark nebula - rising and
// gone at its top. Sampler0 is the void nebula (void_beam/aura) wrapped round the edge and drifting up,
// StarTex the end portal. texCoord0.y runs 1 at the ground to 0 at the top; the vertex colour is the violet,
// its alpha how much shows (it flares on a pulse).
uniform sampler2D Sampler0;
uniform sampler2D StarTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

vec3 stars(vec2 screen, float seconds) {
    vec3 field = vec3(0);
    for (int i = 0; i < 6; i++) {
        float layer = float(i + 1), angle = layer * layer * 1.73;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = rotate * screen * (.55 + layer * .23);
        uv += vec2(layer * .713, seconds * (.002 + layer * .001));
        vec3 sampleColor = texture(StarTex, uv).rgb;
        float star = smoothstep(.12, .75, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += star * mix(vec3(.42, .16, .95), vec3(.92, .78, 1), layer / 6) * .85;
    }
    return field;
}

void main() {
    float seconds = GameTime * 1200;
    float h = texCoord0.y;
    float rise = pow(h, 1.6);
    float veil = texture(Sampler0, vec2(texCoord0.x, h * .5 + seconds * .03)).r;
    float wisp = texture(Sampler0, vec2(texCoord0.x * 1.7 + .31, h * .8 + seconds * .05)).r;
    float body = smoothstep(.25, .85, veil * .6 + wisp * .4);
    vec2 screen = (gl_FragCoord.xy - ScreenSize * .5) / ScreenSize.y;
    vec3 field = stars(screen, seconds);
    float starLight = max(field.r, max(field.g, field.b));
    vec3 violet = vertexColor.rgb;
    // A thin bright seam where the veil meets the ground.
    float seam = smoothstep(.9, 1, h);
    vec3 color = vec3(.03, .005, .07) + violet * (body * .14 + seam * .35) + field * 1.6;
    float alpha = vertexColor.a * clamp(rise * (.08 + .22 * body) + starLight * rise * (.3 + .6 * body) + seam * .15, 0, 1);
    if (alpha < .004) discard;
    fragColor = vec4(color, alpha) * ColorModulator;
}

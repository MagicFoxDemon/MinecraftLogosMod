#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The void field's magic-circle lines cut into the ground (VoidFieldSlit, paired with core/field_line): through
// each line the open void of every void effect - the six screen-space planes of the end portal's stars over a
// dark nebula (void_beam.fsh, void_charge_shell.fsh) - opaque, writing depth, with the shared core's violet
// glow (void_field_line.fsh) at its lips. Sampler0 is the circle, StarTex the end portal, AuraTex the void
// nebula (also the flow both shaders break the lines up by). fieldParam.x is how far out a pulse's light has
// swept, fieldParam.y how far the circle has dissolved. The vertex colour is the violet; its alpha how much
// shows, dithered since the cut is opaque.
uniform sampler2D Sampler0;
uniform sampler2D StarTex;
uniform sampler2D AuraTex;
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

float flowAt(vec2 uv, float seconds) {
    vec2 d = uv - .5;
    float r = length(d) * 2;
    float a = atan(d.y, d.x) / 6.2831853;
    float f1 = texture(AuraTex, vec2(a * 3 + seconds * .012, r * 1.2 - seconds * .02)).r;
    float f2 = texture(AuraTex, vec2(a * 5 - seconds * .02, r * 2 - seconds * .035)).r;
    return f1 * .6 + f2 * .4;
}

void main() {
    // The cut only a touch wider than the line it follows: just enough for the void to show through it.
    float mask = texture(Sampler0, texCoord0).a;
    for (int i = 0; i < 4; i++) {
        float ang = float(i) * 1.5707963 + .785398;
        mask = max(mask, texture(Sampler0, texCoord0 + vec2(cos(ang), sin(ang)) * .001).a * .75);
    }
    if (mask < .5) discard;
    // Fading in and out as dither: the cut is opaque.
    vec2 cell = floor(gl_FragCoord.xy);
    float dither = fract(52.9829189 * fract(dot(cell, vec2(.06711056, .00583715))));
    if (dither >= vertexColor.a) discard;
    float seconds = GameTime * 1200;
    float flow = flowAt(texCoord0, seconds);
    if (fieldParam.y > 0 && flow < fieldParam.y * 1.1 - .05) discard;
    vec3 violet = vertexColor.rgb;
    vec2 screen = (gl_FragCoord.xy - ScreenSize * .5) / ScreenSize.y;
    float nebula = texture(AuraTex, screen * .8 + vec2(seconds * .008, 0)).r;
    float r = length(texCoord0 - .5) * 2;
    float sweep = fieldParam.x;
    float lit = exp(-pow((r - sweep) / .1, 2));
    // Deepest in the middle of a line, violet towards its lips.
    float lip = 1 - smoothstep(.4, .62, mask);
    // The stars packed closer than on the beam: a line is only a few pixels wide.
    vec3 field = stars(screen * 1.8, seconds);
    vec3 deep = mix(vec3(.01, .005, .045), vec3(.15, .035, .32), smoothstep(.35, .95, nebula));
    vec3 color = deep + violet * (nebula * .35 * lit + lip * .3) + field * (1.8 + 1.5 * lit);
    fragColor = vec4(color, 1) * ColorModulator;
}

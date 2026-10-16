#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The colossal void charge's sphere closing in (vertex stage core/void_projectile): the same open void as the
// void beam's sphere core (void_beam.fsh, VOID_SPHERE_CORE) - the screen-projected starfield over a dark
// nebula, with a violet fresnel rim - but see-through, so the caster inside stays visible while it is large.
// The vertex colour is the rim's violet; its alpha is how much shows.
uniform sampler2D Sampler0;
uniform sampler2D StarTex;
uniform sampler2D AuraTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewPosition;
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
    vec3 violet = vertexColor.rgb;
    vec3 normal = normalize(cross(dFdx(viewPosition), dFdy(viewPosition)));
    float grazing = 1 - abs(dot(normal, normalize(-viewPosition)));
    float rim = pow(grazing, 3.0);
    vec2 screen = (gl_FragCoord.xy - ScreenSize * .5) / ScreenSize.y;
    float nebula = texture(AuraTex, screen * .8 + vec2(seconds * .008, 0)).r;
    float surface = texture(Sampler0, texCoord0 * vec2(2, 1) + vec2(seconds * .03, -seconds * .1)).r;
    vec3 color = vec3(.035, .005, .085) + violet * (nebula * .11 + surface * .04) + stars(screen, seconds) * 1.6;
    color += violet * rim * 1.9;
    float alpha = vertexColor.a * clamp(.55 + .45 * surface * .5 + rim, 0, 1);
    fragColor = vec4(color, alpha) * ColorModulator;
    if (fragColor.a < .005) discard;
}

#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The void charge's glimpse of open void: the screen-projected starfield every void effect shows (layered
// end-portal sky fixed to the screen, as in void_ray_geometry), drawn additively so it shows wherever the ball
// and the sphere round it are not already white, whatever order the layers are drawn in. Sampler0 is a soft
// round mask (heal/glow); the vertex colour tints the stars and its alpha is how much shows.
uniform sampler2D Sampler0;
uniform sampler2D StarTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

vec3 stars(vec2 screen, float seconds) {
    vec3 field = vec3(0.0);
    for (int i = 0; i < 5; i++) {
        float layer = float(i + 1);
        float angle = layer * layer * 1.37 + 0.9;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = rotate * screen * (0.6 + layer * 0.25);
        uv += vec2(layer * 0.537, seconds * (0.0025 + layer * 0.0012));
        vec3 sampleColor = texture(StarTex, uv).rgb;
        float star = smoothstep(0.14, 0.78, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += star * mix(vec3(0.45, 0.18, 1.0), vec3(0.95, 0.86, 1.0), layer / 5.0);
    }
    return field;
}

void main() {
    float seconds = GameTime * 1200.0;
    vec2 screen = (gl_FragCoord.xy - ScreenSize * 0.5) / ScreenSize.y;
    float mask = texture(Sampler0, texCoord0).a;
    vec3 color = stars(screen, seconds) * mix(vec3(1.0), vertexColor.rgb, 0.4) * 2.5;
    fragColor = vec4(color, mask * vertexColor.a) * ColorModulator;
    if (fragColor.a < 0.004) discard;
}

#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
uniform sampler2D WarpTex;
uniform sampler2D StarTex;
uniform sampler2D AuraTex;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewPosition;
out vec4 fragColor;

// Same six screen-space planes and portal texture as the void projectile; object UVs only shape the shell.
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
    vec2 uv = texCoord0;
    vec3 violet = vertexColor.rgb;
    vec3 normal = normalize(cross(dFdx(viewPosition), dFdy(viewPosition)));
    float grazing = 1 - abs(dot(normal, normalize(-viewPosition)));
    // NS_Ray_3 / MI_Fresnel_Space uses a 5.357019 exponent for its thin silhouette.
    float rim = pow(grazing, 5.357019);
    float warp = texture(WarpTex, uv + vec2(0, -seconds * .5)).r - .5;
    vec2 warped = uv + vec2(warp * .2, 0);
    float noise = texture(NoiseTex, uv + vec2(0, -seconds)).r;
    vec3 color;
    float alpha;
#if defined(VOID_CORE) || defined(VOID_SPHERE_CORE)
    vec2 screen = (gl_FragCoord.xy - ScreenSize * .5) / ScreenSize.y;
    vec3 field = stars(screen, seconds);
    float nebula = texture(AuraTex, screen * .8 + vec2(seconds * .008, 0)).r;
    float surfaceNoise = texture(Sampler0, uv + vec2(0, -seconds * .1)).r;
    color = vec3(.035, .005, .085) + violet * (nebula * .11 + surfaceNoise * .015) + field * 1.6;
    color += violet * rim * 1.9;
#ifdef VOID_SPHERE_CORE
    alpha = 1;
#else
    alpha = .96;
#endif
#elif defined(VOID_PATCHES)
    // Advection in V moves forward; the independent U offset rotates the broken patches around the tube.
    vec2 moving = uv * vec2(.7, 1.4) + vec2(seconds * .24, -seconds * .75);
    float a = texture(Sampler0, moving + vec2(uv.y * 1.7, 0)).r;
    float b = texture(Sampler0, moving * vec2(1.7, .65) + vec2(.37, .61)).r;
    float mask = a * .7 + b * .3;
    if (mask < .64) discard;
    alpha = 1;
    color = mix(vec3(.12, .025, .21), vec3(.23, .055, .34), smoothstep(.64, .9, mask));
#elif defined(VOID_SOLID) || defined(VOID_SPHERE_SOLID)
    float solid = texture(Sampler0, uv + vec2(seconds * .24, -seconds * .4)).r;
    float veins = smoothstep(.62, .9, solid);
    color = mix(violet * .65, vec3(.75, .43, 1), veins * .45);
    alpha = veins * .65;
#elif defined(VOID_RIBBON)
    // Ray 3's MI_Aura_4 pans both T_Noise08 samples at -0.2, replacing Ray 4's snowflakes.
    float a = texture(Sampler0, uv * vec2(1, 1.4) - vec2(seconds * .2, 0)).r;
    float b = texture(Sampler0, uv * vec2(1.3, 2) - vec2(seconds * .2, .07)).r;
    float edge = abs(uv.y * 2 - 1);
    float mask = smoothstep(.15, .55, edge) * (1 - smoothstep(.65, 1, edge));
    alpha = smoothstep(.12, .55, a * b) * mask * .6;
    color = violet * 1.05;
#elif defined(VOID_SPHERE_AURA)
    float a = texture(Sampler0, uv + vec2(-seconds * .12, seconds * .03)).r;
    float b = texture(WarpTex, uv * 2 + vec2(seconds * .08, 0)).r;
    alpha = smoothstep(.25, .7, a * b) * pow(grazing, 1.2) * .55;
    color = violet * 1.05;
#elif defined(VOID_SPHERE_DARK)
    float cloud = texture(Sampler0, warped + vec2(0, -seconds * .16)).r;
    alpha = smoothstep(.25, .7, cloud) * .22;
    color = violet * .12;
#else
#ifdef VOID_INNER_FLOW
    float speed = 1.4;
#else
    float speed = 2;
#endif
    float tex = texture(Sampler0, warped + vec2(0, -seconds * speed)).r;
    float energy = max(0, tex - noise * .2);
    alpha = smoothstep(.15, .75, energy) * .72;
    color = mix(violet, vec3(.72, .42, 1), energy * .65);
#endif
    fragColor = vec4(color, alpha) * ColorModulator;
    if (fragColor.a < .005) discard;
}

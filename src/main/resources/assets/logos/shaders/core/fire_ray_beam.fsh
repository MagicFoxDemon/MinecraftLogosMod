#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The fire ray's burst: MA_Laser_Fire's smoke-trail flame scrolled toward the target over a soft glow,
// or (FIRE_RAY_CORE) MA_Laser_01's thin white-gold line. U runs along the ray in blocks / 4, V across it.
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float seconds = GameTime * 1200.0;
    vec2 uv = texCoord0;
    float across = abs(uv.y * 2.0 - 1.0);
    float n = texture(NoiseTex, vec2(uv.x * .6 - seconds * 2.4, uv.y * .5 + seconds * .35)).a;
#ifdef FIRE_RAY_CORE
    float flicker = .8 + .2 * texture(Sampler0, vec2(uv.x * 2.0 - seconds * 6.0, .5)).a;
    float core = exp(-across * across * 6.0) * (1.0 - smoothstep(.8, 1.0, across)) * flicker * (.85 + .3 * n);
    vec3 color = mix(vec3(1.0, .50, .10), vec3(1.0, .96, .80), smoothstep(.4, .9, core));
    float alpha = clamp(core, 0.0, 1.0);
#else
    float warp = (n - .5) * .4;
    float fire = texture(Sampler0, vec2(uv.x - seconds * 3.5, uv.y + warp)).a;
    fire = clamp(fire * (.7 + n * .8), 0.0, 1.0);
    float edge = smoothstep(0.0, .22, uv.y) * smoothstep(1.0, .78, uv.y);
    float glow = exp(-across * across * 4.0) * .45;
    float body = max(smoothstep(.03, .4, fire) * edge, glow);
    vec3 color = mix(vec3(.90, .16, .0), vec3(1.0, .45, .05), smoothstep(.1, .5, fire));
    color = mix(color, vec3(1.0, .84, .42), smoothstep(.6, .98, fire));
    float alpha = body;
#endif
    fragColor = vec4(color, alpha) * vertexColor * ColorModulator;
    if (fragColor.a < .004) discard;
}

#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
#ifdef FROST_CRYSTALS
    float alpha = 1;
    vec3 color = vec3(1);
#elif defined(FROST_CORE)
    float mask = texture(Sampler0, texCoord0).r;
    float alpha = pow(mask, .8) * .85;
    vec3 color = mix(vec3(.58, .85, 1), vec3(.96, .99, 1), pow(mask, 1.7));
#elif defined(FROST_CAP)
    float radius = length((texCoord0 - .5) * 2);
    float core = 1 - smoothstep(.18, .48, radius);
    float alpha = max(core, texture(Sampler0, texCoord0).a * .7);
    vec3 color = mix(vec3(.56, .85, 1), vec3(.94, .99, 1), core);
#else
    float seconds = GameTime * 1200;
    vec2 uv = texCoord0;
    uv.y = fract(uv.y + seconds * .16 + .25);
    float warp = texture(Sampler0, vec2(uv.x * .5 - seconds * .5, uv.y)).r * .1;
    float flow = texture(Sampler0, vec2(uv.x - seconds * 2, uv.y + warp)).r;
    // No top/bottom fade: V now wraps around the cylinder instead of crossing a billboard.
    float alpha = pow(flow, 1.8) * .85;
    vec3 color = mix(vec3(.49, .81, 1), vec3(.91, .98, 1), smoothstep(.2, .85, flow));
#endif
    fragColor = vec4(color, alpha) * vertexColor * ColorModulator;
    if (fragColor.a < .005) discard;
}

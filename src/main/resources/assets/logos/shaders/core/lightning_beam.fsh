#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
uniform sampler2D MaskTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float seconds = GameTime * 1200;
#ifdef SHOCK_STRIP
    vec2 uv = texCoord0;
    float warp = (texture(NoiseTex, vec2(uv.x - seconds * .5, uv.y * 2)).g - .5) * .15;
    vec4 tex = texture(Sampler0, vec2(uv.x * 2 - seconds * 1.5, uv.y + warp));
    float energy = clamp(dot(tex.rgb, vec3(12)), 0, 1);
    float alpha = energy * tex.a * .72;
    vec3 color = vec3(1);
#elif defined(RUNIC_RIBBON)
    vec3 rainbow = texture(Sampler0, texCoord0 * 2 + seconds * vec2(.125, .05)).rgb;
    rainbow = mix(rainbow, vec3(dot(rainbow, vec3(.299, .587, .114))), .55);
    float mask = texture(MaskTex, vec2(texCoord0.x - seconds * .5, texCoord0.y)).r;
    float alpha = mask * .75;
    vec3 color = mix(vec3(.6, .9, 1), rainbow * 1.3, .18);
#elif defined(SHOCK_CAP)
    float r = length((texCoord0 - .5) * 2);
    float centre = 1 - smoothstep(.12, .38, r);
    float alpha = max(centre * .85, texture(Sampler0, texCoord0).a * .35);
    vec3 color = mix(vec3(.03, .66, 1), vec3(.67, .96, 1), centre);
#else
    float alpha = pow(max(0, 1 - abs(texCoord0.y * 2 - 1)), 1.5);
    vec3 color = vec3(1);
#endif
    fragColor = vec4(color, alpha) * vertexColor * ColorModulator;
    if (fragColor.a < .005) discard;
}

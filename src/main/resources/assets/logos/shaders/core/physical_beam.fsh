#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
#ifdef PHYSICAL_FLASH
    float alpha = texture(Sampler0, texCoord0).a;
#elif defined(PHYSICAL_GLOW)
    float alpha = pow(max(0, 1 - abs(texCoord0.y * 2 - 1)), 2) * texture(Sampler0, vec2(.5)).a;
#elif defined(PHYSICAL_AIR)
    float seconds = GameTime * 1200;
    float angle = texCoord0.x * 6.283185;
    vec2 radial = vec2(cos(angle), sin(angle));
    vec2 uv = radial * (.8 + texCoord0.y * .45);
    float warp = texture(NoiseTex, uv * 1.6 + vec2(seconds * .1, -seconds * .13)).r;
    float cloud = texture(Sampler0, uv + vec2(seconds * .07, -seconds * .09) + (warp - .5) * .28).r;
    float tornEdge = texCoord0.y * 2 - 1 + (warp - .5) * .85 + (cloud - .5) * .45;
    float edge = pow(max(0, 1 - abs(tornEdge)), 1.1);
    float wisps = smoothstep(.19, .64, cloud * (.6 + warp));
    float alpha = edge * wisps * .9;
#else
    float alpha = texture(Sampler0, vec2(.5)).a;
#endif
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
    if (fragColor.a < .005) discard;
}

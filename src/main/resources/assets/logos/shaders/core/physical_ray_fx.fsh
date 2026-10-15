#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
#ifdef RAY_LINE
    // A camera-facing band: a hard white needle in the middle of a faint sheath. V runs across it.
    float d = texCoord0.y * 2 - 1;
    float alpha = (exp(-d * d * 60) + .3 * exp(-d * d * 6)) * step(.001, texture(Sampler0, vec2(.5)).a);
#elif defined(RAY_FLASH)
    float alpha = texture(Sampler0, texCoord0).a;
#elif defined(RAY_AIR)
    // A compression ring: U runs around it, V across its width; torn by two scrolling noises.
    float seconds = GameTime * 1200;
    float angle = texCoord0.x * 6.283185;
    vec2 radial = vec2(cos(angle), sin(angle));
    vec2 uv = radial * (.7 + texCoord0.y * .4);
    float wind = texture(NoiseTex, vec2(texCoord0.x * 3 + seconds * .4, texCoord0.y * .5)).a;
    float cloud = texture(Sampler0, uv + vec2(seconds * .11, -seconds * .07) + (wind - .5) * .2).a;
    float torn = texCoord0.y * 2 - 1 + (cloud - .5) * .7;
    float edge = pow(max(0, 1 - abs(torn)), 1.4);
    float alpha = min(1, edge * (.55 + .75 * smoothstep(.15, .7, cloud * (.55 + wind * .7))));
#else
    float alpha = 1;
#endif
    fragColor = vec4(vertexColor.rgb, vertexColor.a * alpha) * ColorModulator;
    if (fragColor.a < .004) discard;
}

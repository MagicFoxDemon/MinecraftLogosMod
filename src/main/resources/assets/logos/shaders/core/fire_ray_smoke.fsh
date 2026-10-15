#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The smoke the fire ray leaves on its line. Vertex alpha is how much of it is left: as it drops, the
// wisps are eaten away by noise from their thin parts inward, so the band breaks up as it drifts off.
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float seconds = GameTime * 1200.0;
    vec2 uv = texCoord0;
    float left = vertexColor.a;
    float wisp = texture(Sampler0, vec2(uv.x * .5 + seconds * .04, uv.y)).a;
    float noise = texture(NoiseTex, vec2(uv.x * .3 - seconds * .025, uv.y * .5 + seconds * .06)).a;
    float edge = smoothstep(0.0, .3, uv.y) * smoothstep(1.0, .7, uv.y);
    float body = (wisp * .6 + noise * .55) * edge;
    float cut = (1.0 - left) * .75;
    float alpha = smoothstep(cut, cut + .3, body) * min(1.0, left * 2.0) * .8;
    fragColor = vec4(vertexColor.rgb, alpha) * ColorModulator;
    if (fragColor.a < .004) discard;
}

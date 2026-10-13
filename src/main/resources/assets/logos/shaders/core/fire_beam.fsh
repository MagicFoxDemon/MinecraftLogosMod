#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
uniform sampler2D Sampler0;
uniform sampler2D NoiseTex;
uniform sampler2D MaskTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    float seconds = GameTime * 1200.0;
    vec2 uv = texCoord0;
    vec3 noise = texture(NoiseTex, vec2(uv.x * .5 - seconds * .7, uv.y * .256 + seconds * .13)).rgb;
    float warp = (noise.r - .5) * .24;
    // Beam_Magma uses _MainTextureChannel=(1,1,1), not a single channel.
    float energy = clamp(dot(texture(Sampler0, vec2(uv.x - seconds, uv.y + warp)).rgb, vec3(1.0)), 0.0, 1.0);
    float edge = smoothstep(0.0, .12, uv.y) * smoothstep(1.0, .88, uv.y);
#ifdef FIRE_ACCENTS
    float mask = texture(MaskTex, vec2(uv.y, .5)).r;
    energy *= mask * (.22 + noise.b * .6);
    vec3 color = mix(vec3(.7, .07, 0), vec3(1, .36, .005), energy);
    float alpha = energy * edge * .28;
#else
    energy = clamp(energy - noise.b * .10, 0, 1);
    vec3 color = mix(vec3(1, .12, 0), vec3(1, .5975, 0), smoothstep(.03, .45, energy));
    color = mix(color, vec3(1, .895, .10), smoothstep(.45, .85, energy));
    float alpha = smoothstep(.015, .4, energy) * edge * .40;
#endif
    fragColor = vec4(color, alpha) * vertexColor * ColorModulator;
    if (fragColor.a < .005) discard;
}

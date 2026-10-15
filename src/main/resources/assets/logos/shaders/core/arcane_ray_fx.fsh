#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
// Arcane ray layers. Ribbons run U along the ray and V across it (0..1); sprites use the mask's own UV.
void main() {
    vec3 rgb = vertexColor.rgb;
    float alpha;
#if defined(ARCANE_RAY_CORE)
    // The laser itself: a soft violet body around a near-white thread.
    float across = max(0.0, 1.0 - abs(texCoord0.y * 2.0 - 1.0));
    alpha = vertexColor.a * pow(across, 1.6);
    rgb = mix(rgb, vec3(1.0, 0.93, 1.0), pow(across, 7.0));
#elif defined(ARCANE_RAY_BAND)
    // A scrolling strip (stars, silk, crackle) softened toward both edges.
    float edge = smoothstep(1.0, 0.45, abs(texCoord0.y * 2.0 - 1.0));
    alpha = vertexColor.a * texture(Sampler0, texCoord0).a * edge;
#elif defined(ARCANE_RAY_DISSOLVE)
    // The lingering thread: vertex alpha is how much is left, and the silk noise eats it away in patches.
    float across = max(0.0, 1.0 - abs(texCoord0.y * 2.0 - 1.0));
    // Two octaves of the silk noise so the gaps do not repeat every few blocks.
    float noise = texture(Sampler0, vec2(texCoord0.x, 0.31 + texCoord0.y * 0.08)).a * 0.6
            + texture(Sampler0, vec2(texCoord0.x * 2.7 + 0.37, 0.63 + texCoord0.y * 0.05)).a * 0.4;
    float left = vertexColor.a;
    alpha = pow(across, 2.2) * smoothstep(noise - 0.08, noise + 0.08, left * 1.16 - 0.08);
#ifdef ARCANE_RAY_SOFT
    alpha *= 0.3;
#else
    rgb = mix(rgb, vec3(1.0, 0.9, 1.0), pow(across, 9.0) * 0.6);
#endif
#else
    float mask = texture(Sampler0, texCoord0).a;
    alpha = vertexColor.a * mask;
#ifdef ARCANE_RAY_HOT
    rgb = mix(rgb, vec3(1.0, 0.94, 1.0), smoothstep(0.55, 1.0, mask) * 0.75);
#endif
#endif
    fragColor = vec4(rgb, alpha) * ColorModulator;
    if (fragColor.a < 0.004) discard;
}

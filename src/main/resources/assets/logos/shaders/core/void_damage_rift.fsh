#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// Fragment stage of a Photon custom shader (vertex stage photon:particle); the material binds the textures.
// A torn-open pocket of the void: a dark interior showing a screen-projected starfield, a ragged violet rim.
// The particle colour tints the rim and its alpha fades the whole rift.
uniform sampler2D RiftMask;
uniform sampler2D StarTexture;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

// Five layers of the end-portal sky projected on the screen, each turned and drifting on its own,
// so the stars stay put as the rift moves across them instead of sliding with the quad.
vec3 stars(vec2 screen, float seconds) {
    vec3 field = vec3(0.0);
    for (int i = 0; i < 5; i++) {
        float layer = float(i + 1);
        float angle = layer * layer * 2.11 + 0.4;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = rotate * screen * (0.7 + layer * 0.27);
        uv += vec2(layer * 0.371, seconds * (0.003 + layer * 0.0015));
        vec3 sampleColor = texture(StarTexture, uv).rgb;
        float star = smoothstep(0.15, 0.8, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += star * mix(vec3(0.45, 0.18, 1.0), vec3(0.95, 0.85, 1.0), layer / 5.0);
    }
    return field;
}

void main() {
    float seconds = GameTime * 1200.0;
    vec2 screen = (gl_FragCoord.xy - ScreenSize * 0.5) / ScreenSize.y;
    float mask = texture(RiftMask, texCoord0).a;
    // The rim is where the mask falls off; the solid middle is the open void.
    float rim = smoothstep(0.05, 0.4, mask) * (1.0 - smoothstep(0.55, 0.95, mask));
    vec3 interior = vec3(0.03, 0.004, 0.075) + stars(screen, seconds) * 1.5 * smoothstep(0.5, 1.0, mask);
    vec3 color = mix(interior, vertexColor.rgb, clamp(rim * 0.8, 0.0, 0.65));
    vec4 result = vec4(color, smoothstep(0.03, 0.35, mask) * vertexColor.a) * ColorModulator;
    if (result.a < 0.005) discard;
    fragColor = apply_fog(result, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

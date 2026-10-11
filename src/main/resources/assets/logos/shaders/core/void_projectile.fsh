#version 330
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewPosition;
out vec4 fragColor;

// Screen UVs do not follow the shell's object UVs. Independent layers suggest distant planes.
vec3 stars(vec2 screen, float seconds) {
    vec3 field = vec3(0.0);
    for (int i = 0; i < 6; i++) {
        float layer = float(i + 1);
        float angle = layer * layer * 1.73;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = rotate * screen * (0.55 + layer * 0.23);
        uv += vec2(layer * 0.713, seconds * (0.002 + layer * 0.001));
        vec3 sampleColor = texture(Sampler2, uv).rgb;
        float star = smoothstep(0.12, 0.75, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += star * mix(vec3(0.42, 0.16, 0.95), vec3(0.92, 0.78, 1.0), layer / 6.0) * 0.85;
    }
    return field;
}

void main() {
    float seconds = GameTime * 1200.0;
    vec2 screen = (gl_FragCoord.xy - ScreenSize * 0.5) / ScreenSize.y;
    vec3 starfield = stars(screen, seconds);
    vec3 violet = vertexColor.rgb;
#ifdef VOID_HEAD
    vec2 uv1 = texCoord0 * vec2(0.4, 1.5) + vec2(seconds * 0.2, -seconds);
    vec2 uv2 = texCoord0 * vec2(0.6, 3.0) + vec2(-seconds * 0.2, -seconds * 1.5);
    float turbulence = pow(texture(Sampler0, uv1).r * texture(Sampler0, uv2).r, 0.8);
    float seam = 0.5 + 0.5 * sin(texCoord0.y * 25.1327 + seconds * 2.2 + texCoord0.x * 5.0);
    vec3 normal = normalize(cross(dFdx(viewPosition), dFdy(viewPosition)));
    float rim = pow(1.0 - abs(dot(normal, normalize(-viewPosition))), 2.0);
    float energy = smoothstep(0.15, 0.58, turbulence) * (0.6 + 0.4 * seam);
    float nebula = texture(Sampler0, screen * 0.8 + vec2(seconds * 0.008, 0.0)).r;
    vec3 interior = vec3(0.035, 0.005, 0.085) + violet * nebula * 0.11 + starfield * 1.6;
    vec3 color = mix(interior, violet * 1.3, clamp(energy * 0.7 + rim * 0.85, 0.0, 1.0));
    float alpha = 0.94;
#else
    vec2 uv = texCoord0 * vec2(0.6, 1.0) - vec2(seconds * 2.2, 0.0);
    float noise = texture(Sampler0, uv).r;
    float mask = texture(Sampler1, texCoord0).r;
#ifdef VOID_FILAMENT
    vec3 color = mix(violet, vec3(0.84, 0.58, 1.0), noise * 0.35);
    float alpha = vertexColor.a * pow(noise, 0.7) * mask;
#else
    vec3 color = mix(violet * 0.55 + starfield, violet, noise);
    float alpha = vertexColor.a * pow(noise, 0.7) * mask;
#endif
#endif
    vec4 result = vec4(color, alpha) * ColorModulator;
    if (result.a < 0.005) discard;
    float distance = -ProjMat[3].z / (gl_FragCoord.z * -2.0 + 1.0 - ProjMat[2].z);
    fragColor = apply_fog(result, distance, distance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}

#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// A crystal a lingering vein thrust up (VeinCrystals), drawn with the field-line vertex stage. Sampler0 is the style's
// tileable texture; texCoord0 runs round the crystal (u) and up it (v), already scaled to repeat as the style says.
// The vertex colour carries numbers, not a colour: r how lit the facet is, g how far it faces away from the camera
// (its rim), b how much it flashes as it thrusts up. fieldParam.x is how far it has dissolved (0 whole, 1 gone),
// fieldParam.y a random offset of its own. From the style, as defines: TINT_* times the texture's own colours,
// RIM_* the rim's colour, PAN_U/PAN_V how far the texture flows per second, CLEAR (if defined) how opaque the
// body is behind its rim, and CONTRAST (if defined) how much of the texture's light and dark shows.
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 fieldParam;
out vec4 fragColor;

void main() {
    float seconds = GameTime * 1200;
    vec2 uv = texCoord0 + vec2(PAN_U, PAN_V) * seconds + fieldParam.y * 3.7;
    // A little more contrast than the texture has, so the crystals read as solid and deep rather than washed out.
    vec3 tex = pow(texture(Sampler0, uv).rgb, vec3(1.25));
#ifdef CONTRAST
    // Only that much of its light and dark, round an even light colour.
    tex = mix(vec3(0.8), tex, CONTRAST);
#endif
    // It dissolves by a second, slower look at the same texture, so it breaks into patches rather than fading.
    float grain = dot(texture(Sampler0, texCoord0 * .45 + fieldParam.y * 5.3).rgb, vec3(.299, .587, .114));
    float cut = grain + .2 - fieldParam.x * 1.25;
    if (cut < 0) discard;

    float shade = vertexColor.r, rim = vertexColor.g, flash = vertexColor.b;
    vec3 rimColor = vec3(RIM_R, RIM_G, RIM_B);
    vec3 body = tex * vec3(TINT_R, TINT_G, TINT_B) * (.5 + .7 * shade);
    vec3 color = mix(body, rimColor, clamp(rim * 1.6, 0, 1));
    // Bright as it thrusts up, and along the edges where it is dissolving.
    color += rimColor * (flash * .8 + (1 - smoothstep(0, .12, cut)) * 1.2 * step(.001, fieldParam.x));
    float alpha = 1;
#ifdef CLEAR
    alpha = mix(CLEAR, 1, clamp(rim * 1.6 + flash, 0, 1));
#endif
    fragColor = vec4(color, alpha) * ColorModulator;
}

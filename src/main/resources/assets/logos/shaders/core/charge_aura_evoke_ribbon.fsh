#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The ribbons of the colossal lightning and void auras (ChargeAuraEvoke, vertex stage core/charge_aura_evoke), after
// the materials of NS_Charge_EvokeEnergy_Root. texCoord0.x runs along the ribbon from the feet (0) to its far end (1),
// texCoord0.y across it; the vertex colour is the tint, its alpha how much shows. One of:
//   FLAME  M_Vfx_FlipBook: the 4x4 energy-wind flipbook (Sampler0, red as the mask) looping three times a second,
//          its root at the feet, each cell faded out at its edges; auraParam.y offsets its phase.
//   GLOW   M_Vfx_MathSimpleMaskRibbon: only the border mask (tail 2, side 15, head 2), its sides rounder.
//   STRIP  M_Vfx_SinCosStrip: two thin lines sin(pi v')^thickness weaving round V 0.3 and 0.7, v' = V +- 0.2 + a sine
//          of U running along; auraParam.x is the thickness / 100 (thinner with age), auraParam.y the variant
//          (0: wavelength 1, speed -2.75, amplitude 0.2, head fade 1.5; 1: 1.5, -2.25, 0.25, 2).
//   HAIR   M_Vfx_GhostHair: three strands of the flow band (Sampler0) swaying by amplitude x U x sine and running
//          along, faded (1 - U)^2 U^0.5, broken by AlphaTex, coloured through NoiseTex, to the power 1.5.
// and the element's accent:
//   ELECTRIC  the strip's lines jag and re-jag fourteen times a second; the hair's strands are the lightning bolt
//             (Sampler0 is lightning_beam/shock, its alpha the bolt) jumping to another stretch ten times a second.
//   STARRY    the screen-space starfield of every void effect shows through whatever glows (StarTex the end portal).
uniform sampler2D Sampler0;
#ifdef HAIR
uniform sampler2D NoiseTex;
uniform sampler2D AlphaTex;
#endif
#ifdef STARRY
uniform sampler2D StarTex;
#endif
in vec4 vertexColor;
in vec2 texCoord0;
in vec2 auraParam;
in vec3 viewPosition;
out vec4 fragColor;

const float TAU = 6.2831853;
const float PI = 3.1415927;

float hash(float n) {
    return fract(sin(n) * 43758.5453);
}

// A line through random knots one unit apart, drawn afresh whenever key changes.
float jag(float x, float key) {
    float i = floor(x);
    return mix(hash(i * 1.73 + key), hash((i + 1) * 1.73 + key), fract(x)) - .5;
}

// MF_MathBorderMask: (1 - U)^tail U^head along (scaled to peak at 1) times saturate(side (V (1 - V))^2) across.
float border(vec2 uv, float tailPow, float side, float headPow) {
    float across = clamp(side * pow(uv.y * (1 - uv.y), 2.0), 0, 1);
    float peak = headPow / (tailPow + headPow);
    float norm = pow(1 - peak, tailPow) * pow(peak, headPow);
    return across * pow(max(1 - uv.x, 0), tailPow) * pow(max(uv.x, 0), headPow) / norm;
}

#ifdef STARRY
// The six screen-space planes of the void projectile and beam (void_beam.fsh).
vec3 stars(vec2 screen, float seconds) {
    vec3 field = vec3(0);
    for (int i = 0; i < 6; i++) {
        float layer = float(i + 1), angle = layer * layer * 1.73;
        mat2 rotate = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
        vec2 uv = rotate * screen * (.55 + layer * .23);
        uv += vec2(layer * .713, seconds * (.002 + layer * .001));
        vec3 sampleColor = texture(StarTex, uv).rgb;
        float star = smoothstep(.12, .75, max(sampleColor.r, max(sampleColor.g, sampleColor.b)));
        field += star * mix(vec3(.42, .16, .95), vec3(.92, .78, 1), layer / 6) * .85;
    }
    return field;
}
#endif

void main() {
    float seconds = GameTime * 1200;
    vec2 uv = texCoord0;
    vec3 color = vertexColor.rgb;
    float mask = 0;
#if defined(FLAME)
    float frame = floor(fract(seconds * 3 + auraParam.y) * 16);
    vec2 cell = vec2(mod(frame, 4), floor(frame / 4));
    // The stamp is a brightness mask in an opaque texture: its red channel is the shape. Its blobs touch the edges
    // of their cells (and linear filtering bleeds in the next cell), so each cell is also faded out all round:
    // whatever the ribbon does, no edge of the quad shows.
    vec2 inCell = clamp(uv, .01, .99);
    float soft = smoothstep(0, .12, inCell.x) * (1 - smoothstep(.65, .97, inCell.x)) * smoothstep(0, .22, inCell.y)
            * (1 - smoothstep(.78, 1, inCell.y));
    mask = texture(Sampler0, (cell + inCell) / 4).r * soft;
    // Strength 2: the dense middle runs hot.
    color = mix(color, min(color * 1.7 + .08, vec3(1)), mask);
#elif defined(GLOW)
    // The border mask's along-fade; across, a rounder fall-off than saturate(15 (V (1 - V))^2), whose hard sides
    // the reference's bloom hides and plain blending does not.
    mask = pow(border(vec2(uv.x, .5), 2, 15, 2) * pow(sin(PI * uv.y), 1.6), .7);
#elif defined(STRIP)
    // A third of the reference's band power: its lines owe much of their width to bloom, which plain blending lacks.
    float thick = auraParam.x * 100 / 3;
    bool second = auraParam.y > .5;
    float waveLength = second ? 1.5 : 1.0, speed = second ? -2.25 : -2.75, amp = second ? .25 : .2, head = second ? 2.0 : 1.5;
    float phase = (uv.x + seconds * speed) * waveLength;
    float v1 = uv.y + .2 + amp * sin(TAU * phase);
    float v2 = uv.y - .2 + amp * sin(TAU * (phase + .25));
#ifdef ELECTRIC
    float key = floor(seconds * 14);
    v1 += .1 * jag(uv.x * 16, key);
    v2 += .1 * jag(uv.x * 16 + 40, key + 3.1);
#endif
    float lines = pow(max(sin(PI * v1), 0), thick) + pow(max(sin(PI * v2), 0), thick);
    mask = pow(lines * border(uv, 2, 15, head), .5);
    // 13 and 15 times as bright as their colour: the cores burn white.
    color = mix(color, vec3(1), clamp(lines - .35, 0, 1) * .55);
#elif defined(HAIR)
    float amp[3] = float[](.45, .6, .75);
    float period[3] = float[](.3, .4, .5);
    float pan[3] = float[](-1.2, -2.1, -2.3);
    float offset[3] = float[](0.0, .33, .7);
    float strands = 0;
    for (int i = 0; i < 3; i++) {
        float wave = amp[i] * uv.x * sin(TAU * (uv.x + pan[i] * seconds) / period[i]);
#ifdef ELECTRIC
        float jump = hash(floor(seconds * 10) + float(i) * 7.1 + auraParam.y * 13);
        strands += texture(Sampler0, vec2(uv.x * .8 + offset[i] + jump, .42 + (uv.y - .5) * .9 + wave * .35)).a * 1.4;
#else
        strands += texture(Sampler0, vec2(uv.x - seconds + offset[i], uv.y + wave)).r;
#endif
    }
    float alphaNoise = texture(AlphaTex, uv + vec2(-.5 * seconds, 0)).r;
    float along = pow(max(1 - uv.x, 0), 2.0) * pow(max(uv.x, 0), .5) / .286;
    // The flow band is not quite seamless where it wraps: a soft fall-off across keeps any seam off the sides.
    mask = pow(clamp(strands * along * mix(1, alphaNoise, .5) * 1.6, 0, 1.5), 1.5) * pow(sin(PI * uv.y), .5);
    color *= .6 + .8 * texture(NoiseTex, uv * 1.2 + vec2(-.9 * seconds, 0)).r;
    color = mix(color, vec3(1), clamp(mask - .6, 0, 1) * .5);
#endif
    float alpha = clamp(mask * vertexColor.a, 0, 1);
#ifdef STARRY
    vec2 screen = (gl_FragCoord.xy - ScreenSize * .5) / ScreenSize.y;
    color += stars(screen, seconds) * 1.3 * clamp(mask, 0, 1);
#endif
    if (alpha < .003) discard;
    fragColor = vec4(color, alpha) * ColorModulator;
}

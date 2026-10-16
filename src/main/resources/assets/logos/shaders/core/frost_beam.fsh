#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
#if defined(FROST_BEAM_SOLID)
    // Ice crystals: the facet shade is in the vertex colour; the noise only adds a faint frosting.
    vec4 color = vertexColor;
    color.rgb *= .92 + .08 * texture(Sampler0, texCoord0).a;
#elif defined(FROST_BEAM_LASER)
    // gradient_002: the soft falloff takes the tint, its hot centre line burns to white.
    float mask = texture(Sampler0, texCoord0).a;
    vec4 color = vec4(mix(vertexColor.rgb, vec3(1), pow(mask, 3.)), mask * vertexColor.a);
#elif defined(FROST_BEAM_ICE)
    // IceBeam: the streaming ice body. A slower, stretched copy of the same mask bends the fast one
    // (the material's detail distortion) and breaks up its repeats; the densest strands burn white.
    float bend = texture(Sampler0, vec2(texCoord0.x * .31 + .53, texCoord0.y * .8 + .1)).a - .5;
    vec2 uv = vec2(texCoord0.x, clamp(texCoord0.y + bend * .22, 0., 1.));
    float fast = texture(Sampler0, uv).a;
    float slow = texture(Sampler0, vec2(texCoord0.x * .57 + .21, 1. - uv.y)).a;
    float across = 1. - abs(texCoord0.y * 2. - 1.);
    float mask = max(fast, slow * .65) * smoothstep(0., .35, across);
    vec4 color = vec4(mix(vertexColor.rgb, vec3(1), pow(mask, 2.5) * .5), mask * vertexColor.a);
#elif defined(FROST_BEAM_RIME)
    // The rime line left behind: vertex alpha is how much of it remains; the water noise thresholds
    // its thinnest stretches away first, so it breaks into pieces instead of fading evenly.
    float noise = texture(Sampler0, vec2(texCoord0.x * .37, .31 + texCoord0.y * .1)).a * .6
            + texture(Sampler0, vec2(texCoord0.x * 1.13 + .5, .72 + texCoord0.y * .1)).a * .4;
    float left = vertexColor.a;
    float keep = smoothstep(1. - left, 1. - left + .2, noise * .85 + .15);
    float across = 1. - abs(texCoord0.y * 2. - 1.);
    vec4 color = vec4(mix(vertexColor.rgb, vec3(1), across * across * .6), keep * across * min(1., left * 3.));
#else
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * texture(Sampler0, texCoord0).a);
#endif
    fragColor = color * ColorModulator;
    if (fragColor.a < .004) discard;
}

#version 330
#moj_import <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;
void main() {
    vec4 tex = texture(Sampler0, texCoord0);
#ifdef LASER_CORE
    float mask = tex.r;
    float alpha = pow(mask, .8) * smoothstep(0, .025, texCoord0.x)
            * (1 - smoothstep(.97, 1, texCoord0.x));
    vec3 color = mix(vec3(1, .48, .06), vec3(1, .97, .76), pow(mask, 1.7));
#elif defined(CONE_GLOW)
    float alpha = tex.a * tex.r;
    vec3 color = vertexColor.rgb;
#elif defined(WINDING_TRAIL)
    float alpha = tex.a;
    vec3 color = vertexColor.rgb;
#else
    float alpha = tex.r;
    vec3 color = vertexColor.rgb;
#endif
    fragColor = vec4(color, alpha * vertexColor.a) * ColorModulator;
    if (fragColor.a < .005) discard;
}

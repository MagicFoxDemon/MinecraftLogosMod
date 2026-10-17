#version 330
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:globals.glsl>
// The physical field's pressure wall (PhysicalFieldVisual): a see-through ring of squeezed air at the edge.
// Sampler0 is physical/trail_noise (bright cell edges, read as the refraction ripples of air under pressure),
// FillTex physical_ray/wind (streaks in its alpha). U runs round the wall, V from 0 at the top to 1 at the foot.
// The ripples rise and are smeared sideways by wind running round the wall; faint pressure bands climb
// through them; the foot is a thin hard white line. The vertex colour is the tint and its alpha how much
// shows (the fade upwards and the flare on a pulse are in it).
uniform sampler2D Sampler0;
uniform sampler2D FillTex;
in vec4 vertexColor;
in vec2 texCoord0;
out vec4 fragColor;

void main() {
    float seconds = GameTime * 1200;
    float u = texCoord0.x, v = texCoord0.y;
    float wind = texture(FillTex, vec2(u * .5 - seconds * .35, v * .7)).a;
    float ripple = texture(Sampler0, vec2(u * .6 + (wind - .5) * .12, v * .9 + seconds * .1)).r;
    float ripple2 = texture(Sampler0, vec2(u * 1.1 - seconds * .03, v * 1.6 + seconds * .19 + .5)).r;
    float cells = smoothstep(.35, 1, ripple) * .7 + smoothstep(.45, 1, ripple2) * .45;
    float bands = pow(.5 + .5 * sin((v * 5 + seconds * .9 + ripple * .6) * 6.2831853), 8);
    float foot = smoothstep(.9, 1, v);
    float alpha = cells * .9 + bands * .35 + wind * .2 + foot * 1.4;
    vec3 steel = vertexColor.rgb * vec3(.62, .72, .9);
    vec3 color = mix(steel, vec3(1), clamp(cells * .8 + foot, 0, 1));
    fragColor = vec4(color, clamp(alpha * vertexColor.a, 0, 1)) * ColorModulator;
    if (fragColor.a < .004) discard;
}

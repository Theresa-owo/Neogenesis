#version 450
layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
layout(binding = 0) uniform sampler2D atlas;
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vTint;
layout(location = 0) out vec4 outColor;

// Item icon quads: vUv is the sprite's rect inside the copied vanilla atlas.
// The vertex tint scales opacity (alpha) and multiplies the texture color,
// so nodes can fade/recolor icons without extra pipelines.
void main() {
    vec4 tex = texture(atlas, vUv);
    outColor = vec4(tex.rgb * vTint.rgb, tex.a * vTint.a);
}

#version 450
layout(binding = 0) uniform sampler2D atlas;
layout(location = 0) in vec3 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 0) out vec4 outColor;
void main() {
    vec4 base = texture(atlas, vUV);
    if (base.a < 0.1) discard;
    outColor = vec4(base.rgb * vColor, 1.0);
}

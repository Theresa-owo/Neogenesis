#version 450
layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
layout(binding = 0) uniform sampler2D glyphAtlas;
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vTint;
layout(location = 2) in vec4 vRect;
layout(location = 3) in vec4 vGradEnd;
layout(location = 4) in vec4 vBorder;
layout(location = 5) in vec2 vScreenUv;
layout(location = 0) out vec4 outColor;
void main() {
    float coverage = texture(glyphAtlas, vUv).r;
    outColor = vec4(vTint.rgb, vTint.a * coverage);
}

#version 450
layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
layout(binding = 0) uniform sampler2D srcTex;
layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;
void main() {
    vec2 texel = push.params1.xy;
    vec2 dir = push.params1.zw;
    float w0 = 0.227027;
    float w1 = 0.1945946;
    float w2 = 0.1216216;
    float w3 = 0.054054;
    float w4 = 0.016216;
    vec4 c = texture(srcTex, inUv) * w0;
    for (int i = 1; i < 5; i++) {
        float wi = i == 1 ? w1 : (i == 2 ? w2 : (i == 3 ? w3 : w4));
        vec2 o = dir * texel * float(i);
        c += texture(srcTex, inUv + o) * wi;
        c += texture(srcTex, inUv - o) * wi;
    }
    outColor = c;
}

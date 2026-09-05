#version 450
layout(push_constant) uniform Push { mat4 mvp; vec4 chunkOrigin; vec4 eye; vec4 fog; } push;
layout(binding = 0) uniform sampler2D atlas;
layout(binding = 1) uniform sampler2D lightmap;
layout(location = 0) in vec3 vColor;
layout(location = 1) in vec2 vUV;
layout(location = 2) in vec2 vLM;
layout(location = 3) in float vDist;
layout(location = 0) out vec4 outColor;
void main() {
    vec4 base = texture(atlas, vUV);
    if (base.a < 0.1) discard;
    vec3 light = texture(lightmap, vLM.yx / 256.0 + vec2(0.004)).rgb;
    vec3 c = base.rgb * vColor * light;
    float f = clamp((vDist - push.eye.w) / max(push.fog.x - push.eye.w, 0.001), 0.0, 1.0);
    outColor = vec4(mix(c, push.fog.yzw, f), 1.0);
}

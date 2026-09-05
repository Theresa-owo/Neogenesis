#version 450
layout(push_constant) uniform Push { mat4 mvp; vec4 chunkOrigin; vec4 eye; vec4 fog; } push;
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;
layout(location = 3) in vec2 inLM;
layout(location = 0) out vec3 vColor;
layout(location = 1) out vec2 vUV;
layout(location = 2) out vec2 vLM;
layout(location = 3) out float vDist;
void main() {
    // chunkOrigin is camera-relative (origin - eye, computed in double on the CPU)
    vec3 viewPos = push.chunkOrigin.xyz + inPos;
    gl_Position = push.mvp * vec4(viewPos, 1.0);
    vColor = inColor.rgb;
    vUV = inUV;
    vLM = inLM;
    vDist = length(viewPos);
}

#version 450
layout(push_constant) uniform Push { mat4 mvp; vec4 chunkOrigin; } push;
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUV;
layout(location = 0) out vec3 vColor;
layout(location = 1) out vec2 vUV;
void main() {
    gl_Position = push.mvp * vec4(push.chunkOrigin.xyz + inPos, 1.0);
    vColor = inColor.rgb;
    vUV = inUV;
}

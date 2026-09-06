#version 450
layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inTint;
layout(location = 3) in vec4 inRect;    // w, h, radius, mode
layout(location = 4) in vec4 inGradEnd;
layout(location = 5) in vec4 inBorder;
layout(location = 0) out vec2 vUv;
layout(location = 1) out vec4 vTint;
layout(location = 2) out vec4 vRect;
layout(location = 3) out vec4 vGradEnd;
layout(location = 4) out vec4 vBorder;
layout(location = 5) out vec2 vScreenUv;
void main() {
    gl_Position = push.ortho * vec4(inPos, 0.0, 1.0);
    vUv = inUv;
    vTint = inTint;
    vRect = inRect;
    vGradEnd = inGradEnd;
    vBorder = inBorder;
    vScreenUv = inPos / push.params0.xy;
}

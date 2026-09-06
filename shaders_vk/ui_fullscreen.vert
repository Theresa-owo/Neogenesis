#version 450
layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
layout(location = 0) out vec2 outUv;
void main() {
    vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    outUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}

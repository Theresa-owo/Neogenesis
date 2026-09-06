#version 450
layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
layout(binding = 0) uniform sampler2D panorama;
layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;
const float PI = 3.141592653589793;
void main() {
    float t = push.params0.z;
    float aspect = push.params0.w;
    vec2 ndc = inUv * 2.0 - 1.0;
    float tanHalf = 1.15; // ~98 degrees vertical span, close to vanilla's 120-degree crop
    // GL-style eye ray (+Y up, looking down -Z), pulled back into the
    // orbit space: undo the camera rotations (pitch about X, yaw about
    // Y — same speed/formula as GuiMainMenu), then undo the fixed
    // base = RotX(180)*RotZ(90) via its transpose (y, x, -z).
    vec3 d = normalize(vec3(ndc.x * tanHalf * aspect, ndc.y * tanHalf, -1.0));
    float pitch = radians(20.0 + 25.0 * sin(t * PI / 10.0));
    float yaw = t * -0.0349;
    float cp = cos(-pitch), sp = sin(-pitch);
    d = vec3(d.x, d.y * cp - d.z * sp, d.y * sp + d.z * cp);
    float cy = cos(-yaw), sy = sin(-yaw);
    d = vec3(d.x * cy + d.z * sy, d.y, -d.x * sy + d.z * cy);
    d = vec3(d.y, d.x, -d.z);
    float theta = acos(clamp(d.y, -1.0, 1.0));
    float phi = atan(d.z, d.x);
    vec2 uv = vec2(phi / (2.0 * PI) + 0.5, theta / PI);
    outColor = vec4(texture(panorama, uv).rgb, 1.0);
}

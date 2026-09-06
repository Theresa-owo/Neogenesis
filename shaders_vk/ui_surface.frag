#version 450
layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
layout(binding = 0) uniform sampler2D backdrop;
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vTint;
layout(location = 2) in vec4 vRect;
layout(location = 3) in vec4 vGradEnd;
layout(location = 4) in vec4 vBorder;
layout(location = 5) in vec2 vScreenUv;
layout(location = 0) out vec4 outColor;

float sdRoundedBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float hashNoise(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    float mode = vRect.w;
    vec2 half2 = vRect.xy * 0.5;
    vec2 local = vUv - half2;

    if (mode < 0.5) {
        outColor = vTint;
        return;
    }

    float d = sdRoundedBox(local, half2, vRect.z);
    if (d > 24.0) discard;

    if (mode < 1.5) {
        // Solid / vertical-gradient rounded rect with hairline border.
        float aa = 1.0 - smoothstep(-0.75, 0.75, d);
        float t = clamp(vUv.y / max(vRect.y, 1.0), 0.0, 1.0);
        vec3 fill = mix(vTint.rgb, vGradEnd.rgb, t);
        float fillA = mix(vTint.a, vGradEnd.a, t);
        float borderA = (1.0 - smoothstep(0.55, 1.45, abs(d + 1.0))) * vBorder.a;
        vec3 c = mix(fill, vBorder.rgb, borderA);
        float a = max(fillA, borderA) * aa;
        outColor = vec4(c, a);
        return;
    }

    if (mode < 2.5) {
        // Frosted glass: blurred backdrop in screen space, tinted, film grain.
        vec3 bg = texture(backdrop, vScreenUv).rgb;
        float n = (hashNoise(floor(gl_FragCoord.xy)) - 0.5) * 0.028;
        vec3 c = bg * vTint.rgb * 2.15 + n;
        float aa = 1.0 - smoothstep(-0.75, 0.75, d);
        float borderA = (1.0 - smoothstep(0.55, 1.45, abs(d + 1.0))) * vBorder.a;
        c = mix(c, vBorder.rgb, borderA);
        float a = clamp(vTint.a + borderA, 0.0, 1.0) * aa;
        outColor = vec4(c, a);
        return;
    }

    // Mode 3: soft drop shadow (alpha falls off with SDF distance).
    float fall = exp(-max(d, 0.0) * 0.42);
    float a = vTint.a * fall;
    outColor = vec4(vTint.rgb, a);
}

package net.theresa.ui.render

import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo
import org.lwjgl.vulkan.VkShaderModuleCreateInfo
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VkDevice
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * NeoUI shader sources + runtime GLSL->SPIR-V compilation.
 *
 * Sources live as embedded strings (fallback) and are overridden by
 * `shaders_vk/ui_<name>.vert|frag` on disk when present, mirroring the terrain
 * F9 hot-reload flow. The embedded source is written to disk on first run so
 * the files exist for editing.
 */
object UiShaders {

    enum class Stage(val ext: String, val shadercKind: Int) {
        VERTEX("vert", Shaderc.shaderc_glsl_vertex_shader),
        FRAGMENT("frag", Shaderc.shaderc_glsl_fragment_shader)
    }

    /** Shared push constants for every NeoUI pipeline (96 bytes, VS|FS). */
    const val PUSH_SIZE = 96

    // ---------------------------------------------------------------------
    // Fullscreen triangle vertex shader, shared by panorama/blur passes.
    // ---------------------------------------------------------------------
    val FULLSCREEN_VERT = """
        #version 450
        layout(push_constant) uniform Push { mat4 ortho; vec4 params0; vec4 params1; } push;
        layout(location = 0) out vec2 outUv;
        void main() {
            vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
            outUv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent() + "\n"

    /**
     * Samples the baked equirectangular panorama. The ray construction mirrors
     * GuiMainMenu.drawPanorama's modelview chain exactly:
     *   eye ray -> RotY(-yaw)*RotX(-pitch) -> base^T (RotZ(-90)*RotX(180))
     * with vanilla's pitch = 20+25*sin(t/400) deg and yaw speed -2 deg/s.
     */
    val PANORAMA_FRAG = """
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
    """.trimIndent() + "\n"

    /** Separable 9-tap gaussian; direction * spread comes in via params1.zw. */
    val BLUR_FRAG = """
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
    """.trimIndent() + "\n"

    /**
     * UI surface quads: solid/gradient rounded rects, frosted glass (samples the
     * blurred backdrop in screen space + noise), and soft SDF shadows.
     * uv carries LOCAL pixel coordinates for SDF modes, texture coords for
     * textured modes.
     */
    val SURFACE_VERT = """
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
    """.trimIndent() + "\n"

    val SURFACE_FRAG = """
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
    """.trimIndent() + "\n"

    /** Glyph quads: single-channel atlas (alpha coverage), tinted per vertex. */
    val TEXT_VERT = SURFACE_VERT

    val TEXT_FRAG = """
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
    """.trimIndent() + "\n"

    /** Reads shaders_vk/ui_<name>.<ext> when present, writing the embedded fallback to disk. */
    fun load(name: String, stage: Stage, embedded: String): String {
        return try {
            val path: Path = Paths.get("shaders_vk", "ui_$name.${stage.ext}")
            if (Files.exists(path)) {
                String(Files.readAllBytes(path), Charsets.UTF_8)
            } else {
                Files.createDirectories(path.parent)
                Files.write(path, embedded.toByteArray(Charsets.UTF_8))
                embedded
            }
        } catch (e: Exception) {
            System.err.println("[NeoUI] shader file ui_$name.${stage.ext} unavailable, using embedded: $e")
            embedded
        }
    }

    fun compile(glsl: String, stage: Stage, name: String): ByteArray {
        val compiler = Shaderc.shaderc_compiler_initialize()
        val options = Shaderc.shaderc_compile_options_initialize()
        var result = 0L
        try {
            Shaderc.shaderc_compile_options_set_target_env(
                options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_0
            )
            result = Shaderc.shaderc_compile_into_spv(compiler, glsl, stage.shadercKind, name, "main", options)
            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw RuntimeException(
                    "Failed to compile NeoUI shader '$name' (status "
                            + Shaderc.shaderc_result_get_compilation_status(result) + "):"
                            + System.lineSeparator() + Shaderc.shaderc_result_get_error_message(result)
                )
            }
            val bytes = Shaderc.shaderc_result_get_bytes(result)
                ?: throw RuntimeException("shaderc produced no SPIR-V bytes for '$name'")
            val spv = ByteArray(bytes.remaining())
            bytes.get(spv)
            return spv
        } finally {
            if (result != 0L) Shaderc.shaderc_result_release(result)
            Shaderc.shaderc_compile_options_release(options)
            Shaderc.shaderc_compiler_release(compiler)
        }
    }

    fun createModule(device: VkDevice, spv: ByteArray): Long =
        MemoryStack.stackPush().use { stack ->
            val pCode = stack.malloc(spv.size)
            pCode.put(spv)
            pCode.flip()
            val info = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(pCode)
            val module = stack.mallocLong(1)
            net.theresa.render.vulkan.VulkanContext.check(
                vkCreateShaderModule(device, info, null, module), "vkCreateShaderModule (NeoUI)"
            )
            module.get(0)
        }
}

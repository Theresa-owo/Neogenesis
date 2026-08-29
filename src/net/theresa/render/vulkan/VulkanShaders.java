package net.theresa.render.vulkan;

import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

/**
 * Runtime GLSL -> SPIR-V compilation via the shaderc native library
 * (org.lwjgl:lwjgl-shaderc::natives-windows, loaded lazily by LWJGL on first call).
 */
public final class VulkanShaders {

    public enum Stage {
        VERTEX(Shaderc.shaderc_glsl_vertex_shader),
        FRAGMENT(Shaderc.shaderc_glsl_fragment_shader);

        final int shadercKind;

        Stage(int shadercKind) {
            this.shadercKind = shadercKind;
        }
    }

    private VulkanShaders() {
    }

    /**
     * Compiles GLSL source to Vulkan 1.0 SPIR-V. Throws a RuntimeException carrying
     * the full shaderc diagnostic log when compilation fails.
     */
    public static byte[] compileGlslToSpv(String glslSource, Stage stage, String fileName) {
        long compiler = 0;
        long options = 0;
        long result = 0;
        try {
            compiler = Shaderc.shaderc_compiler_initialize();
            options = Shaderc.shaderc_compile_options_initialize();
            Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_0);

            result = Shaderc.shaderc_compile_into_spv(compiler, glslSource, stage.shadercKind, fileName, "main", options);

            if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
                throw new RuntimeException("Failed to compile shader '" + fileName + "' to SPIR-V (status "
                    + Shaderc.shaderc_result_get_compilation_status(result) + "): "
                    + System.lineSeparator() + Shaderc.shaderc_result_get_error_message(result));
            }

            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            byte[] spv = new byte[bytes.remaining()];
            bytes.get(spv);
            return spv;
        } finally {
            // Result must be released before the compiler that produced it.
            if (result != 0) {
                Shaderc.shaderc_result_release(result);
            }
            if (options != 0) {
                Shaderc.shaderc_compile_options_release(options);
            }
            if (compiler != 0) {
                Shaderc.shaderc_compiler_release(compiler);
            }
        }
    }
}

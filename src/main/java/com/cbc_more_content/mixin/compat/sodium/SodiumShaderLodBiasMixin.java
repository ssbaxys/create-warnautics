package com.cbc_more_content.mixin.compat.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Repairs a shader produced by the Veil + Sodium + Create Deep Seas chain.
 * <p>
 * Veil preprocesses Sodium's opaque chunk fragment shader when its post effects
 * are reloaded. In the affected combination the use of {@code lodBias} survives,
 * but its local declaration does not, so the next world frame crashes in
 * {@code GlShader} with C1503 instead of merely disabling that post effect.
 * Working at the final GL upload point makes this independent of modifier order:
 * every earlier shader preprocessor has already finished.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlShader", remap = false)
public abstract class SodiumShaderLodBiasMixin {
    private static final String SODIUM_LOD_DECLARATION =
            "\n    float lodBias = _material_use_mips(v_Material)"
                    + " ? 0.0 : float(-MAX_TEXTURE_LOD_BIAS);";
    private static final String SAFE_LOD_DECLARATION =
            "\n    float lodBias = 0.0;";

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/ShaderWorkarounds;"
                            + "safeShaderSource(ILjava/lang/CharSequence;)V"),
            index = 1,
            require = 0)
    private CharSequence cbcMoreContent$restoreMissingLodBias(CharSequence source) {
        if (source == null) {
            return null;
        }
        String shader = source.toString();
        if (!shader.contains("lodBias")
                || shader.matches("(?s).*\\bfloat\\s+lodBias\\b.*")
                || !shader.matches("(?s).*texture\\s*\\(\\s*u_BlockTex\\s*,"
                        + "\\s*v_TexCoord\\s*,\\s*lodBias\\s*\\).*")) {
            return source;
        }

        int main = shader.indexOf("void main");
        int body = main < 0 ? -1 : shader.indexOf('{', main);
        if (body < 0) {
            return source;
        }
        String declaration = shader.contains("MAX_TEXTURE_LOD_BIAS")
                        && shader.contains("_material_use_mips")
                ? SODIUM_LOD_DECLARATION
                : SAFE_LOD_DECLARATION;
        return shader.substring(0, body + 1)
                + declaration
                + shader.substring(body + 1);
    }
}

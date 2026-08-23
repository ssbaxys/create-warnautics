package com.cbc_more_content.mixin.compat.sodium;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Repairs a shader produced by the Veil + Sodium + Create Deep Seas chain.
 * <p>
 * Sodium's opaque chunk fragment shader declares {@code lodBias} as the first
 * statement of {@code main()}. When a detonation activates Veil's dynamic
 * buffers, Veil recompiles Sodium's chunk shaders and its preprocessor inserts
 * {@code ... = texture(u_BlockTex, v_TexCoord, lodBias) ...} as the second
 * statement of {@code main()}. Deep Seas' {@code SodiumShaderLoaderMixin}
 * independently inserts its water-occlusion block at the top of {@code main()},
 * so when both are present the declaration is pushed past Veil's injection and
 * the shader references {@code lodBias} before declaring it, crashing in
 * {@code GlShader} with C1503 instead of merely disabling that post effect.
 * <p>
 * Working at the final GL upload point makes this independent of modifier
 * order: every earlier shader preprocessor has already finished. The shader is
 * repaired by moving the declaration ahead of the first use (re-inserting it at
 * the top of {@code main()} and dropping the misplaced original), so it also
 * covers the case where Veil's re-serialization dropped the declaration
 * entirely.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlShader", remap = false)
public abstract class SodiumShaderLodBiasMixin {
    private static final String SODIUM_LOD_DECLARATION =
            "\n    float lodBias = _material_use_mips(v_Material)"
                    + " ? 0.0 : float(-MAX_TEXTURE_LOD_BIAS);";
    private static final String SAFE_LOD_DECLARATION =
            "\n    float lodBias = 0.0;";

    private static final Pattern LOD_BIAS_DECLARATION =
            Pattern.compile("\\bfloat\\s+lodBias\\b");
    private static final Pattern LOD_BIAS_DECLARATION_STATEMENT =
            Pattern.compile("\\bfloat\\s+lodBias\\s*=\\s*[^;]+;");

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
        if (!shader.contains("lodBias")) {
            return source;
        }

        int main = shader.indexOf("void main");
        int body = main < 0 ? -1 : shader.indexOf('{', main);
        if (body < 0) {
            return source;
        }

        String bodySource = shader.substring(body + 1);
        int firstUse = bodySource.indexOf("lodBias");
        if (firstUse < 0) {
            return source;
        }
        Matcher declaration = LOD_BIAS_DECLARATION.matcher(bodySource);
        int declarationStart = declaration.find() ? declaration.start() : -1;
        if (declarationStart >= 0 && declarationStart <= firstUse) {
            // A declaration already precedes every use; nothing to repair.
            return source;
        }

        // The declaration is missing entirely, or (Veil + Deep Seas) it was
        // pushed after the first use. Re-insert it at the top of main() and
        // drop the misplaced original so it cannot redefine the new one.
        String lodBiasDeclaration = shader.contains("MAX_TEXTURE_LOD_BIAS")
                        && shader.contains("_material_use_mips")
                ? SODIUM_LOD_DECLARATION
                : SAFE_LOD_DECLARATION;
        if (declarationStart >= 0) {
            bodySource = LOD_BIAS_DECLARATION_STATEMENT.matcher(bodySource).replaceFirst("");
        }
        return shader.substring(0, body + 1)
                + lodBiasDeclaration
                + bodySource;
    }
}

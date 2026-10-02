package com.cbc_more_content.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import net.neoforged.fml.loading.LoadingModList;
import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Repairs only Veil's clear handler mistakenly attached to setupRenderState (4.1.4 / 4.3.2). */
final class VeilDynamicBufferRepair {
    private static final String MIXIN = "foundry.veil.mixin.dynamicbuffer.client.DynamicBufferLevelRendererMixin";
    private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String HANDLER =
            "(Lnet/minecraft/client/renderer/RenderStateShard$OutputStateShard;L" + OPERATION + ";)V";
    private static Boolean needed;

    static boolean isNeeded() {
        if (needed != null) {
            return needed;
        }
        needed = false;
        var file = LoadingModList.get().getModFileById("veil");
        if (file == null) {
            return false;
        }
        try (var input = Files.newInputStream(file.getFile().findResource(MIXIN.replace('.', '/') + ".class"))) {
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            needed = node.methods.stream()
                    .anyMatch(
                            method -> method.name.equals("clearState")
                                    && method.desc.equals(HANDLER)
                                    && contains(
                                            method.visibleAnnotations,
                                            "Lnet/minecraft/client/renderer/RenderStateShard$OutputStateShard;setupRenderState()V"));
        } catch (IOException failure) {
            LogManager.getLogger("Warnautics/VeilCompat").warn("Cannot inspect Veil's weather buffer handler", failure);
        }
        return needed;
    }

    static void removeMisplacedClear(ClassNode target) {
        int repaired = 0;
        for (var method : target.methods) {
            if (!method.desc.equals(HANDLER) || !contains(method.visibleAnnotations, MIXIN)) {
                continue;
            }
            boolean closesWeather = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("foundry/veil/impl/client/render/dynamicbuffer/DynamicBufferShard")
                        && call.name.equals("clearRenderState")) {
                    closesWeather = true;
                }
            }
            if (!closesWeather) {
                continue;
            }
            // Keep MixinExtras' operation chain intact, forwarding to Veil's correct setup handler.
            // The replacement mixin closes weather at the actual clearRenderState call instead.
            method.instructions.clear();
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) {
                method.localVariables.clear();
            }
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
            method.instructions.add(new InsnNode(Opcodes.ICONST_1));
            method.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            method.instructions.add(new InsnNode(Opcodes.DUP));
            method.instructions.add(new InsnNode(Opcodes.ICONST_0));
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            method.instructions.add(new InsnNode(Opcodes.AASTORE));
            method.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKEINTERFACE, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
            method.instructions.add(new InsnNode(Opcodes.POP));
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            method.maxStack = 5;
            method.maxLocals = 3;
            repaired++;
        }
        if (repaired != 1) {
            throw new IllegalStateException("Expected one misplaced Veil weather clear handler, found " + repaired);
        }
        LogManager.getLogger("Warnautics/VeilCompat").info("Repaired Veil weather buffer setup/clear pairing");
    }

    private static boolean contains(Object value, String expected) {
        if (value instanceof AnnotationNode annotation) {
            return contains(annotation.values, expected);
        }
        if (value instanceof List<?> values) {
            return values.stream().anyMatch(entry -> contains(entry, expected));
        }
        return expected.equals(value);
    }
}

package dev.logos.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.util.Annotations;

/** Which merged methods count as a cosmetic injector that missed. */
class CosmeticMixinPluginTest {
    private static final String COSMETIC = "dev.logos.mixin.CloudRendererMixin";
    private static final String OWNER = "net/minecraft/Target";

    private static MethodNode method(String name) {
        return new MethodNode(Opcodes.ACC_PRIVATE, name, "()V", null, null);
    }

    private static MethodNode merged(String name, String mixin) {
        MethodNode method = method(name);
        Annotations.setVisible(method, MixinMerged.class, "mixin", mixin);
        return method;
    }

    private static ClassNode target(MethodNode... methods) {
        ClassNode node = new ClassNode();
        node.name = OWNER;
        node.methods.addAll(List.of(methods));
        return node;
    }

    private static List<String> unapplied(ClassNode target) {
        return CosmeticMixinPlugin.unapplied(target, Set.of(COSMETIC));
    }

    @Test
    void aHandlerNothingCallsMissed() {
        assertEquals(List.of(COSMETIC + "#logos$cloudTime"), unapplied(target(merged("localvar$zza000$logos$cloudTime", COSMETIC))));
    }

    @Test
    void aHandlerSomethingCallsApplied() {
        MethodNode caller = method("render");
        caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OWNER, "handler$zza000$logos$aura", "()V", false));
        assertEquals(List.of(), unapplied(target(caller, merged("handler$zza000$logos$aura", COSMETIC))));
    }

    @Test
    void aHandlerReachedThroughALambdaApplied() {
        MethodNode caller = method("render");
        Handle handle = new Handle(Opcodes.H_INVOKESPECIAL, OWNER, "wrapOperation$zza000$logos$body", "()V", false);
        Handle factory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory", "()V", false);
        caller.instructions.add(new InvokeDynamicInsnNode("call", "()V", factory, handle));
        assertEquals(List.of(), unapplied(target(caller, merged("wrapOperation$zza000$logos$body", COSMETIC))));
    }

    @Test
    void onlyInjectorsOfThoseMixinsCount() {
        assertEquals(List.of(), unapplied(target(
                merged("logos$aura", COSMETIC),                               // a plain method the mixin adds
                merged("handler$zzb000$logos$collide", "dev.logos.mixin.EntityMixin"),  // not a cosmetic mixin
                merged("handler$zzc000$sodium$render", "other.Mixin"),
                method("render"))));
    }
}

package dev.logos.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;
import org.spongepowered.asm.util.Annotations;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;

/**
 * Logs a warning when a mixin of {@code logos.cosmetic.mixins.json} found nowhere to inject. That config lets them miss,
 * since a missing visual effect is no reason to stop the game, and Mixin itself says nothing when one does. A handler is
 * merged into its target either way, so one that nothing in the target calls is one that missed. Handlers are told apart
 * by their name: every {@code logos$...} method a mixin injects with comes out as {@code prefix$id$logos$...}.
 */
public final class CosmeticMixinPlugin implements IMixinConfigPlugin, IExtension {
    private static final Logger LOGGER = LoggerFactory.getLogger(CosmeticMixinPlugin.class);

    private final Set<String> mixins = new HashSet<>();

    /** The handlers of {@code mixins} merged into {@code target} that nothing in it calls, as {@code mixin#handler}. */
    static List<String> unapplied(ClassNode target, Set<String> mixins) {
        List<MethodNode> handlers = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        for (MethodNode method : target.methods) {
            AnnotationNode merged = Annotations.getVisible(method, MixinMerged.class);
            if (merged != null && mixins.contains(Annotations.<String>getValue(merged, "mixin")) && method.name.indexOf("logos$") > 0) handlers.add(method);
        }
        if (handlers.isEmpty()) return missed;
        Set<String> called = new HashSet<>();
        for (MethodNode method : target.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) called.add(call.name);
                else if (insn instanceof InvokeDynamicInsnNode dynamic) {
                    for (Object arg : dynamic.bsmArgs) if (arg instanceof Handle handle) called.add(handle.getName());
                }
            }
        }
        for (MethodNode handler : handlers) {
            if (called.contains(handler.name)) continue;
            String mixin = Annotations.getValue(Annotations.getVisible(handler, MixinMerged.class), "mixin");
            missed.add(mixin + "#" + handler.name.substring(handler.name.indexOf("logos$")));
        }
        return missed;
    }

    @Override
    public void onLoad(String mixinPackage) {
        try {
            // MixinExtras applies most of these injectors in an extension of its own: the check has to come after it.
            MixinExtrasBootstrap.init();
            if (MixinEnvironment.getCurrentEnvironment().getActiveTransformer() instanceof IMixinTransformer transformer
                    && transformer.getExtensions() instanceof Extensions extensions) {
                extensions.add(this);
                return;
            }
            LOGGER.warn("Can't watch the cosmetic mixins: no mixin transformer to watch. One that misses will go unreported.");
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Can't watch the cosmetic mixins. One that misses will go unreported.", e);
        }
    }

    @Override
    public void postApply(ITargetClassContext context) {
        ClassNode target = context.getClassNode();
        for (String handler : unapplied(target, mixins)) {
            LOGGER.warn("{} found nowhere to inject in {}, probably because another mod changed it. That visual effect is off; nothing else is affected.",
                    handler, target.name.replace('/', '.'));
        }
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        mixins.add(mixinClassName);
        return true;
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {}

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.Mannequin;

/** A possessing player's body is a mannequin labelled as such (see dev.logos.runtime.Possession). */
@Mixin(Mannequin.class)
public interface MannequinAccessor {
    @Invoker("setDescription")
    void logos$setDescription(Component description);
}

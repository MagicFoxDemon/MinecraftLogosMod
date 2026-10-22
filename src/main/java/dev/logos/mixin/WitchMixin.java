package dev.logos.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dev.logos.runtime.Familiars;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.raid.Raider;

/** Familiar witches use vanilla support potions for their master and offensive potions for raider foes. */
@Mixin(Witch.class)
abstract class WitchMixin {
    @Definition(id = "Raider", type = Raider.class)
    @Expression("? instanceof Raider")
    @ModifyExpressionValue(method = "performRangedAttack", at = @At("MIXINEXTRAS:EXPRESSION"))
    private boolean logos$healMasterInsteadOfRaiders(boolean raider, LivingEntity target, float power) {
        UUID master = Familiars.masterOf((Witch) (Object) this);
        return master == null ? raider : master.equals(target.getUUID());
    }
}

package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.logos.runtime.AreaWeather;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.WeatherAttributes;
import net.minecraft.world.level.Level;

/** On a client, the weather layer of sky colour and light sees area weather near the camera; a server's sees the world's. */
@Mixin(EnvironmentAttributeSystem.class)
abstract class EnvironmentAttributeSystemMixin {
    @WrapOperation(method = "addDefaultLayers", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/attribute/WeatherAttributes$WeatherAccess;from(Lnet/minecraft/world/level/Level;)Lnet/minecraft/world/attribute/WeatherAttributes$WeatherAccess;"))
    private static WeatherAttributes.WeatherAccess logos$areaWeather(Level level, Operation<WeatherAttributes.WeatherAccess> original) {
        return AreaWeather.access(level, original.call(level));
    }
}

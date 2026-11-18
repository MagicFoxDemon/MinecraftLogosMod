package dev.logos.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import dev.logos.runtime.AreaWeather;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The sky colour, cloud colour and the sky light's darkening see area weather near the camera. On
 * 1.21.1 these are the client level's own methods, which read the world's rain and thunder (26.1 layers them as
 * environment attributes).
 */
@Mixin(ClientLevel.class)
abstract class EnvironmentAttributeSystemMixin {
    @ModifyExpressionValue(method = {"getSkyColor", "getCloudColor", "getSkyDarken"},
            at = @At(value = "INVOKE", target = "getRainLevel(F)F"))
    private float logos$areaRain(float world) {
        return AreaWeather.skyRain((ClientLevel) (Object) this, world);
    }

    @ModifyExpressionValue(method = {"getSkyColor", "getCloudColor", "getSkyDarken"},
            at = @At(value = "INVOKE", target = "getThunderLevel(F)F"))
    private float logos$areaThunder(float world) {
        return AreaWeather.skyThunder((ClientLevel) (Object) this, world);
    }
}

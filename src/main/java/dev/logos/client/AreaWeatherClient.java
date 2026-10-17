package dev.logos.client;

import dev.logos.LogosMod;
import dev.logos.runtime.AreaWeather;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Feeds {@link AreaWeather} the camera every client tick, and forgets every zone on leaving a world. */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
final class AreaWeatherClient {
    private AreaWeatherClient() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !mc.isPaused()) AreaWeather.clientTick(mc.level, mc.gameRenderer.getMainCamera().position());
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        AreaWeather.clearClient();
    }
}

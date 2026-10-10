package dev.logos.client;

import dev.logos.LogosMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client entry point: the in-game config screen (Mods → Logos → Config). */
@Mod(value = LogosMod.MODID, dist = Dist.CLIENT)
public final class LogosModClient {
    public LogosModClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}

package dev.logos.compat.tlm.client;

import com.github.tartaricacid.touhoulittlemaid.api.event.AddJadeInfoEvent;

import dev.logos.runtime.CreatureMana;
import net.neoforged.bus.api.SubscribeEvent;

/**
 * A tamed maid's mana in Jade, under the lines the maid mod adds there. Registered by
 * {@link dev.logos.compat.tlm.LogosMaidExtension} on a client that has Jade, as this names Jade's classes.
 */
public final class MaidManaJade {
    private MaidManaJade() {}

    @SubscribeEvent
    static void onJade(AddJadeInfoEvent event) {
        CreatureMana pool = MaidManaView.shown(event.getMaid());
        if (pool != null) event.getTooltip().add(MaidManaView.amount(pool));
    }
}

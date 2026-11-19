package dev.logos.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

import dev.logos.compat.tlm.client.MaidManaJade;
import dev.logos.compat.tlm.client.MaidManaView;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.ManaVessels;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Logos with Touhou Little Maid: the maid mod finds this class by its annotation and makes it only when it is
 * installed, so nothing here loads without it (and Logos does not depend on it, nor it on Logos). It adds the 施法
 * work mode ({@link SpellcastingTask}), gives tamed maids a mana pool ({@link MaidMana}) that free mana drifts into
 * as into a player's, and listens to the maid mod's events ({@link MaidEvents}); its event handlers are registered
 * here rather than by annotation, as the maid mod's guide asks, so they never load on their own. On a client it also
 * shows a maid's mana in her screen and in Jade ({@code client/}).
 */
@LittleMaidExtension
public final class LogosMaidExtension implements ILittleMaid {
    public LogosMaidExtension() {
        ManaVessels.register(MaidMana::vesselOf);
        NeoForge.EVENT_BUS.register(MaidEvents.class);
        NeoForge.EVENT_BUS.register(SummonedMaids.class);
        FreeManaEntity.gatheredBy(EntityMaid.class);
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.register(MaidManaView.class);
            if (ModList.get().isLoaded("jade")) NeoForge.EVENT_BUS.register(MaidManaJade.class);
        }
    }

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new SpellcastingTask());
    }
}

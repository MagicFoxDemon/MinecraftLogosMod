package dev.logos.compat.tlm;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;

import dev.logos.runtime.ManaVessels;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Logos with Touhou Little Maid: the maid mod finds this class by its annotation and makes it only when it is
 * installed, so nothing here loads without it (and Logos does not depend on it, nor it on Logos). It adds the 施法
 * work mode ({@link SpellcastingTask}), gives tamed maids a mana pool ({@link MaidMana}) and listens to the maid
 * mod's events ({@link MaidEvents}); its event handlers are registered here rather than by annotation, as the maid
 * mod's guide asks, so they never load on their own.
 */
@LittleMaidExtension
public final class LogosMaidExtension implements ILittleMaid {
    public LogosMaidExtension() {
        ManaVessels.register(MaidMana::vesselOf);
        NeoForge.EVENT_BUS.register(MaidEvents.class);
        NeoForge.EVENT_BUS.register(SummonedMaids.class);
    }

    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new SpellcastingTask());
    }
}

package dev.logos.mixin;

import dev.logos.port.DataTrades;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager of a profession with data trades (the warlock) takes its level's trades from them, as on 26.1 (dev.logos.port.DataTrades). */
@Mixin(Villager.class)
public abstract class VillagerMixin {
    @Inject(method = "updateTrades", at = @At("HEAD"), cancellable = true)
    private void logos$dataTrades(CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        VillagerData data = self.getVillagerData();
        if (DataTrades.addOffers(data.getProfession(), data.getLevel(), self.getOffers(), self.getRandom(), self.registryAccess())) ci.cancel();
    }
}

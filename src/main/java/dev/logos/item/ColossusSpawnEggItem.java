package dev.logos.item;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.runtime.Colossi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** A vanilla spawn egg that raises the command's full-mana guardian and binds it to its user. */
public final class ColossusSpawnEggItem extends BoundSpawnEggItem {
    public ColossusSpawnEggItem(Properties props) {
        super(props);
    }

    @Override
    EntityType<?> boundType() {
        return LogosMod.ALCHEMICAL_COLOSSUS.get();
    }

    @Override
    boolean allowed() {
        return LogosConfig.maxColossi() > 0;
    }

    @Override
    void prepare(Entity entity, @Nullable ServerPlayer owner) {
        if (entity instanceof dev.logos.runtime.AlchemicalColossus colossus) Colossi.prepareSummoned(colossus, owner);
    }
}

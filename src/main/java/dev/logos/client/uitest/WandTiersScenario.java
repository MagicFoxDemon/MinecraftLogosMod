package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

/** Every registered higher-tier wand, including the twelve-slot creative one. */
@LDLRegisterClient(name = "logos_wand_tiers", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public final class WandTiersScenario extends TierEquipmentScenario {
    @Override
    protected boolean staffs() { return false; }
}

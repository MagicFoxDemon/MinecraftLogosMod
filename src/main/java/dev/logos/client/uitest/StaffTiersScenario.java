package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

/** Every registered higher-tier staff and its continuously animated orbit. */
@LDLRegisterClient(name = "logos_staff_tiers", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public final class StaffTiersScenario extends TierEquipmentScenario {
    @Override
    protected boolean staffs() { return true; }
}

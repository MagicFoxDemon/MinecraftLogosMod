package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The fire charge (CHARGE_VFX.md) through the whole {@link ChargeTour}. */
@LDLRegisterClient(name = "logos_fire_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FireChargeScenario extends ChargeTour {
    @Override
    protected Element element() { return Element.FIRE; }
}

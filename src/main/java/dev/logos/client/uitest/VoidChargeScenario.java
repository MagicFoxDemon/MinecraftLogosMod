package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The void charge (CHARGE_VFX.md) through the whole {@link ChargeTour}. */
@LDLRegisterClient(name = "logos_void_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoidChargeScenario extends ChargeTour {
    @Override
    protected Element element() { return Element.VOID; }
}

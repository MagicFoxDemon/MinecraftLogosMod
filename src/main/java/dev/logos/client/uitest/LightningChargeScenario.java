package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The lightning charge (CHARGE_VFX.md) through the whole {@link ChargeTour}: the shared core with lightning's arcs. */
@LDLRegisterClient(name = "logos_lightning_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LightningChargeScenario extends ChargeTour {
    @Override
    protected Element element() { return Element.LIGHTNING; }
}

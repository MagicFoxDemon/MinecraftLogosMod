package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The arcane charge (CHARGE_VFX.md) through the whole {@link ChargeTour}: its circles, and runes round the grand and colossal ball. */
@LDLRegisterClient(name = "logos_arcane_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ArcaneChargeScenario extends ChargeTour {
    @Override
    protected Element element() { return Element.ARCANE; }
}

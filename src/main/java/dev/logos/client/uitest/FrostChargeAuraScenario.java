package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/**
 * The frost charge's colossal aura (CHARGE_VFX.md, after NS_Charge_EvokeEnergy_Root, with ice crystals and cold mist)
 * through {@link ChargeAuraEvokeTour}.
 */
@LDLRegisterClient(name = "logos_frost_charge_aura", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FrostChargeAuraScenario extends ChargeAuraEvokeTour {
    @Override
    protected Element element() { return Element.FROST; }
}

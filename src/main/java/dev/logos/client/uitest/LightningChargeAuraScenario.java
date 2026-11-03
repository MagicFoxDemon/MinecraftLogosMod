package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The lightning charge's colossal aura (CHARGE_VFX.md, after NS_Charge_EvokeEnergy_Root) through {@link ChargeAuraEvokeTour}. */
@LDLRegisterClient(name = "logos_lightning_charge_aura", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LightningChargeAuraScenario extends ChargeAuraEvokeTour {
    @Override
    protected Element element() { return Element.LIGHTNING; }
}

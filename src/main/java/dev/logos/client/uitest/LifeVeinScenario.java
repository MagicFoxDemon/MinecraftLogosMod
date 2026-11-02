package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The life vein (VEIN_VFX.md) through the whole {@link VeinTour}, its veins healing. */
@LDLRegisterClient(name = "logos_life_vein", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeVeinScenario extends VeinTour {
    @Override
    protected Element element() { return Element.LIFE; }

    /** Life does no damage (outside addons): its veins heal, which is what makes them life's. */
    @Override
    protected String onHit() { return "(heal target 2)"; }
}

package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The physical vein (VEIN_VFX.md) through the whole {@link VeinTour}. */
@LDLRegisterClient(name = "logos_physical_vein", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PhysicalVeinScenario extends VeinTour {
    @Override
    protected Element element() { return Element.PHYSICAL; }
}

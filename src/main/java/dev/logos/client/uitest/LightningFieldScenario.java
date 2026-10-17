package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The lightning field (FIELD_VFX.md) through the whole {@link FieldTour}. */
@LDLRegisterClient(name = "logos_lightning_field", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LightningFieldScenario extends FieldTour {
    @Override
    protected Element element() { return Element.LIGHTNING; }
}

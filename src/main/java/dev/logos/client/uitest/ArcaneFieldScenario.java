package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The arcane field (FIELD_VFX.md) through the whole {@link FieldTour}. */
@LDLRegisterClient(name = "logos_arcane_field", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ArcaneFieldScenario extends FieldTour {
    @Override
    protected Element element() { return Element.ARCANE; }
}

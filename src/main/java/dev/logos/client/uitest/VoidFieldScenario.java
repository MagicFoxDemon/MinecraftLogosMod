package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The void field (FIELD_VFX.md) through the whole {@link FieldTour}. */
@LDLRegisterClient(name = "logos_void_field", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoidFieldScenario extends FieldTour {
    @Override
    protected Element element() { return Element.VOID; }
}

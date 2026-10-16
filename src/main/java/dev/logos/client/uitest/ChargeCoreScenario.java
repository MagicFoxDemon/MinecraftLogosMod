package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/**
 * The shared charge (CHARGE_VFX.md) in frost's colours through the whole {@link ChargeTour}, then a colossal
 * charge by night in each other element's colours, before any element adds its own layers.
 */
@LDLRegisterClient(name = "logos_charge_core", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class ChargeCoreScenario extends ChargeTour {
    @Override
    protected Element element() { return Element.FROST; }

    @Override
    protected void extra(ScenarioBuilder s) {
        s.runCommand("time set midnight").ticks(5);
        for (Element e : Element.values()) if (e != Element.FROST) play(s, e, 3, "night");
        s.runCommand("time set noon");
    }
}

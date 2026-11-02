package dev.logos.client.uitest;

import java.util.function.BiFunction;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.client.ChargeStyle;
import dev.logos.client.CrystalVeinVisual;
import dev.logos.client.VeinCrystals;
import dev.logos.client.VeinVisual;
import dev.logos.lang.Element;

/**
 * The shared crystals ({@link CrystalVeinVisual}, see VEIN_VFX.md) through the whole {@link VeinTour}, plain: a
 * grey noise in the arcane colours. The lingering vein of every element looks like this once it picks the crystals
 * and before it gives them its own texture and accents; the passing look is the element's own.
 */
@LDLRegisterClient(name = "logos_vein_core", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VeinCoreScenario extends VeinTour {
    @Override
    protected Element element() { return Element.ARCANE; }

    @Override
    protected BiFunction<Element, Boolean, VeinVisual> visuals() {
        return (element, lingers) -> {
            if (!lingers) return VeinVisual.create(element, false);
            ChargeStyle style = ChargeStyle.of(element);
            return new CrystalVeinVisual(element, VeinCrystals.Style.of("ward/noise", style.light()).withTint(style.main()));
        };
    }
}

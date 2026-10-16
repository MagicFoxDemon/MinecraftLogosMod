package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;
import net.minecraft.client.CameraType;
import net.minecraft.world.phys.Vec3;

/**
 * The shared field (FIELD_VFX.md) in arcane's colours through the whole {@link FieldTour}, then by night a
 * field in each other element's colours, before any element adds its own.
 */
@LDLRegisterClient(name = "logos_field_core", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class FieldCoreScenario extends FieldTour {
    @Override
    protected Element element() { return Element.ARCANE; }

    @Override
    protected void extra(ScenarioBuilder s) {
        s.step("from behind", ctx -> camera(CameraType.THIRD_PERSON_BACK)).teleportPlayer(.5, 101, .5, 0, 45)
                .runCommand("time set midnight").ticks(5);
        for (Element e : Element.values()) {
            if (e == Element.ARCANE) continue;
            String name = e.name().toLowerCase();
            s.step("a " + name + " field", ctx -> FieldPreview.start(e, new Vec3(.5, 102, .5), 5, 60, 20, -1));
            shot(at(s, 21), name + "_palette");
            at(s, 90);
        }
        s.runCommand("time set noon");
    }
}

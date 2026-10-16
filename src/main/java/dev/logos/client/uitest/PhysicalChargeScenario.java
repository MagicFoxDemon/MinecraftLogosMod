package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.lang.Element;

/** The physical charge (CHARGE_VFX.md) through the whole {@link ChargeTour}. */
@LDLRegisterClient(name = "logos_physical_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class PhysicalChargeScenario extends ChargeTour {
    @Override
    protected Element element() { return Element.PHYSICAL; }

    /** The colossal release's cross flash comes as the ball bursts, after the tour's release shot: catch it by day and by night. */
    @Override
    protected void extra(ScenarioBuilder s) {
        int letGo = CHARGE[3] + HOLD + 1;
        for (String time : new String[] {"noon", "midnight"}) {
            String name = "physical_colossal_" + (time.equals("noon") ? "day" : "night") + "_cross";
            s.runCommand("time set " + time).ticks(5)
                    .step("charge " + name, ctx -> ChargePreview.release(player(), Element.PHYSICAL, 3, CHARGE[3], HOLD));
            // In stages: each wait must finish within the scene runner's per-step limit.
            at(at(s, CHARGE[3] / 2), CHARGE[3]);
            shot(at(s, letGo + 7), name);
            at(s, letGo + 50).check("the " + name + " charge is over", ctx -> !ChargePreview.busy());
        }
        s.runCommand("time set noon");
    }
}

package dev.logos.client.uitest;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.ClientCharges;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Familiars;
import dev.logos.runtime.ManaLifeform;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * A level-3 lifeform charging a grand fire attack (800 mana, about three seconds): it holds its cast
 * pose with the charge effects in front of it, the client sees the charge, and then it lets go.
 */
@LDLRegisterClient(name = "logos_lifeform_charge", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class LifeformChargeScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);
    private ManaLifeform lf;
    private Husk husk;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "lifeform", "charge").guiScale(2);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .runCommand("difficulty normal")
                .clearArea(ORIGIN.offset(0, 5, 6), 8)
                .fill(ORIGIN.offset(-10, 0, -6), ORIGIN.offset(10, 0, 18), Blocks.SMOOTH_STONE.defaultBlockState())
                // Side-on to the lifeform (at x 0.5, z 1.5) and the husk it attacks (z 6.5).
                .teleportPlayer(ORIGIN.getX() - 4.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 3.5, -110, 5)
                .server("summon a level-3 lifeform with a grand fire page", sc -> {
                    var p = sc.player();
                    PlayerMagic m = PlayerMagic.of(p);
                    m.maxMana = m.mana = 100000;
                    m.trace = false;
                    Casting.cast(p, Casting.compile("(spell :mana 6000 (summon mana_lifeform :level 3 :at (pos-of caster)))"), null, null);
                    lf = (ManaLifeform) Familiars.latest(p, null);
                    if (lf == null) throw new AssertionError("no lifeform");
                    lf.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1.5, ORIGIN.getZ() + 1.5, 180, 0);
                    lf.core.setItem(0, SpellPages.inscribed(PageTier.CHALK, "(spell :name \"焰\" :intent strike :mana 800 (damage (foe) standard fire))", null));
                    husk = EntityType.HUSK.create(p.level(), EntitySpawnReason.COMMAND);
                    husk.snapTo(ORIGIN.getX() + 0.5, ORIGIN.getY() + 1, ORIGIN.getZ() + 6.5, 180, 0);
                    husk.setNoAi(true);
                    husk.setPersistenceRequired();
                    p.level().addFreshEntity(husk);
                    if (!Familiars.order(lf, husk)) throw new AssertionError("it should take the order");
                })
                .ticks(40)
                .server("charging", sc -> {
                    if (!lf.isCasting()) throw new AssertionError("it should be casting at the husk");
                })
                .step("the client sees a grand fire charge", ctx -> {
                    var client = Minecraft.getInstance().level.getEntity(lf.getId());
                    // Point the camera at it, wherever it hovers.
                    var me = Minecraft.getInstance().player;
                    var d = client.position().add(0, client.getBbHeight() / 2, 0).subtract(me.getEyePosition());
                    me.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                    me.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                    var c = ClientCharges.of(client);
                    if (c == null || c.tier != 2 || c.element != Element.FIRE) throw new AssertionError("expected a grand fire charge on the lifeform, got " + c);
                })
                .step("where its spells leave from", ctx -> {
                    var client = Minecraft.getInstance().level.getEntity(lf.getId());
                    // Drawn from its model's cast_point bone once the model has one; estimated until then.
                    LogosMod.LOGGER.info("[cast point] lifeform: drawn {}, used {}, body at {}", dev.logos.client.CastPoints.drawn(client),
                            dev.logos.client.CastPoints.of(client, 1), client.position());
                })
                .screenshot("lifeform_charging")
                .ticks(60)
                .server("let go", sc -> {
                    if (husk.getHealth() >= husk.getMaxHealth()) throw new AssertionError("the fire should have hit the husk");
                })
                .screenshot("lifeform_released")
                // It goes on attacking the husk; with the husk gone, a cast it has begun again stops at once.
                .server("take the husk away", sc -> husk.discard())
                .ticks(10)
                .step("charge gone", ctx -> {
                    if (ClientCharges.of(Minecraft.getInstance().level.getEntity(lf.getId())) != null)
                        throw new AssertionError("the charge should be over on the client");
                });
    }
}

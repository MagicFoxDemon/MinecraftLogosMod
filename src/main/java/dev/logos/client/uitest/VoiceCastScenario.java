package dev.logos.client.uitest;

import java.util.List;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;

import dev.logos.LogosMod;
import dev.logos.client.SpellWheelScreen;
import dev.logos.client.voice.VoiceClient;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Status;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.VoiceCasting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * 言出法随 end to end on a real client: the wheel offers it for a wand with a 言灵坠, holding the real
 * use key listens, and a recognised name goes to the server and casts. Speech is handed in as a
 * made-up word (see {@link VoiceScenario}); the recordings are made in a throwaway folder first.
 */
@LDLRegisterClient(name = "logos_voice_cast", group = "logos", registry = UIScenario.REGISTRY,
        environment = RegistrationEnvironment.DEV_ONLY)
public class VoiceCastScenario implements UIScenario {
    private static final BlockPos ORIGIN = new BlockPos(0, 100, 0);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("logos", "voice", "wand").guiScale(3);
    }

    private static void hold(boolean down) {
        Minecraft.getInstance().options.keyUse.setDown(down);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
                .runCommand("time set noon")
                .runCommand("weather clear")
                .clearArea(ORIGIN.offset(0, 6, 0), 8)
                .fill(ORIGIN.offset(-6, 0, -6), ORIGIN.offset(6, 0, 6), Blocks.SMOOTH_STONE.defaultBlockState())
                .teleportPlayer(0.5, 101, 0.5, 0, 10)
                .step("record 火球 three times, in a throwaway folder", ctx -> {
                    VoiceScenario.throwaway();
                    VoiceClient.withVoices(v -> {
                        try {
                            v.replace("火球", List.of(VoiceScenario.word(VoiceScenario.FIREBALL, 0.98, 1),
                                    VoiceScenario.word(VoiceScenario.FIREBALL, 1.0, 2), VoiceScenario.word(VoiceScenario.FIREBALL, 1.04, 3)));
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    }).join();
                })
                .server("hand over a wand with a 言出法随 page and a 言灵坠", sc -> {
                    PlayerMagic m = PlayerMagic.of(sc.player());
                    m.baseMaxMana = m.mana = 1000;
                    m.trace = false;
                    ItemStack page = SpellPages.inscribed(PageTier.CRYSTAL, VoiceScenario.SPELL, null);
                    page.set(LogosMod.VOICE.get(), "火球");
                    ItemStack wand = new ItemStack(LogosMod.WOOD_WANDS.getFirst().get());
                    wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(page, new ItemStack(LogosMod.VOICE_CHARM.get()))));
                    sc.player().setItemInHand(InteractionHand.MAIN_HAND, wand);
                })
                .ticks(5)
                .step("show the HUD and open the wheel", ctx -> {
                    Minecraft.getInstance().options.hideGui = false;
                    SpellWheelScreen.tryOpen();
                })
                .ticks(5)
                .screenshot("voice_wheel")
                .step("close the wheel", ctx -> Minecraft.getInstance().setScreen(null))
                .server("choose 言出法随", sc -> sc.player().getMainHandItem().set(LogosMod.VOICE_SELECTED.get(), true))
                .ticks(5)
                .step("hold right-click", ctx -> hold(true))
                .ticks(12)
                .server("the wand listens", sc -> {
                    if (!VoiceCasting.listening(sc.player())) throw new AssertionError("holding right-click with 言出法随 chosen should listen");
                })
                .screenshot("voice_listening")
                .step("say 火球", ctx -> VoiceClient.simulateSpeech(VoiceScenario.word(VoiceScenario.FIREBALL, 1.02, 9)))
                .ticks(20)
                .server("火球 was cast", sc -> {
                    // The mana it cost is checked exactly in the world tests; here regeneration refills it meanwhile.
                    if (StatusData.stacks(sc.player(), new Status("#voiced")) != 1) throw new AssertionError("saying 火球 should cast it once");
                })
                .screenshot("voice_cast")
                .ticks(30)
                .server("still holding: the press stays spent", sc -> {
                    if (VoiceCasting.listening(sc.player())) throw new AssertionError("a held right-click must not listen again");
                    if (StatusData.stacks(sc.player(), new Status("#voiced")) != 1) throw new AssertionError("holding on must not cast again");
                })
                .step("let go", ctx -> {
                    hold(false);
                    VoiceClient.simulated = false;
                });
    }
}

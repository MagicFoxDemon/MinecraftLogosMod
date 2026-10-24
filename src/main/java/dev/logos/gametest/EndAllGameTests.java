package dev.logos.gametest;

import dev.logos.lang.Status;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.StatusData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 万咒皆终 (end-all) puts out whole spells with a body in range, nearest first, each for what it has left. */
final class EndAllGameTests {
    private EndAllGameTests() {}

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper h, double x, double z) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(x, 2, z));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1e9;
        m.trace = false;
        return p;
    }

    private static SpellInstance cast(GameTestHelper h, ServerPlayer p, String src, Vec3 rel) {
        SpellInstance inst = Casting.cast(p, Casting.compile(src), null, h.absoluteVec(rel));
        h.assertTrue(inst != null && !inst.dead, "cast failed: " + src + (inst == null ? "" : " — " + inst.endReason()));
        return inst;
    }

    /** Another player's spell needs :players; with it, the whole spell goes, its queued delay with it, for its mana left + 2. */
    static void othersNeedPlayers(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        ServerPlayer a = player(h, 1.5, 1.5), b = player(h, 7.5, 7.5);
        SpellInstance field = cast(h, a, "(spell :mana 100 (field :life 20 :radius 1) (delay 3 (mark caster #late)))", new Vec3(4.5, 3, 4.5));
        cast(h, b, "(spell :mana 200 (end-all :radius 4))", new Vec3(4.5, 3, 4.5));
        h.assertTrue(!field.dead, "without :players another player's spell must survive");
        double left = field.mana;
        SpellInstance stopper = cast(h, b, "(spell :mana 200 :players true (end-all :radius 4))", new Vec3(4.5, 3, 4.5));
        h.assertTrue(field.dead, "with :players the other player's spell should be put out");
        h.assertTrue(Math.abs(stopper.injected - stopper.mana - (2 + left)) < 1e-6, "it should cost the spell's mana left + 2 = " + (2 + left)
                + ", cost " + (stopper.injected - stopper.mana));
        h.runAfterDelay(80, () -> {
            h.assertTrue(StatusData.stacks(a, new Status("#late")) == 0, "the put-out spell's queued delay must never run");
            h.succeed();
        });
    }

    /** The caster's own spells go too; one too strong to pay for is skipped and a weaker one further out still goes. */
    static void ownAndTooStrong(GameTestHelper h) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        ServerPlayer p = player(h, 0.5, 0.5);
        SpellInstance strong = cast(h, p, "(spell :mana 1000 (field :life 20 :radius 1))", new Vec3(4.5, 3, 4.5));
        SpellInstance weak = cast(h, p, "(spell :mana 20 (field :life 20 :radius 1))", new Vec3(7.5, 3, 4.5));
        SpellInstance stopper = cast(h, p, "(spell :mana 60 (end-all :radius 5))", new Vec3(4.5, 3, 4.5));
        h.assertTrue(!strong.dead, "the nearer spell holds more than the stopper can pay: it must survive");
        h.assertTrue(weak.dead, "the weaker spell further out should still be put out (the caster's own spells too)");
        h.assertTrue(!stopper.dead || stopper.finished(), "the stopper itself is not put out by its own rune");
        h.succeed();
    }
}

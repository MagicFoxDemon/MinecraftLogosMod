package dev.logos.gametest;

import java.util.ArrayList;
import java.util.List;

import dev.logos.LogosMod;
import dev.logos.block.InscriptionTableBlock;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.lang.Status;
import dev.logos.runtime.Charging;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.VoiceCasting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * In-world tests for 言出法随 on the server: a wand listens while gathering mana, and casts the
 * spell its player's client says was named; a staff casts it from what it holds; a chant charm
 * keeps casting the named spell. Recognition itself runs on the client and is tested apart.
 */
final class VoiceGameTests {
    /** 100 asked of a crystal page; marks the caster. */
    private static final String BIG = "(spell :name \"蓄\" :mana 100 (mark caster #charged))";
    private static final String SMALL = "(spell :name \"小\" :mana 20 (mark caster #small))";

    private VoiceGameTests() {}

    private static ItemStack voicePage(String src, String name) {
        ItemStack page = SpellPages.inscribed(PageTier.CRYSTAL, src, null);
        page.set(LogosMod.VOICE.get(), name);
        return page;
    }

    /**
     * A player with 1000 of 1000 mana holding {@code holder} (an oak wand unless given), hung with
     * the two voice pages 蓄 (index 0) and 小 (index 1), {@code extra}, and a 言灵坠, with 言出法随 chosen.
     */
    private static ServerPlayer caster(GameTestHelper h, ItemStack holder, ItemStack... extra) {
        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(p);
        m.maxMana = m.mana = 1000;
        m.trace = false;
        List<ItemStack> slots = new ArrayList<>(List.of(voicePage(BIG, "蓄"), voicePage(SMALL, "小")));
        slots.addAll(List.of(extra));
        slots.add(new ItemStack(LogosMod.VOICE_CHARM.get()));
        holder.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(slots));
        holder.set(LogosMod.VOICE_SELECTED.get(), true);
        p.setItemInHand(InteractionHand.MAIN_HAND, holder);
        return p;
    }

    private static ServerPlayer wandCaster(GameTestHelper h, ItemStack... extra) {
        return caster(h, new ItemStack(LogosMod.WOOD_WANDS.getFirst().get()), extra);
    }

    private static boolean press(GameTestHelper h, ServerPlayer p) {
        return p.getMainHandItem().getItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME && p.isUsingItem();
    }

    private static void hold(GameTestHelper h, ServerPlayer p, int ticks) {
        for (int i = 0; i < ticks && p.isUsingItem(); i++) p.getMainHandItem().getItem().onUseTick(h.getLevel(), p, p.getMainHandItem(), p.getUseItemRemainingTicks());
    }

    /**
     * The press is spent: still down, neither listening nor charging, and holding it on takes and casts
     * nothing more (a held right-click must not listen again; only a fresh press does).
     */
    private static boolean idle(GameTestHelper h, ServerPlayer p) {
        double mana = PlayerMagic.of(p).mana;
        int casts = marks(p, "#charged") + marks(p, "#small");
        hold(h, p, 20);
        return p.isUsingItem() && !VoiceCasting.listening(p) && Charging.of(p) == null
                && Math.abs(PlayerMagic.of(p).mana - mana) < 1e-9 && marks(p, "#charged") + marks(p, "#small") == casts;
    }

    private static int marks(ServerPlayer p, String mark) {
        return (int) StatusData.stacks(p, new Status(mark));
    }

    private static void near(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-6, what + ": expected " + expected + ", got " + actual);
    }

    /** Listening gathers up to the dearest voice spell (100); naming a cheaper one casts it at once and gives the rest back. */
    static void castsAtOnceAndRefunds(GameTestHelper h) {
        ServerPlayer p = wandCaster(h);
        h.assertTrue(press(h, p), "listening did not start");
        h.assertTrue(VoiceCasting.listening(p) && Charging.of(p) == null, "a press with 言出法随 chosen listens instead of charging");
        hold(h, p, 40);
        near(h, PlayerMagic.of(p).mana, 900, "listening gathers up to the dearest voice spell");
        VoiceCasting.heard(p, 1, "小");
        h.assertTrue(marks(p, "#small") == 1, "naming 小 casts it at once");
        near(h, PlayerMagic.of(p).mana, 980, "the 80 gathered beyond 小's 20 goes back to the pool");
        h.assertTrue(idle(h, p), "without a chant charm the press stays down, idle, until let go");
        h.succeed();
    }

    /** Named before enough is in, the charge carries on toward that spell and goes off by itself once full. */
    static void chargesOnToTheNamedSpell(GameTestHelper h) {
        ServerPlayer p = wandCaster(h);
        h.assertTrue(press(h, p), "listening did not start");
        hold(h, p, 5);
        double gathered = 1000 - PlayerMagic.of(p).mana;
        h.assertTrue(gathered > 0 && gathered < 100, "five ticks should gather part of 100, got " + gathered);
        VoiceCasting.heard(p, 0, "小");
        h.assertTrue(VoiceCasting.listening(p), "a name that is not that page's is ignored");
        VoiceCasting.heard(p, 0, "蓄");
        var c = Charging.of(p);
        h.assertTrue(c != null && marks(p, "#charged") == 0, "蓄 is not full yet, so it charges on");
        near(h, c.injected, gathered, "what was gathered carries over");
        hold(h, p, 40);
        h.assertTrue(marks(p, "#charged") == 1, "once full it goes off without letting go");
        near(h, PlayerMagic.of(p).mana, 900, "蓄 cost its 100 in all");
        h.assertTrue(idle(h, p), "the press stays down, idle, until let go");
        h.succeed();
    }

    /** Letting go before naming anything casts nothing and sets what was gathered free, as a broken charge does. */
    static void letGoFreesWhatWasGathered(GameTestHelper h) {
        ServerPlayer p = wandCaster(h);
        h.assertTrue(press(h, p), "listening did not start");
        hold(h, p, 10);
        double gathered = 1000 - PlayerMagic.of(p).mana;
        p.releaseUsingItem();
        h.assertTrue(!VoiceCasting.listening(p) && marks(p, "#charged") + marks(p, "#small") == 0, "nothing is cast");
        double free = h.getLevel().getEntitiesOfClass(FreeManaEntity.class, new AABB(p.position(), p.position()).inflate(8))
                .stream().mapToDouble(FreeManaEntity::amount).sum();
        near(h, free, gathered, "free mana set loose");
        h.succeed();
    }

    /** With no page written for 言出法随, there is nothing to name and the press is refused; without the charm, the wand charges as usual. */
    static void needsVoicePagesAndTheCharm(GameTestHelper h) {
        ServerPlayer p = wandCaster(h);
        ItemStack wand = p.getMainHandItem();
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(
                SpellPages.inscribed(PageTier.CRYSTAL, BIG, null), new ItemStack(LogosMod.VOICE_CHARM.get()))));
        h.assertFalse(press(h, p), "a press listened with no voice page to name");
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(voicePage(BIG, "蓄"))));
        h.assertFalse(VoiceCasting.armed(wand), "without its charm the wand is not in 言出法随");
        h.assertTrue(press(h, p) && Charging.of(p) != null, "it charges the selected page as usual");
        h.succeed();
    }

    /** A staff listens without gathering, then casts the named spell from what it holds, or fails if it holds too little. */
    static void staffCastsTheNamedSpell(GameTestHelper h) {
        ServerPlayer p = caster(h, new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get()));
        ItemStack staff = p.getMainHandItem();
        StaffItem.store(staff, 150);
        h.assertTrue(press(h, p) && VoiceCasting.listening(p), "a staff with 言出法随 chosen listens");
        near(h, StaffItem.stored(staff), 150, "listening casts nothing");
        VoiceCasting.heard(p, 0, "蓄");
        h.assertTrue(marks(p, "#charged") == 1, "the named spell is cast at once");
        near(h, StaffItem.stored(staff), 50, "paid from the staff");
        near(h, PlayerMagic.of(p).mana, 1000, "not from the pool");
        h.assertTrue(idle(h, p), "the press stays down, idle, until let go");
        p.releaseUsingItem();
        h.assertTrue(press(h, p), "listening again");
        VoiceCasting.heard(p, 0, "蓄");
        h.assertTrue(marks(p, "#charged") == 1 && idle(h, p), "50 cannot pay 100: the cast fails and the press goes idle");
        near(h, StaffItem.stored(staff), 50, "a failed cast takes nothing");
        h.succeed();
    }

    /** With a chant charm the named spell keeps being cast while held; a new press listens again. */
    static void chantRepeatsTheNamedSpell(GameTestHelper h) {
        ServerPlayer p = wandCaster(h, new ItemStack(LogosMod.CHANT_CHARM.get()));
        h.assertTrue(press(h, p), "listening did not start");
        hold(h, p, 40);
        VoiceCasting.heard(p, 1, "小");
        h.assertTrue(marks(p, "#small") == 1, "the first 小 goes off at once");
        h.assertTrue(p.isUsingItem() && Charging.of(p) != null, "with a chant charm the next 小 starts charging");
        hold(h, p, Charging.ticks(20, 40, 40));
        h.assertTrue(marks(p, "#small") == 2, "and goes off once full, without naming it again");
        near(h, PlayerMagic.of(p).mana, 960, "two casts of 20 in all");
        p.releaseUsingItem();
        h.assertTrue(press(h, p) && VoiceCasting.listening(p), "pressing again listens for a name again");
        h.succeed();
    }

    /** A page written with 言出法随 keeps the name it is called by, shimmers, and copies keep it. */
    static void writtenPageKeepsItsName(GameTestHelper h) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        BlockPos rel = new BlockPos(1, 2, 1);
        h.setBlock(rel, LogosMod.INSCRIPTION_TABLE.get());
        BlockPos pos = h.absolutePos(rel);
        p.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5, 0, 0);
        var table = (InscriptionTableBlockEntity) h.getLevel().getBlockEntity(pos);
        table.setPage(new ItemStack(LogosMod.SPELL_PAGE.get()));
        h.assertTrue(InscriptionTableBlock.inscribe(p, pos, SMALL, new CompoundTag(), "小") != null, "inscribing should succeed");
        ItemStack page = table.page();
        h.assertTrue("小".equals(page.get(LogosMod.VOICE.get())), "the page should keep the name it is called by");
        h.assertTrue(page.getItem().isFoil(page), "a voice page shimmers");
        h.assertTrue(InscriptionTableBlock.inscribe(p, pos, SMALL, new CompoundTag()) != null && !table.page().has(LogosMod.VOICE.get()),
                "written again without it, the page loses 言出法随");
        h.succeed();
    }
}

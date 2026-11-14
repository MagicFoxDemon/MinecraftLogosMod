package dev.logos.gametest;

import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.block.SpellEmitterBlock;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.graph.SpecialNodes;
import dev.logos.item.Books;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Unlocks;
import dev.logos.net.EmitterEditPayload;
import dev.logos.net.InscribePayload;
import dev.logos.runtime.RuneKnowledge;
import dev.logos.sage.SageRunes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * 符文解锁: a player writes only what they have learned, besides what the page said already; creative play writes
 * anything. Pages are written through the real handlers, as a client's packets would be.
 */
final class RuneUnlockGameTests {
    private static final BlockPos TABLE = new BlockPos(1, 2, 1), EMITTER = new BlockPos(5, 2, 1);
    private static final String FREEZE = "(spell :name \"冰\" :mana 5 (projectile (on-hit (freeze target 1))))";

    private RuneUnlockGameTests() {}

    /** A survival player standing by the table, its interface open. */
    private static ServerPlayer survivor(GameTestHelper h) {
        ServerPlayer p = EditPayloadGameTests.playerAt(h, 3.5, 1.5);
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    private static String name(ItemStack page) {
        return SpellPages.isInscribed(page) ? SpellPages.spell(page).name() : "";
    }

    private static void inscribe(GameTestHelper h, ServerPlayer p, String source) {
        BlockPos pos = h.absolutePos(TABLE);
        EditAccessGameTests.rightClick(p, pos);
        InscribePayload.handle(new InscribePayload(pos, source, EditPayloadGameTests.drawing("新"), ""), EditPayloadGameTests.from(p));
    }

    /**
     * A fresh survival player cannot write freeze; once learned they can, but not fire; a page they no longer know
     * freeze for keeps its one freeze through a rewrite and takes no second one. Creative play writes anything.
     */
    static void inscribeHeldToLearning(GameTestHelper h) {
        InscriptionTableBlockEntity table = EditPayloadGameTests.table(h, TABLE, new ItemStack(LogosMod.SPELL_PAGE.get()));
        ServerPlayer p = survivor(h);
        h.assertTrue(!RuneKnowledge.knows(p, Unlocks.rune("freeze")) && RuneKnowledge.knows(p, Unlocks.rune("damage")),
                "a fresh player should know damage and not freeze");
        inscribe(h, p, FREEZE);
        h.assertTrue(!SpellPages.isInscribed(table.page()), "freeze was written before it was learned");

        h.assertTrue(RuneKnowledge.learn(p, Unlocks.rune("freeze")), "learning freeze changed nothing");
        h.assertTrue(!RuneKnowledge.learn(p, Unlocks.rune("freeze")), "freeze was learned twice");
        inscribe(h, p, FREEZE);
        h.assertTrue(name(table.page()).equals("冰"), "a learned freeze was refused: " + name(table.page()));
        inscribe(h, p, "(spell :name \"火\" :mana 5 (projectile (on-hit (damage target 1 fire))))");
        h.assertTrue(name(table.page()).equals("冰"), "fire was written before it was learned");

        RuneKnowledge.forget(p, Unlocks.rune("freeze"));
        inscribe(h, p, "(spell :name \"冰二\" :mana 8 (projectile :speed 2 (on-hit (freeze target 2) (damage target 1))))");
        h.assertTrue(name(table.page()).equals("冰二"), "a rewrite keeping the page's own freeze was refused: " + name(table.page()));
        inscribe(h, p, "(spell :name \"冰三\" :mana 8 (projectile (on-hit (freeze target 2) (freeze caster 1))))");
        h.assertTrue(name(table.page()).equals("冰二"), "a second freeze was added to a page whose writer has not learned it");

        ServerPlayer creative = EditPayloadGameTests.playerAt(h, 3.5, 1.5);
        creative.setGameMode(GameType.CREATIVE);
        inscribe(h, creative, "(spell :name \"生\" :mana 5 (projectile (on-hit (damage target 1 life) (freeze target 1))))");
        h.assertTrue(name(table.page()).equals("生"), "a creative player was held to what they have learned: " + name(table.page()));
        h.succeed();
    }

    /** The spell emitter's page is held to the same: freeze is refused until learned. */
    static void emitterHeldToLearning(GameTestHelper h) {
        h.setBlock(EMITTER, LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST));
        SpellEmitterBlockEntity emitter = (SpellEmitterBlockEntity) h.getBlockEntity(EMITTER, SpellEmitterBlockEntity.class);
        emitter.setPage(SpellPages.inscribed(PageTier.CRYSTAL, "(spell :name \"旧\" :mana 5 (projectile (on-hit (damage target 1))))", null), null);
        ServerPlayer p = survivor(h);
        BlockPos pos = h.absolutePos(EMITTER);
        Runnable send = () -> {
            EditAccessGameTests.rightClick(p, pos);
            EmitterEditPayload.handle(new EmitterEditPayload(pos, FREEZE, EditPayloadGameTests.drawing("新"), SpecialNodes.EmitterMode.PULSE, 1, false),
                    EditPayloadGameTests.from(p));
        };
        send.run();
        h.assertTrue(name(emitter.page()).equals("旧"), "the emitter took freeze before it was learned");
        RuneKnowledge.learn(p, Unlocks.rune("freeze"));
        send.run();
        h.assertTrue(name(emitter.page()).equals("冰"), "the emitter refused a learned freeze: " + name(emitter.page()));
        h.succeed();
    }

    /** Copying a spell out of a book onto a page is writing it too. */
    static void bookHeldToLearning(GameTestHelper h) {
        ServerPlayer p = survivor(h);
        p.setItemInHand(InteractionHand.OFF_HAND, Books.create("冰", FREEZE));
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LogosMod.SPELL_PAGE.get()));
        InteractionResult refused = p.getMainHandItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(!SpellPages.isInscribed(p.getMainHandItem()) && refused == InteractionResult.FAIL, "the book's freeze was copied before it was learned");
        RuneKnowledge.learn(p, Unlocks.rune("freeze"));
        p.getMainHandItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        h.assertTrue(name(p.getMainHandItem()).equals("冰"), "the book's learned freeze was not copied: " + name(p.getMainHandItem()));
        h.succeed();
    }

    /**
     * What a player knows: the start, then what they learn, by command too; the sage is offered the same. Learning
     * is saved, and life is learned only when given.
     */
    static void knowledgeAndCommands(GameTestHelper h) {
        ServerPlayer p = survivor(h);
        Set<String> sage = SageRunes.available(p);
        h.assertTrue(sage.containsAll(Unlocks.STARTING), "the sage was not offered the starting words: " + sage);
        h.assertTrue(!sage.contains(Unlocks.rune("freeze")) && !sage.contains("element:life"), "the sage was offered words not learned");
        var commands = h.getLevel().getServer().getCommands();
        var source = h.getLevel().getServer().createCommandSourceStack().withEntity(p).withSuppressedOutput();
        commands.performPrefixedCommand(source, "logos unlock @s rune:freeze");
        h.assertTrue(RuneKnowledge.learned(p, Unlocks.rune("freeze")) && SageRunes.available(p).contains(Unlocks.rune("freeze")),
                "/logos unlock did not teach freeze");
        commands.performPrefixedCommand(source, "logos unlock @s element:fire");
        var saved = RuneKnowledge.CODEC.codec().encodeStart(NbtOps.INSTANCE, RuneKnowledge.of(p)).getOrThrow();
        RuneKnowledge loaded = RuneKnowledge.CODEC.codec().parse(NbtOps.INSTANCE, saved).getOrThrow();
        h.assertTrue(RuneKnowledge.CODEC.codec().encodeStart(NbtOps.INSTANCE, loaded).getOrThrow().equals(saved), "learning did not survive saving: " + saved);
        h.assertTrue(saved.toString().contains("rune:freeze") && saved.toString().contains("element:fire"), "what was saved: " + saved);
        commands.performPrefixedCommand(source, "logos lock @s all");
        h.assertTrue(RuneKnowledge.unlearned(p).size() == Unlocks.all().size() - Unlocks.STARTING.size(), "/logos lock all left something learned");
        h.assertTrue(RuneKnowledge.learned(p, Unlocks.rune("damage")), "a starting word was forgotten");
        commands.performPrefixedCommand(source, "logos unlock @s all");
        h.assertTrue(RuneKnowledge.unlearned(p).isEmpty() && RuneKnowledge.knows(p, "element:life"), "/logos unlock all left something out");
        h.succeed();
    }
}

package dev.logos.gametest;

import java.lang.reflect.Proxy;
import java.util.function.Predicate;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.alchemy.ProcessCard;
import dev.logos.block.AlchemyTableBlockEntity;
import dev.logos.block.AlchemyTableUI;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.block.SpellEmitterBlock;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.graph.GraphBuilder;
import dev.logos.graph.GraphCompiler;
import dev.logos.graph.SpecialNodes;
import dev.logos.graph.SpellGraph;
import dev.logos.item.PageTier;
import dev.logos.item.PotionPowderItem;
import dev.logos.item.SpellPages;
import dev.logos.lang.Compiler;
import dev.logos.lang.Examples;
import dev.logos.lang.Reader;
import dev.logos.lang.Spell;
import dev.logos.net.AlchemyCardPayload;
import dev.logos.net.EditLimits;
import dev.logos.net.EmitterEditPayload;
import dev.logos.net.InscribePayload;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** What the server holds client edit packets to, sent through their real handlers: checks before compiling, drawing limits, wiring and pace. */
final class EditPayloadGameTests {
    private static final BlockPos TABLE = new BlockPos(1, 2, 1);

    private EditPayloadGameTests() {}

    /** All a handler asks of its context is who sent the packet. */
    static IPayloadContext from(ServerPlayer player) {
        return (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(), new Class<?>[] {IPayloadContext.class},
                (self, method, args) -> method.getName().equals("player") ? player : null);
    }

    @SuppressWarnings("removal")
    static ServerPlayer playerAt(GameTestHelper h, double x, double z) {
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        Vec3 pos = h.absoluteVec(new Vec3(x, 2, z));
        p.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic.of(p).trace = false;
        return p;
    }

    static InscriptionTableBlockEntity table(GameTestHelper h, BlockPos rel, ItemStack page) {
        h.setBlock(rel, LogosMod.INSCRIPTION_TABLE.get());
        InscriptionTableBlockEntity table = (InscriptionTableBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(rel));
        if (!page.isEmpty()) table.setPage(page);
        return table;
    }

    static String spell(String name) {
        return "(spell :name \"" + name + "\" :mana 5 (heal caster 1))";
    }

    static CompoundTag drawing(String marker) {
        CompoundTag graph = new CompoundTag();
        graph.putString("marker", marker);
        return graph;
    }

    /** A drawing of {@code count} empty nodes, where a drawing keeps its nodes. */
    private static CompoundTag nodes(int count) {
        ListTag nodes = new ListTag();
        for (int i = 0; i < count; i++) nodes.add(new CompoundTag());
        CompoundTag additional = new CompoundTag();
        additional.put("nodes", nodes);
        CompoundTag graph = new CompoundTag();
        graph.put("_additional", additional);
        return graph;
    }

    private static CompoundTag bytes(int count) {
        CompoundTag graph = new CompoundTag();
        graph.putByteArray("junk", new byte[count]);
        return graph;
    }

    private static CompoundTag serialized(GameTestHelper h, String source) {
        SpellGraph graph = GraphBuilder.build(Compiler.compile(source));
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        graph.graphModel.serialize(out);
        return out.buildResult();
    }

    /**
     * Compiling is the last thing an inscribe does: away from the table, or at a table with no page,
     * the source is not even looked at. The compiler is watched through a check only compiling makes.
     */
    static void inscribeChecksBeforeCompiling(GameTestHelper h) {
        InscriptionTableBlockEntity table = table(h, TABLE, new ItemStack(LogosMod.SPELL_PAGE.get()));
        InscriptionTableBlockEntity emptied = table(h, TABLE.east(4), new ItemStack(LogosMod.SPELL_PAGE.get()));
        BlockPos pos = h.absolutePos(TABLE), bare = h.absolutePos(TABLE.east(4));
        String source = "(spell :name \"先查后编\" :mana 5 (potion caster speed 1))";
        int[] compiled = {0};
        Predicate<String> real = Compiler.effectExists;
        Compiler.effectExists = id -> {
            compiled[0]++;
            return real.test(id);
        };
        try {
            ServerPlayer far = playerAt(h, 1.5, 10.5);
            EditAccessGameTests.rightClick(far, pos);
            InscribePayload.handle(new InscribePayload(pos, source, drawing("远"), ""), from(far));
            h.assertTrue(compiled[0] == 0, "a packet from away from the table was compiled");
            h.assertTrue(!SpellPages.isInscribed(table.page()), "a packet from away from the table wrote the page");

            ServerPlayer near = playerAt(h, 3.5, 1.5);
            EditAccessGameTests.rightClick(near, bare);
            emptied.setPage(ItemStack.EMPTY);
            InscribePayload.handle(new InscribePayload(bare, source, drawing("空"), ""), from(near));
            h.assertTrue(compiled[0] == 0, "a packet for a table with no page was compiled");

            EditAccessGameTests.rightClick(near, pos);
            InscribePayload.handle(new InscribePayload(pos, source, drawing("近"), ""), from(near));
            h.assertTrue(compiled[0] > 0, "the watch on the compiler saw nothing, so the checks above prove nothing");
            h.assertTrue(SpellPages.isInscribed(table.page()) && "先查后编".equals(SpellPages.spell(table.page()).name()), "a proper inscribe did not write the page");
            h.assertTrue(table.page().get(LogosMod.SPELL_GRAPH.get()).getStringOr("marker", "").equals("近"), "a proper inscribe did not keep the drawing");
        } finally {
            Compiler.effectExists = real;
        }
        h.succeed();
    }

    /**
     * A drawing too large or with too many nodes is refused, leaving the page or emitter as it was.
     * Real drawings are not: the largest example, a much larger spell, and what the limit lets through
     * all still get through the packet codec that a larger one would disconnect the sender on.
     */
    static void oversizedGraphRefused(GameTestHelper h) {
        ItemStack written = SpellPages.inscribed(spell("旧"), drawing("旧"));
        InscriptionTableBlockEntity table = table(h, TABLE, written);
        BlockPos pos = h.absolutePos(TABLE);
        ServerPlayer p = playerAt(h, 3.5, 1.5);
        EditAccessGameTests.rightClick(p, pos);

        for (CompoundTag tooLarge : new CompoundTag[] {bytes(EditLimits.MAX_GRAPH_BYTES + 1), nodes(EditLimits.MAX_GRAPH_NODES + 1)}) {
            InscribePayload.handle(new InscribePayload(pos, spell("新"), tooLarge, ""), from(p));
            h.assertTrue("旧".equals(SpellPages.spell(table.page()).name()), "an oversized drawing rewrote the spell");
            h.assertTrue(table.page().get(LogosMod.SPELL_GRAPH.get()).getStringOr("marker", "").equals("旧"), "an oversized drawing replaced the old one");
        }
        InscribePayload.handle(new InscribePayload(pos, spell("新"), nodes(EditLimits.MAX_GRAPH_NODES), ""), from(p));
        h.assertTrue("新".equals(SpellPages.spell(table.page()).name()), "a drawing right at the limit was refused");

        BlockPos at = new BlockPos(6, 2, 1);
        h.setBlock(at, LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST));
        SpellEmitterBlockEntity emitter = (SpellEmitterBlockEntity) h.getBlockEntity(at, SpellEmitterBlockEntity.class);
        emitter.setPage(SpellPages.inscribed(PageTier.CRYSTAL, spell("旧"), drawing("旧")), null);
        ServerPlayer q = playerAt(h, 4.5, 1.5);
        EditAccessGameTests.rightClick(q, h.absolutePos(at));
        EmitterEditPayload.handle(new EmitterEditPayload(h.absolutePos(at), spell("新"), bytes(EditLimits.MAX_GRAPH_BYTES + 1), SpecialNodes.EmitterMode.PULSE, 1, false), from(q));
        h.assertTrue("旧".equals(SpellPages.spell(emitter.page()).name()), "an oversized drawing rewrote the emitter's spell");
        EmitterEditPayload.handle(new EmitterEditPayload(h.absolutePos(at), spell("新"), drawing("新"), SpecialNodes.EmitterMode.PULSE, 1, false), from(q));
        h.assertTrue("新".equals(SpellPages.spell(emitter.page()).name()), "a proper emitter edit was refused");

        CompoundTag largestExample = serialized(h, Examples.ALL.get("prismatic-barrage"));
        h.assertTrue(EditLimits.fits(largestExample), "the largest example spell's drawing was refused");
        CompoundTag huge = serialized(h, "(spell :name \"长\" :mana 5" + " (heal caster 1)".repeat(250) + ")");
        h.assertTrue(EditLimits.fits(huge), "a drawing ten times the largest example's was refused");
        var buffer = Unpooled.buffer();
        try {
            ByteBufCodecs.COMPOUND_TAG.encode(buffer, huge);
            h.assertTrue(huge.equals(ByteBufCodecs.COMPOUND_TAG.decode(buffer)), "a drawing within the limit did not survive the packet codec");
        } finally {
            buffer.release();
        }
        h.succeed();
    }

    /**
     * A wire can only be asked for on a product the card has made, the ones its panel lists, told apart
     * by their data: not a kind it has not made, and not a new kind once the card has as many as it may.
     */
    static void routeTakesOnlyMadeProducts(GameTestHelper h) {
        AlchemyTableBlockEntity table = AlchemyGameTests.placeTable(h);
        table.setMana(10);
        ProcessCard card = table.addCard(Process.SETTLE);
        AlchemyGameTests.put(card.inputs(), PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS));
        AlchemyGameTests.put(card.inputs(), PotionContents.createItemStack(Items.POTION, Potions.POISON));
        ItemResource swift = ItemResource.of(PotionPowderItem.of(LogosMod.POTION_POWDER.get(), new PotionContents(Potions.SWIFTNESS), 1));
        ItemResource poison = ItemResource.of(PotionPowderItem.of(LogosMod.POTION_POWDER.get(), new PotionContents(Potions.POISON), 1));
        ItemResource diamond = ItemResource.of(Items.DIAMOND);
        BlockPos pos = h.absolutePos(AlchemyGameTests.MAIN);
        ServerPlayer p = playerAt(h, 1.5, 2.5);
        EditAccessGameTests.openMenu(p, pos);

        AlchemyCardPayload.handle(AlchemyCardPayload.route(pos, 0, diamond, ProcessCard.PRODUCTS), from(p));
        h.assertTrue(card.route(diamond) == ProcessCard.KEEP, "a kind this card does not make was wired");
        AlchemyCardPayload.handle(AlchemyCardPayload.route(pos, 0, swift, ProcessCard.PRODUCTS), from(p));
        h.assertTrue(card.route(swift) == ProcessCard.KEEP, "a kind this card has not made yet was wired");

        // Made here and now rather than by letting the table run: its lever animation cannot be sent to a mock player.
        card.craft(table, true);
        card.craft(table, true);
        h.assertTrue(card.made().contains(swift) && card.made().contains(poison), "the card made " + card.made());
        // Wired as a client does it: the product as the table's sync shows it, then back through the packet.
        TagValueOutput synced = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        card.save(synced);
        ProcessCard seen = ProcessCard.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), synced.buildResult()));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            AlchemyCardPayload.CODEC.encode(buffer, AlchemyCardPayload.route(pos, 0, seen.made().get(card.made().indexOf(swift)), ProcessCard.PRODUCTS));
            AlchemyCardPayload.handle(AlchemyCardPayload.CODEC.decode(buffer), from(p));
        } finally {
            buffer.release();
        }
        h.assertTrue(card.route(swift) == ProcessCard.PRODUCTS, "a product the card made, data and all, could not be wired");
        h.assertTrue(card.route(poison) == ProcessCard.KEEP, "wiring one powder wired the other");
        AlchemyCardPayload.handle(AlchemyCardPayload.route(pos, 0, diamond, ProcessCard.PRODUCTS), from(p));
        h.assertTrue(card.route(diamond) == ProcessCard.KEEP, "a kind this card does not make was wired");

        // Filled up (the way an older save may be): new kinds are refused, ones already wired can still be changed.
        for (int i = 1; i < ProcessCard.MAX_ROUTES; i++) {
            ItemStack filler = new ItemStack(Items.PAPER);
            filler.set(DataComponents.CUSTOM_NAME, Component.literal("填" + i));
            card.setRoute(ItemResource.of(filler), ProcessCard.PRODUCTS);
        }
        h.assertTrue(!card.wire(poison, ProcessCard.PRODUCTS) && card.route(poison) == ProcessCard.KEEP, "a kind past the limit was wired");
        h.assertTrue(card.wire(swift, ProcessCard.KEEP) && card.route(swift) == ProcessCard.KEEP, "a wire at the limit could not be changed");
        h.succeed();
    }

    /**
     * A few saves in a row all count, past that they are dropped until the allowance comes back; card
     * edits, one per click, have a much larger allowance. Other players are not affected.
     */
    static void editsAreThrottled(GameTestHelper h) {
        InscriptionTableBlockEntity table = table(h, TABLE, new ItemStack(LogosMod.SPELL_PAGE.get()));
        BlockPos pos = h.absolutePos(TABLE);
        ServerPlayer p = playerAt(h, 3.5, 1.5);
        EditAccessGameTests.rightClick(p, pos);
        for (int i = 0; i < 5; i++) {
            InscribePayload.handle(new InscribePayload(pos, spell("第" + i), drawing("第" + i), ""), from(p));
            h.assertTrue(("第" + i).equals(SpellPages.spell(table.page()).name()), "save " + i + " of a short burst was dropped");
        }
        InscribePayload.handle(new InscribePayload(pos, spell("多"), drawing("多"), ""), from(p));
        h.assertTrue("第4".equals(SpellPages.spell(table.page()).name()), "a save past the burst was not dropped");
        ServerPlayer other = playerAt(h, 3.5, 2.5);
        EditAccessGameTests.rightClick(other, pos);
        InscribePayload.handle(new InscribePayload(pos, spell("他"), drawing("他"), ""), from(other));
        h.assertTrue("他".equals(SpellPages.spell(table.page()).name()), "one player's pace held another back");

        AlchemyTableBlockEntity alchemy = AlchemyGameTests.placeTable(h);
        ProcessCard card = alchemy.addCard(Process.FERMENT);
        BlockPos at = h.absolutePos(AlchemyGameTests.MAIN);
        ServerPlayer q = playerAt(h, 1.5, 2.5);
        EditAccessGameTests.openMenu(q, at);
        for (int i = 0; i < 30; i++) AlchemyCardPayload.handle(AlchemyCardPayload.move(at, 0, 10 + i, 10), from(q));
        h.assertTrue(card.x() == AlchemyTableUI.clampX(39), "a burst of 30 card edits was cut short at x=" + card.x());
        AlchemyCardPayload.handle(AlchemyCardPayload.move(at, 0, 0, 10), from(q));
        h.assertTrue(card.x() == AlchemyTableUI.clampX(39), "a card edit past the burst was not dropped");

        h.runAfterDelay(25, () -> {
            InscribePayload.handle(new InscribePayload(pos, spell("后"), drawing("后"), ""), from(p));
            h.assertTrue("后".equals(SpellPages.spell(table.page()).name()), "the allowance did not come back");
            AlchemyCardPayload.handle(AlchemyCardPayload.move(at, 0, 0, 10), from(q));
            h.assertTrue(card.x() == 0, "the card allowance did not come back");
            h.succeed();
        });
    }

    /** A spell nested as deep as the reader allows runs, and opens as a drawing and back, on the server thread's stack. */
    static void deepSpellRuns(GameTestHelper h) {
        ServerPlayer p = playerAt(h, 1.5, 1.5);
        PlayerMagic magic = PlayerMagic.of(p);
        magic.maxMana = magic.mana = 1000;
        String sums = "(+ 1 ".repeat(Reader.MAX_DEPTH - 2) + "1" + ")".repeat(Reader.MAX_DEPTH - 2);
        Spell spell = Compiler.compile("(spell :name \"深\" :mana 5 (heal caster " + sums + "))");
        h.assertTrue(Casting.cast(p, spell, null, null) != null, "the deep spell was refused");
        GraphCompiler.Result drawn = GraphCompiler.compile(GraphBuilder.build(spell));
        h.assertTrue(drawn.ok(), "the deep spell's drawing does not compile: " + drawn.error());
        h.succeed();
    }
}

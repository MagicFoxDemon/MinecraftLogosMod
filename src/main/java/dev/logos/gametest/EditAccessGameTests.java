package dev.logos.gametest;

import static dev.logos.gametest.EditPayloadGameTests.drawing;
import static dev.logos.gametest.EditPayloadGameTests.from;
import static dev.logos.gametest.EditPayloadGameTests.playerAt;
import static dev.logos.gametest.EditPayloadGameTests.spell;
import static dev.logos.gametest.EditPayloadGameTests.table;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;

import dev.logos.LogosMod;
import dev.logos.alchemy.Process;
import dev.logos.alchemy.ProcessCard;
import dev.logos.block.AlchemyTableBlockEntity;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.block.SpellEmitterBlock;
import dev.logos.block.SpellEmitterBlockEntity;
import dev.logos.graph.SpecialNodes;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.net.AlchemyCardPayload;
import dev.logos.net.EmitterEditPayload;
import dev.logos.net.InscribePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Who an edit packet is taken from: someone in reach, with that block's interface really open, in a place they may change. */
final class EditAccessGameTests {
    private static final BlockPos TABLE = new BlockPos(1, 2, 1), OTHER_TABLE = new BlockPos(1, 2, 5), EMITTER = new BlockPos(6, 2, 1);
    private static final double STAND_X = 3.5, STAND_Z = 4.5;

    private EditAccessGameTests() {}

    /** A right-click as the server gets it from a client: through the game mode, past the event claim mods stop it at. */
    static void rightClick(ServerPlayer p, BlockPos pos) {
        p.gameMode.useItemOn(p, p.level(), p.getMainHandItem(), InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    /**
     * The alchemy table's menu as the server holds it once open, around an empty screen: the real one
     * cannot be built on a test server, where its storage ribbon asks the client for a font.
     */
    static void openMenu(ServerPlayer p, BlockPos pos) {
        BlockUIMenuType.BlockUI screen = holder -> ModularUI.of(UI.of(new UIElement()), p);
        p.containerMenu = new BlockUIMenuType.BlockUIHolder(screen, p, pos, p.level().getBlockState(pos)).createMenu(1, p.getInventory(), p);
    }

    /** A block edited through a packet: {@link #edit} sends one new edit and says whether the block took it. */
    private static final class Editable {
        final String name;
        final BlockPos pos;
        private final BiConsumer<ServerPlayer, BlockPos> opener;
        private final BiConsumer<ServerPlayer, Integer> send;
        private final Supplier<String> seen;
        private int mark;

        Editable(String name, BlockPos pos, BiConsumer<ServerPlayer, BlockPos> opener, BiConsumer<ServerPlayer, Integer> send, Supplier<String> seen) {
            this.name = name;
            this.pos = pos;
            this.opener = opener;
            this.send = send;
            this.seen = seen;
        }

        void open(ServerPlayer p) {
            opener.accept(p, pos);
        }

        boolean edit(ServerPlayer p) {
            String before = seen.get();
            send.accept(p, ++mark);
            return !seen.get().equals(before);
        }
    }

    private static Editable tableAt(GameTestHelper h, BlockPos rel) {
        InscriptionTableBlockEntity table = table(h, rel, SpellPages.inscribed(spell("旧"), drawing("旧")));
        BlockPos pos = h.absolutePos(rel);
        return new Editable("the inscription table", pos, EditAccessGameTests::rightClick,
                (p, mark) -> InscribePayload.handle(new InscribePayload(pos, spell("第" + mark), drawing("新"), ""), from(p)),
                () -> SpellPages.spell(table.page()).name());
    }

    private static Editable emitter(GameTestHelper h) {
        h.setBlock(EMITTER, LogosMod.SPELL_EMITTER.get().defaultBlockState().setValue(SpellEmitterBlock.FACING, Direction.EAST));
        SpellEmitterBlockEntity emitter = (SpellEmitterBlockEntity) h.getBlockEntity(EMITTER, SpellEmitterBlockEntity.class);
        emitter.setPage(SpellPages.inscribed(PageTier.CRYSTAL, spell("旧"), drawing("旧")), null);
        BlockPos pos = h.absolutePos(EMITTER);
        return new Editable("the spell emitter", pos, EditAccessGameTests::rightClick,
                (p, mark) -> EmitterEditPayload.handle(new EmitterEditPayload(pos, spell("第" + mark), drawing("新"), SpecialNodes.EmitterMode.PULSE, 1, false), from(p)),
                () -> SpellPages.spell(emitter.page()).name());
    }

    private static Editable alchemy(GameTestHelper h) {
        AlchemyTableBlockEntity table = AlchemyGameTests.placeTable(h);
        ProcessCard card = table.addCard(Process.FERMENT);
        BlockPos pos = h.absolutePos(AlchemyGameTests.MAIN);
        return new Editable("the alchemy table", pos, EditAccessGameTests::openMenu,
                (p, mark) -> AlchemyCardPayload.handle(AlchemyCardPayload.move(pos, 0, mark, 0), from(p)),
                () -> String.valueOf(card.x()));
    }

    private static List<Editable> all(GameTestHelper h) {
        return List.of(tableAt(h, TABLE), emitter(h), alchemy(h));
    }

    /** Runs {@code body} with {@code pos} outside the world border, where {@code mayInteract} refuses; no tick passes before it is put back. */
    private static void outsideBorder(GameTestHelper h, BlockPos pos, Runnable body) {
        WorldBorder border = h.getLevel().getWorldBorder();
        double x = border.getCenterX(), z = border.getCenterZ(), size = border.getSize();
        border.setCenter(pos.getX() + 1000, pos.getZ() + 1000);
        border.setSize(1);
        try {
            body.run();
        } finally {
            border.setSize(size);
            border.setCenter(x, z);
        }
    }

    /** With the interface never opened, an edit changes nothing; the same edit goes through once it is, and for a real menu stops when it closes. */
    static void closedInterfaceRefused(GameTestHelper h) {
        List<Editable> all = all(h);
        for (Editable e : all) {
            ServerPlayer p = playerAt(h, STAND_X, STAND_Z);
            h.assertTrue(!e.edit(p), e.name + " took an edit with its interface never opened");
            e.open(p);
            h.assertTrue(e.edit(p), e.name + " refused an edit after it was opened");
            if (e == all.getLast()) {
                p.closeContainer();
                h.assertTrue(!e.edit(p), e.name + " took an edit after its menu closed");
                e.open(p);
                h.assertTrue(e.edit(p), e.name + " refused an edit after its menu was reopened");
            }
        }
        h.succeed();
    }

    /** Where {@code mayInteract} is false, or in adventure mode, an edit changes nothing even with the interface open. */
    static void protectedPlaceRefused(GameTestHelper h) {
        for (Editable e : all(h)) {
            ServerPlayer p = playerAt(h, STAND_X, STAND_Z);
            e.open(p);
            outsideBorder(h, e.pos, () -> {
                h.assertTrue(!h.getLevel().mayInteract(p, e.pos), "the border did not make this a place mayInteract refuses");
                h.assertTrue(!e.edit(p), e.name + " took an edit where mayInteract is false");
            });
            h.assertTrue(e.edit(p), e.name + " refused an edit back inside the border");
            p.setGameMode(GameType.ADVENTURE);
            h.assertTrue(!e.edit(p), e.name + " took an edit from adventure mode");
            p.setGameMode(GameType.SURVIVAL);
            h.assertTrue(e.edit(p), e.name + " refused an edit back in survival");
        }
        h.succeed();
    }

    /**
     * Open, then edit, the usual way: several saves on one opening, and drifting while the interface is
     * open costs nothing up to 8 blocks, or as far as a longer reach goes. Past that it is refused, and
     * coming back is enough.
     */
    static void openThenEdit(GameTestHelper h) {
        for (Editable e : all(h)) {
            ServerPlayer p = playerAt(h, STAND_X, STAND_Z);
            p.setGameMode(GameType.SURVIVAL);
            e.open(p);
            h.assertTrue(e.edit(p), e.name + " refused the first edit on an opening");
            h.assertTrue(e.edit(p), e.name + " refused a second edit on the same opening");
            p.snapTo(e.pos.getX() + 0.5 + 7.5, e.pos.getY(), e.pos.getZ() + 0.5, 0, 0);
            h.assertTrue(e.edit(p), e.name + " refused an edit from 7.5 blocks away");
            p.snapTo(e.pos.getX() + 0.5 + 12, e.pos.getY(), e.pos.getZ() + 0.5, 0, 0);
            h.assertTrue(!e.edit(p), e.name + " took an edit from 12 blocks away");
            p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(16);
            h.assertTrue(e.edit(p), e.name + " refused an edit within a long reach");
            p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(Attributes.BLOCK_INTERACTION_RANGE.value().getDefaultValue());
            p.snapTo(e.pos.getX() + 0.5, e.pos.getY(), e.pos.getZ() + 2.5, 0, 0);
            h.assertTrue(e.edit(p), e.name + " refused an edit after coming back");
        }
        h.succeed();
    }

    /** What a claim mod does: stops the right-click on the server, while the client may already have drawn its own screen. */
    public static final class ClaimMod {
        @SubscribeEvent
        public void refuse(PlayerInteractEvent.RightClickBlock event) {
            event.setCanceled(true);
        }
    }

    /** A right-click the server's event stopped opens nothing, so the edit that follows is refused. */
    static void stoppedRightClickRefused(GameTestHelper h) {
        List<Editable> all = all(h);
        ServerPlayer[] players = {playerAt(h, STAND_X, STAND_Z), playerAt(h, STAND_X, STAND_Z), playerAt(h, STAND_X, STAND_Z)};
        ClaimMod claim = new ClaimMod();
        NeoForge.EVENT_BUS.register(claim);
        try {
            for (int i = 0; i < 3; i++) {
                rightClick(players[i], all.get(i).pos);
                h.assertTrue(!all.get(i).edit(players[i]), all.get(i).name + " took an edit after a stopped right-click");
            }
        } finally {
            NeoForge.EVENT_BUS.unregister(claim);
        }
        for (int i = 0; i < 3; i++) {
            all.get(i).open(players[i]);
            h.assertTrue(all.get(i).edit(players[i]), all.get(i).name + " refused an edit once it was opened");
        }
        h.succeed();
    }

    /**
     * The editor belongs to the block it was opened on, for the player who opened it: opening another
     * table leaves only that one editable, a table put in place of the opened one has to be opened
     * itself, a broken one takes nothing, and someone else's opening or an owner's refusal gives no access.
     */
    static void editorFollowsTheBlock(GameTestHelper h) {
        Editable first = tableAt(h, TABLE), second = tableAt(h, OTHER_TABLE);
        ServerPlayer p = playerAt(h, STAND_X, STAND_Z);
        first.open(p);
        h.assertTrue(first.edit(p), "the first table refused an edit");
        second.open(p);
        h.assertTrue(!first.edit(p), "the first table took an edit after the second was opened");
        h.assertTrue(second.edit(p), "the second table refused an edit");
        first.open(p);
        h.assertTrue(first.edit(p), "reopening the first table did not make it editable again");
        h.assertTrue(!second.edit(p), "reopening the first table left the second editable");

        ServerPlayer watcher = playerAt(h, STAND_X, STAND_Z);
        h.assertTrue(!first.edit(watcher), "a table took an edit from someone who did not open it");

        h.setBlock(TABLE, Blocks.AIR);
        first.send.accept(p, 100);
        Editable replaced = tableAt(h, TABLE);
        h.assertTrue(!replaced.edit(p), "a new table took an edit through the old one's opening");
        replaced.open(p);
        h.assertTrue(replaced.edit(p), "the new table refused an edit once opened");

        Editable owned = emitter(h);
        ServerPlayer owner = playerAt(h, STAND_X, STAND_Z), stranger = playerAt(h, STAND_X, STAND_Z);
        BlockState state = h.getLevel().getBlockState(owned.pos);
        state.getBlock().setPlacedBy(h.getLevel(), owned.pos, state, owner, new ItemStack(LogosMod.SPELL_EMITTER.get()));
        owned.open(stranger);
        h.assertTrue(!owned.edit(stranger), "an emitter took an edit from someone other than its owner");
        owned.open(owner);
        h.assertTrue(owned.edit(owner), "an emitter refused its owner's edit");
        h.succeed();
    }
}

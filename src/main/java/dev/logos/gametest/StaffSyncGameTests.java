package dev.logos.gametest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.Deflater;

import com.mojang.authlib.GameProfile;

import dev.logos.LogosMod;
import dev.logos.graph.GraphBuilder;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.lang.Compiler;
import dev.logos.lang.Examples;
import dev.logos.lang.Status;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.StaffCharging;
import dev.logos.runtime.StatusData;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What a staff costs on the wire, and where the mana of a fill goes: its size through the real item codec, what a fill
 * sends to its holder and to players tracking them, and that nothing is lost or doubled however the fill ends.
 */
final class StaffSyncGameTests {
    /** Long enough to measure, short enough that an oak staff (150, at 28 + 28·t a second) is not yet full. */
    private static final int FILL_TICKS = 40;

    private StaffSyncGameTests() {}

    /** A player whose connection keeps what it is asked to send, as a mock player's does, where the test can read it. */
    private record Watched(ServerPlayer player, EmbeddedChannel channel) {}

    /** The item updates read off a connection, encoded by their own packet codecs. */
    private record Updates(int count, long bytes, long zlib) {
        String describe(int ticks) {
            double seconds = ticks / 20.0;
            return count + " updates, " + String.format("%.1f", bytes / 1024.0) + " KB (" + String.format("%.1f", bytes / 1024.0 / seconds) + " KB/s), zlib "
                    + String.format("%.1f", zlib / 1024.0) + " KB (" + String.format("%.1f", zlib / 1024.0 / seconds) + " KB/s)";
        }
    }

    private static Watched watched(GameTestHelper h, double x, double z) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "staff-sync"), false);
        ServerPlayer player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        Vec3 pos = h.absoluteVec(new Vec3(x, 2, z));
        player.snapTo(pos.x, pos.y, pos.z, 0, 0);
        PlayerMagic m = PlayerMagic.of(player);
        m.baseMaxMana = m.mana = 1000;
        m.trace = false;
        return new Watched(player, channel);
    }

    /** Has {@code observer} track {@code target} the way a client does once it holds the chunk: a mock connection sends no chunks by itself. */
    private static void track(GameTestHelper h, ServerPlayer observer, ServerPlayer target) {
        ChunkMap chunks = h.getLevel().getChunkSource().chunkMap;
        chunks.move(observer);
        PlayerChunkSender sender = observer.connection.chunkSender;
        long at = target.chunkPosition().pack();
        for (int i = 0; i < 100 && sender.isPending(at); i++) {
            sender.sendNextChunks(observer);
            sender.onChunkBatchReceivedByClient(64);
        }
        chunks.move(observer);
        h.assertTrue(chunks.getPlayersWatching(target).contains(observer), "the observer should be tracking the holder");
    }

    private static CompoundTag drawing(GameTestHelper h, String source) {
        TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        GraphBuilder.build(Compiler.compile(source)).graphModel.serialize(out);
        return out.buildResult();
    }

    /** An oak staff with a written page per source, with or without the drawings the editor would keep on them. */
    private static ItemStack staff(GameTestHelper h, List<String> sources, boolean drawn) {
        List<ItemStack> pages = new ArrayList<>();
        for (String source : sources) pages.add(SpellPages.inscribed(PageTier.CRYSTAL, source, drawn ? drawing(h, source) : null));
        ItemStack staff = new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get());
        staff.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(pages));
        return staff;
    }

    /** {@code [from, from + 9)} of the examples, smallest first. */
    private static List<String> examples(int from) {
        return Examples.ALL.values().stream().sorted(Comparator.comparingInt(String::length)).skip(from).limit(9).toList();
    }

    private static int zlib(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        byte[] out = new byte[8192];
        int total = 0;
        while (!deflater.finished()) total += deflater.deflate(out);
        deflater.end();
        return total;
    }

    private static byte[] encoded(GameTestHelper h, Consumer<RegistryFriendlyByteBuf> write) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            write.accept(buffer);
            return ByteBufUtil.getBytes(buffer);
        } finally {
            buffer.release();
        }
    }

    private static String size(GameTestHelper h, ItemStack stack) {
        byte[] raw = encoded(h, b -> ItemStack.OPTIONAL_STREAM_CODEC.encode(b, stack));
        return raw.length + " B (zlib " + zlib(raw) + " B)";
    }

    /** Takes every item update sent on {@code channel} since the last look. */
    private static Updates updates(GameTestHelper h, EmbeddedChannel channel) {
        int count = 0;
        long bytes = 0, zlib = 0;
        channel.flushOutbound();
        for (Object sent = channel.readOutbound(); sent != null; sent = channel.readOutbound()) {
            byte[] raw = null;
            if (sent instanceof ClientboundSetEquipmentPacket equipment) raw = encoded(h, b -> ClientboundSetEquipmentPacket.STREAM_CODEC.encode(b, equipment));
            else if (sent instanceof ClientboundContainerSetSlotPacket slot) raw = encoded(h, b -> ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(b, slot));
            if (raw == null) continue;
            count++;
            bytes += raw.length;
            zlib += zlib(raw);
        }
        return new Updates(count, bytes, zlib);
    }

    /** One server tick of a mock player, whose connection does not tick by itself: what fires the player tick and notices a changed hand. */
    private static void tick(ServerPlayer p) {
        p.doTick();
        p.containerMenu.broadcastChanges();
    }

    /**
     * Over real connections: filling a staff of nine medium pages sends its holder and the players tracking
     * them no item update at all until it is let go or fills up, and then one each. The sizes behind that go to the log.
     */
    static void fillSendsNoItemUpdates(GameTestHelper h) {
        int examples = Examples.ALL.size();
        List<String> medium = examples((examples - 9) / 2), largest = examples(examples - 9);
        ItemStack staff = staff(h, medium, true);
        LogosMod.LOGGER.info("[staff sync] {} examples; 9 medium pages, {}..{} chars of source each: {}, without drawings {}; the 3 an oak staff holds: {}", examples,
                medium.getFirst().length(), medium.getLast().length(), size(h, staff), size(h, staff(h, medium, false)), size(h, staff(h, medium.subList(0, 3), true)));
        LogosMod.LOGGER.info("[staff sync] 9 largest pages, {}..{} chars of source each: {}, without drawings {}",
                largest.getFirst().length(), largest.getLast().length(), size(h, staff(h, largest, true)), size(h, staff(h, largest, false)));

        for (int x = 0; x < 9; x++) for (int z = 0; z < 9; z++) h.setBlock(x, 1, z, Blocks.STONE);
        Watched holder = watched(h, 4.5, 4.5), observer = watched(h, 6.5, 4.5);
        ServerPlayer p = holder.player();
        p.setItemInHand(InteractionHand.MAIN_HAND, staff);
        track(h, observer.player(), p);
        tick(p);
        updates(h, holder.channel());
        updates(h, observer.channel());

        StaffCharging.start(p);
        int[] ticks = {0};
        h.onEachTick(() -> {
            tick(p);
            ticks[0]++;
            if (ticks[0] == FILL_TICKS) {
                Updates toHolder = updates(h, holder.channel()), toTracker = updates(h, observer.channel());
                LogosMod.LOGGER.info("[staff sync] {} ticks of filling: holder {}; tracker {}", FILL_TICKS, toHolder.describe(FILL_TICKS), toTracker.describe(FILL_TICKS));
                h.assertTrue(toHolder.count() == 0, "the holder got " + toHolder.count() + " item updates in " + FILL_TICKS + " ticks of filling");
                h.assertTrue(toTracker.count() == 0, "a tracking player got " + toTracker.count() + " item updates in " + FILL_TICKS + " ticks of filling");
                StaffCharging.stop(p);
            } else if (ticks[0] == FILL_TICKS + 1) {
                Updates toHolder = updates(h, holder.channel()), toTracker = updates(h, observer.channel());
                LogosMod.LOGGER.info("[staff sync] letting go: holder {}; tracker {}", toHolder, toTracker);
                h.assertTrue(toHolder.count() == 1 && toTracker.count() == 1, "letting go should send the filled staff once each, not holder "
                        + toHolder.count() + " and tracker " + toTracker.count());
                h.assertTrue(StaffItem.stored(staff) > 100, "letting go should write what went in, the staff holds " + StaffItem.stored(staff));
                StaffCharging.start(p);
            } else if (ticks[0] == FILL_TICKS + 1 + FILL_TICKS) {
                Updates toHolder = updates(h, holder.channel()), toTracker = updates(h, observer.channel());
                near(h, StaffItem.stored(staff), 150, "the rest of the fill");
                h.assertTrue(toHolder.count() == 1 && toTracker.count() == 1, "filling up should send the full staff once each, not holder "
                        + toHolder.count() + " and tracker " + toTracker.count());
                h.succeed();
            }
        });
    }

    /** A player with a full pool of 1000 and a pageless oak staff in the main hand that already holds {@code stored}, filling for a second (42 mana). */
    private static ServerPlayer filling(GameTestHelper h, double stored) {
        ServerPlayer p = watched(h, 4.5, 4.5).player();
        ItemStack staff = new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get());
        StaffItem.store(staff, stored);
        p.setItemInHand(InteractionHand.MAIN_HAND, staff);
        StaffCharging.start(p);
        fill(p, 20);
        near(h, StaffCharging.held(p, staff), stored + 42, "a second of filling");
        near(h, PlayerMagic.of(p).mana, 958, "the pool pays as it goes");
        return p;
    }

    private static void fill(ServerPlayer p, int ticks) {
        for (int i = 0; i < ticks; i++) StaffCharging.tick(p);
    }

    private static void near(GameTestHelper h, double actual, double expected, String what) {
        h.assertTrue(Math.abs(actual - expected) < 1e-6, what + ": expected " + expected + ", got " + actual);
    }

    private static ItemStack dropped(GameTestHelper h, ServerPlayer p) {
        List<ItemEntity> staffs = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(p.position(), p.position()).inflate(8), e -> e.getItem().getItem() instanceof StaffItem);
        h.assertTrue(staffs.size() == 1, "expected one dropped staff, found " + staffs.size());
        return staffs.getFirst().getItem();
    }

    /** Scrolling to another staff with the button down: each keeps what was paid into it, and the other's fill starts from the start of its own ramp. */
    static void switchingStaffs(GameTestHelper h) {
        ServerPlayer p = filling(h, 0);
        ItemStack first = p.getMainHandItem(), second = new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get());
        p.getInventory().setItem(1, second);
        p.getInventory().setSelectedSlot(1);
        fill(p, 1);
        near(h, StaffItem.stored(first), 42, "the first staff keeps what was paid into it");
        near(h, StaffCharging.held(p, second), 0, "none of it moves to the staff now in hand");
        fill(p, 20);
        near(h, StaffCharging.held(p, second), 42, "the second staff fills as from nothing");
        near(h, StaffItem.stored(first), 42, "the first takes no more");
        near(h, PlayerMagic.of(p).mana, 916, "the pool paid for both");
        h.succeed();
    }

    /** What a staff pays for is spent from that staff: not from another in hand before the tick notices, and in full from its own mid-fill. */
    static void spendsOnlyItsOwn(GameTestHelper h) {
        ServerPlayer p = filling(h, 0);
        ItemStack first = p.getMainHandItem(), second = new ItemStack(LogosMod.WOOD_STAFFS.getFirst().get());
        String spell = "(spell :name \"付\" :mana 30 (mark caster #paid))";
        for (ItemStack staff : List.of(first, second))
            staff.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(SpellPages.inscribed(PageTier.CRYSTAL, spell, null))));
        p.getInventory().setItem(1, second);
        p.getInventory().setSelectedSlot(1);
        h.assertFalse(StaffCharging.cast(p, second, InteractionHand.MAIN_HAND), "a staff holding nothing cast on what was paid into another");
        p.getInventory().setSelectedSlot(0);
        h.assertTrue(StaffCharging.cast(p, first, InteractionHand.MAIN_HAND), "a staff should cast on what is on its way into it");
        h.assertTrue(StatusData.stacks(p, new Status("#paid")) == 1, "the cast should have gone off");
        near(h, StaffItem.stored(first), 12, "42 paid in, 30 spent");
        near(h, StaffCharging.held(p, first), 12, "and it is not counted twice");
        StaffCharging.stop(p);
        near(h, StaffItem.stored(first), 12, "nor when the fill ends");
        h.succeed();
    }

    /** Thrown mid-fill, the staff takes what went in with it. */
    static void thrown(GameTestHelper h) {
        ServerPlayer p = filling(h, 10);
        p.drop(true);
        h.assertTrue(PlayerMagic.of(p).fill == null, "throwing the staff should end the fill");
        near(h, StaffItem.stored(dropped(h, p)), 52, "the thrown staff");
        near(h, PlayerMagic.of(p).mana, 958, "the pool gets none of it back as well");
        h.succeed();
    }

    /**
     * Moved as a copy, as a menu moves it, or replaced by one, as a creative client does: the copy has what the
     * item showed, so what had not reached it goes back to the pool, once. A copy in hand can then be filled.
     */
    static void movedAsCopy(GameTestHelper h) {
        ServerPlayer p = filling(h, 10);
        ItemStack husk = p.getMainHandItem(), moved = p.getInventory().removeFromSelected(true);
        h.assertTrue(husk.isEmpty() && moved != husk, "taking the stack should leave a husk behind");
        fill(p, 1);
        h.assertTrue(PlayerMagic.of(p).fill == null, "the staff left the hand");
        near(h, PlayerMagic.of(p).mana, 1000, "what had not reached the staff");
        near(h, StaffItem.stored(moved), 10, "the moved staff");

        p.setItemInHand(InteractionHand.MAIN_HAND, moved);
        StaffCharging.start(p);
        fill(p, 20);
        ItemStack copy = moved.copy();
        p.setItemInHand(InteractionHand.MAIN_HAND, copy);
        fill(p, 1);
        near(h, PlayerMagic.of(p).mana, 1000, "what the replaced stack had not shown");
        near(h, StaffCharging.held(p, copy), 10, "the copy in hand");
        fill(p, 20);
        near(h, StaffCharging.held(p, copy), 52, "the copy fills in its turn");
        near(h, StaffItem.stored(moved), 10, "the stack it replaced does not");
        h.succeed();
    }

    /** Dying mid-fill writes it before the inventory drops, so the staff on the ground holds what went in. */
    static void death(GameTestHelper h) {
        ServerPlayer p = filling(h, 10);
        p.die(h.getLevel().damageSources().generic());
        h.assertTrue(PlayerMagic.of(p).fill == null, "death should end the fill");
        near(h, StaffItem.stored(dropped(h, p)), 52, "the dropped staff");
        near(h, PlayerMagic.of(p).mana, 958, "the pool");
        h.succeed();
    }

    /** Logging out mid-fill writes it before the player is saved. */
    static void logout(GameTestHelper h) {
        ServerPlayer p = filling(h, 10);
        ItemStack staff = p.getMainHandItem();
        h.getLevel().getServer().getPlayerList().remove(p);
        near(h, StaffItem.stored(staff), 52, "the staff as saved");
        near(h, saved(p).mana, 958, "the pool as saved");
        h.succeed();
    }

    private static PlayerMagic saved(ServerPlayer p) {
        var tag = PlayerMagic.CODEC.codec().encodeStart(NbtOps.INSTANCE, PlayerMagic.of(p)).getOrThrow();
        return PlayerMagic.CODEC.codec().parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    /**
     * Saved mid-fill, as an autosave can be just before a crash (or as a player is copied out of the End): the mana
     * not on the item yet is saved as the pool's, so what comes back from disk has all of it, and once. The fill runs on unharmed.
     */
    static void savedMidway(GameTestHelper h) {
        ServerPlayer p = filling(h, 10);
        ItemStack staff = p.getMainHandItem();
        near(h, saved(p).mana, 1000, "the pool as saved holds what is on its way");
        near(h, StaffItem.stored(staff), 10, "and the item does not");
        near(h, PlayerMagic.of(p).mana, 958, "saving leaves the running pool alone");
        StaffCharging.stop(p);
        near(h, saved(p).mana, 958, "with the fill over the item has it");
        near(h, StaffItem.stored(staff), 52, "the staff");
        h.succeed();
    }
}

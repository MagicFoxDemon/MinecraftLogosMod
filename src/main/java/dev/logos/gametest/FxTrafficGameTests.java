package dev.logos.gametest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.net.AreaWeatherPayload;
import dev.logos.net.FxPayload;
import dev.logos.net.FxPayload.Kind;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellInstance;
import dev.logos.runtime.Vfx;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.ChannelAttributes;

/**
 * Spell FX traffic as a player receives it: a mock player whose connection negotiated the channels gets the real packets, which
 * pile up in its embedded channel, and they are measured through the packet's real stream codec.
 */
final class FxTrafficGameTests {
    /** This far above the test grid no other test's FX come within range. */
    private static final double ALTITUDE = 300;
    /** And this far apart, none of these tests' own. */
    private static final double APART = 400;
    private static final int BODIES = 100, WINDOW = 60;
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private FxTrafficGameTests() {}

    /** Test number {@code test}'s own spot, with the chunks about it loaded for spells to fly through. */
    private static Vec3 aloft(GameTestHelper h, int test) {
        Vec3 at = h.absoluteVec(new Vec3(0, 2, 0)).add(test * APART, ALTITUDE, 0);
        BlockPos block = BlockPos.containing(at);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) h.getLevel().setChunkForced((block.getX() >> 4) + x, (block.getZ() >> 4) + z, true);
        return at;
    }

    /** A mock player whose connection negotiated the channels, at {@code at}. */
    private static ServerPlayer listener(GameTestHelper h, Vec3 at) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.snapTo(at.x, at.y, at.z, 0, 0);
        var channels = ChannelAttributes.getOrCreateAdHocChannels(p.connection.getConnection());
        channels.add(FxPayload.Batch.TYPE.id());
        channels.add(AreaWeatherPayload.TYPE.id());
        channels.add(AreaWeatherPayload.Moved.TYPE.id());
        sent(p);
        return p;
    }

    private static ServerPlayer caster(GameTestHelper h, Vec3 at) {
        @SuppressWarnings("removal")
        ServerPlayer p = h.makeMockServerPlayerInLevel();
        p.snapTo(at.x, at.y, at.z, 0, 0);
        PlayerMagic magic = PlayerMagic.of(p);
        magic.baseMaxMana = magic.mana = 100000;
        magic.trace = false;
        return p;
    }

    /** Takes the payloads the server has sent {@code p} since the last look. */
    private static List<CustomPacketPayload> sent(ServerPlayer p) {
        var out = ((EmbeddedChannel) p.connection.getConnection().channel()).outboundMessages();
        List<CustomPacketPayload> found = new ArrayList<>();
        for (Object o : out) if (o instanceof ClientboundCustomPayloadPacket packet) found.add(packet.payload());
        out.clear();
        return found;
    }

    private static List<CustomPacketPayload> batches(List<CustomPacketPayload> payloads) {
        return payloads.stream().filter(FxPayload.Batch.class::isInstance).toList();
    }

    private static List<FxPayload> effects(List<CustomPacketPayload> payloads) {
        List<FxPayload> all = new ArrayList<>();
        for (CustomPacketPayload payload : batches(payloads)) all.addAll(((FxPayload.Batch) payload).effects());
        return all;
    }

    /** What payloads come to as packets: how many, their bytes through the real codec with the packet id, and those bytes after the network's compression. */
    private record Traffic(int packets, long bytes, long compressed) {
        static Traffic of(GameTestHelper h, List<CustomPacketPayload> payloads) {
            long bytes = 0, compressed = 0;
            for (CustomPacketPayload payload : payloads) {
                var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
                ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(buf, new ClientboundCustomPayloadPacket(payload));
                int size = buf.readableBytes() + 1;
                bytes += size;
                compressed += size < 256 ? size : deflated(buf);
            }
            return new Traffic(payloads.size(), bytes, compressed);
        }

        /** At the default network-compression-threshold of 256, a larger packet is deflated whole. */
        private static int deflated(RegistryFriendlyByteBuf buf) {
            byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            Deflater deflater = new Deflater();
            deflater.setInput(raw);
            deflater.finish();
            byte[] out = new byte[raw.length + 64];
            int size = deflater.deflate(out);
            deflater.end();
            return size + 3;
        }

        String perSecond(int ticks) {
            return packets * 20 / ticks + " packets/s, " + bytes * 20 / ticks + " B/s (" + compressed * 20 / ticks + " B/s compressed)";
        }
    }

    /** The bytes these effects took when each was a packet of its own: the packet id, the channel's name "logos:fx" and the whole record. */
    private static long unbatchedBytes(List<FxPayload> effects) {
        long bytes = 0;
        for (FxPayload p : effects) {
            bytes += 1 + 9 + 3 + 7 * 4 + VarInt.getByteSize(p.id()) + VarInt.getByteSize(p.look() + 1) + 4 + VarInt.getByteSize(p.particle() + 1);
        }
        return bytes;
    }

    /**
     * Many real projectiles fly past a player, with others further off. Each is sent one packet a tick, and what those packets
     * hold falls with distance; the log line is the measurement the numbers in the PR come from.
     */
    static void manyProjectiles(GameTestHelper h) {
        Vec3 base = aloft(h, 0);
        ServerPlayer near = listener(h, base.add(6, 0, 0)), middling = listener(h, base.add(48, 0, 0)), far = listener(h, base.add(80, 0, 0));
        ServerPlayer caster = caster(h, base.add(0, 0, -6));
        var spell = Casting.compile("(spell :mana 5 (projectile :speed 0.2 :life 8))");
        for (int i = 0; i < BODIES; i++) {
            caster.setYRot((i % 11 - 5) * 0.7f);
            caster.setXRot((i % 7 - 3) * 0.3f);
            caster.setYHeadRot(caster.getYRot());
            h.assertTrue(Casting.cast(caster, spell, null, base.add(i % 10 - 5, i / 10 - 5, -6 + i * 0.03)) != null, "cast was refused");
        }
        h.runAfterDelay(1, () -> {
            sent(near);
            sent(middling);
            sent(far);
        });
        h.runAfterDelay(1 + WINDOW, () -> {
            List<CustomPacketPayload> n = batches(sent(near)), m = batches(sent(middling)), f = batches(sent(far));
            Traffic nt = Traffic.of(h, n), mt = Traffic.of(h, m), ft = Traffic.of(h, f);
            LogosMod.LOGGER.info("[fx-traffic] {} projectiles over {} ticks: near {}; 48 blocks {}; 80 blocks {}", BODIES, WINDOW, nt.perSecond(WINDOW), mt.perSecond(WINDOW), ft.perSecond(WINDOW));
            int updates = BODIES * WINDOW;
            h.assertTrue(effects(n).size() == updates, "a near player should get every update of every body, got " + effects(n).size() + " of " + updates);
            h.assertTrue(nt.packets() == WINDOW, "one packet a tick, got " + nt.packets() + " in " + WINDOW);
            h.assertTrue(mt.packets() <= WINDOW && ft.packets() <= WINDOW, "at most one packet a tick further off");
            h.assertTrue(nt.bytes() * 10 < unbatchedBytes(effects(n)) * 7, "batching should save over 30%: " + nt.bytes() + " of " + unbatchedBytes(effects(n)));
            h.assertTrue(Math.abs(effects(m).size() - updates / 2) <= BODIES, "at 48 blocks every other update, got " + effects(m).size() + " of " + updates);
            h.assertTrue(Math.abs(effects(f).size() - updates / 4) <= BODIES, "at 80 blocks one in four, got " + effects(f).size() + " of " + updates);
            h.succeed();
        });
    }

    /**
     * In one tick a newer update takes the place of an older one of the same thing, and everything else stays as it happened:
     * an update does not pass another event of its id, nor its end. It all comes as one packet, unchanged by the codec.
     */
    static void orderAndCollapse(GameTestHelper h) {
        Vec3 at = aloft(h, 1), first = at.add(1, 0, 0), newest = at.add(2, 0, 0), elsewhere = at.add(3, 0, 0);
        ServerPlayer near = listener(h, at);
        ServerLevel level = h.getLevel();
        Vfx.trail(level, 900001, first, UP, Element.FIRE, Motion.FORWARD, 0.3, Vfx.Guise.PLAIN);
        Vfx.burst(level, elsewhere, Element.FIRE, 20);
        Vfx.trail(level, 900002, elsewhere, UP, Element.FIRE, Motion.FORWARD, 0.3, Vfx.Guise.PLAIN);
        Vfx.trail(level, 900001, newest, UP, Element.FIRE, Motion.FORWARD, 0.3, Vfx.Guise.PLAIN);
        Vfx.vein(level, 900003, 0, first, UP, Direction.UP, Element.LIFE, 1, 1, 0);
        Vfx.vein(level, 900003, 1, elsewhere, UP, Direction.UP, Element.LIFE, 1, 1, 0);
        Vfx.vein(level, 900003, 0, newest, UP, Direction.UP, Element.LIFE, 1, 1, 0);
        Vfx.field(level, 900004, first, 3, Element.FROST, 1, 80, 20, Vfx.NONE);
        Vfx.fieldPulse(level, 900004, first, 3, Element.FROST);
        Vfx.field(level, 900004, newest, 3, Element.FROST, 2, 80, 20, Vfx.NONE);
        Vfx.trail(level, 900005, first, UP, Element.VOID, Motion.FORWARD, 0.3, Vfx.Guise.PLAIN);
        Vfx.trailEnd(level, 900005, newest, Element.VOID);
        Vfx.beam(level, 900006, first, elsewhere, Element.ARCANE, 0.5, Vfx.NONE, Vfx.NONE);
        Vfx.beam(level, 900006, newest, elsewhere, Element.ARCANE, 0.5, Vfx.NONE, Vfx.NONE);
        Vfx.beamEnd(level, 900006, newest, Element.ARCANE);
        h.runAfterDelay(1, () -> {
            List<CustomPacketPayload> packets = batches(sent(near));
            h.assertTrue(packets.size() == 1, "one tick should be one packet, got " + packets.size());
            List<FxPayload> got = effects(packets);
            List<Kind> kinds = got.stream().map(FxPayload::kindEnum).toList();
            h.assertTrue(kinds.equals(List.of(Kind.TRAIL, Kind.BURST, Kind.TRAIL, Kind.VEIN_HOLD, Kind.VEIN_HOLD, Kind.FIELD_HOLD, Kind.FIELD_PULSE, Kind.FIELD_HOLD,
                    Kind.TRAIL, Kind.TRAIL_END, Kind.BEAM_HOLD, Kind.BEAM_END)), "wrong effects, or out of order: " + kinds);
            float newestX = (float) newest.x;
            h.assertTrue(got.get(0).id() == 900001 && got.get(0).x() == newestX, "the newest update takes the older one's place");
            h.assertTrue(got.get(2).id() == 900002, "another body's update stays");
            h.assertTrue(got.get(3).variant() == 0 && got.get(3).x() == newestX && got.get(4).variant() == 1, "a vein's heads are told of apart");
            h.assertTrue(got.get(5).x() != newestX && got.get(7).x() == newestX, "an update does not pass another event of its id");
            h.assertTrue(got.get(10).x() == newestX, "the newest beam update takes the older one's place");
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
            FxPayload.Batch.CODEC.encode(buf, new FxPayload.Batch(got));
            h.assertTrue(FxPayload.Batch.CODEC.decode(buf).effects().equals(got) && buf.readableBytes() == 0, "the codec should give back what it was given");
            h.succeed();
        });
    }

    /** What arrived, and the tick of the test it was sent in. */
    private record Heard(int tick, FxPayload effect) {}

    private static List<Integer> ticks(List<Heard> heard, Kind kind, int id) {
        return heard.stream().filter(e -> e.effect().kindEnum() == kind && e.effect().id() == id).map(Heard::tick).toList();
    }

    /**
     * Far players get fewer updates of what lasts, at most 4 ticks apart for what clients drop after 10, and near players all of
     * them, counting the whole length of a beam and the inside of a field as near. Every start and every end reaches everyone,
     * in the tick it happens, real projectile included.
     */
    static void farFewerUpdates(GameTestHelper h) {
        Vec3 at = aloft(h, 2);
        ServerPlayer near = listener(h, at.add(5, 0, 0)), middling = listener(h, at.add(48, 0, 0)), far = listener(h, at.add(80, 0, 0));
        ServerPlayer caster = caster(h, at.add(0, -1.62, 0));
        Map<ServerPlayer, List<Heard>> heard = new HashMap<>();
        List<ServerPlayer> all = List.of(near, middling, far);
        for (ServerPlayer p : all) heard.put(p, new ArrayList<>());
        ServerLevel level = h.getLevel();
        int trail = 900011, beam = 900012, vein = 900013, field = 900014, longBeam = 900015, wideField = 900016, last = 24;
        Vec3 beamEnd = at.add(0, 0, 5), longFrom = at.add(70, 0, 0), longTo = at.add(-20, 0, 0), wideCentre = at.add(60, 0, 0);
        h.assertTrue(Casting.cast(caster, Casting.compile("(spell :mana 10 (projectile :speed 0.02 :life 1))"), null, null) != null, "cast was refused");
        int[] tick = {0};
        h.onEachTick(() -> {
            int t = tick[0]++;
            for (ServerPlayer p : all) for (FxPayload e : effects(sent(p))) heard.get(p).add(new Heard(t - 1, e));
            if (t <= last) {
                Vfx.trail(level, trail, at, UP, Element.FIRE, Motion.FORWARD, 0.3, Vfx.Guise.PLAIN);
                Vfx.beam(level, beam, at, beamEnd, Element.FROST, 0.5, Vfx.NONE, Vfx.NONE);
                Vfx.beam(level, longBeam, longFrom, longTo, Element.FROST, 0.5, Vfx.NONE, Vfx.NONE);
                Vfx.vein(level, vein, 0, at, UP, Direction.UP, Element.LIFE, 1, 1, 0);
                if (t % 4 == 0) {
                    Vfx.field(level, field, at, 3, Element.VOID, t, 100, 20, Vfx.NONE);
                    Vfx.field(level, wideField, wideCentre, 70, Element.VOID, t, 100, 20, Vfx.NONE);
                }
            }
            if (t == last) {
                Vfx.trailEnd(level, trail, at, Element.FIRE);
                Vfx.beamEnd(level, beam, at, Element.FROST);
                Vfx.beamEnd(level, longBeam, longFrom, Element.FROST);
                Vfx.veinEnd(level, vein, 0, at, Element.LIFE, false);
                Vfx.fieldEnd(level, field, at, Element.VOID);
                Vfx.fieldEnd(level, wideField, wideCentre, Element.VOID);
            }
            if (t <= last + 1) return;
            int[] every = {1, 2, 4};
            for (int k = 0; k < 3; k++) {
                List<Heard> got = heard.get(all.get(k));
                String who = "player " + k + ": ";
                for (int[] lasting : new int[][] {{Kind.TRAIL.ordinal(), trail, Kind.TRAIL_END.ordinal()}, {Kind.BEAM_HOLD.ordinal(), beam, Kind.BEAM_END.ordinal()}, {Kind.VEIN_HOLD.ordinal(), vein, Kind.VEIN_END.ordinal()}}) {
                    Kind update = Kind.VALUES[lasting[0]];
                    List<Integer> times = ticks(got, update, lasting[1]);
                    h.assertTrue(times.getFirst() == 0, who + update + " should start in its first tick: " + times);
                    h.assertTrue(times.size() == last / every[k] + 1, who + update + " should come every " + every[k] + " ticks: " + times);
                    for (int i = 1; i < times.size(); i++) h.assertTrue(times.get(i) - times.get(i - 1) <= 4, who + update + " silent too long: " + times);
                    h.assertTrue(ticks(got, Kind.VALUES[lasting[2]], lasting[1]).equals(List.of(last)), who + update + " should end in its last tick");
                }
                List<Integer> reminders = ticks(got, Kind.FIELD_HOLD, field);
                h.assertTrue(reminders.getFirst() == 0, who + "a field should start in its first tick");
                h.assertTrue(reminders.size() == (k == 2 ? 4 : 7), who + "a field is reminded every 4 ticks, every 8 far off: " + reminders);
                h.assertTrue(ticks(got, Kind.FIELD_END, field).equals(List.of(last)), who + "a field should end in its last tick");
                List<Heard> real = got.stream().filter(e -> e.effect().id() > 0 && e.effect().id() < 900000).toList();
                h.assertTrue(real.getFirst().effect().kindEnum() == Kind.TRAIL_FROM, who + "a real projectile's start should arrive");
                h.assertTrue(real.getLast().effect().kindEnum() == Kind.TRAIL_END, who + "a real projectile's end should arrive");
                h.assertTrue(real.stream().filter(e -> e.effect().kindEnum() == Kind.TRAIL).count() >= 20 / every[k] - 1, who + "a real projectile's updates should arrive");
            }
            List<Heard> beside = heard.get(near);
            h.assertTrue(ticks(beside, Kind.BEAM_HOLD, longBeam).size() == last + 1, "beside a long beam, every update, wherever it starts");
            h.assertTrue(ticks(beside, Kind.FIELD_HOLD, wideField).size() == 7, "inside a wide field, every reminder, wherever its centre");
            h.succeed();
        });
    }

    /** What is shown between ticks (a packet handler, a command) has no tick end coming to send it: it goes at once. */
    static void betweenTicks(GameTestHelper h) {
        Vec3 at = aloft(h, 3);
        ServerPlayer near = listener(h, at);
        MinecraftServer server = h.getLevel().getServer();
        int[] packets = {-1};
        server.schedule(new TickTask(server.getTickCount(), () -> {
            Vfx.burst(h.getLevel(), at, Element.FIRE, 20);
            packets[0] = batches(sent(near)).size();
        }));
        h.succeedWhen(() -> {
            h.assertTrue(packets[0] >= 0, "the task has not run yet");
            h.assertTrue(packets[0] == 1, "it should be on the connection already, found " + packets[0] + " packets");
        });
    }

    /** {@code phantasm} costs 0.02 a particle; a spell that cannot pay shows nothing. */
    static void phantasmPrice(GameTestHelper h) {
        Vec3 at = aloft(h, 4);
        ServerPlayer near = listener(h, at);
        ServerPlayer caster = caster(h, at);
        SpellInstance one = Casting.cast(caster, Casting.compile("(spell :mana 5 (phantasm %portal))"), null, at);
        SpellInstance full = Casting.cast(caster, Casting.compile("(spell :mana 5 (phantasm %portal :count 64))"), null, at);
        SpellInstance past = Casting.cast(caster, Casting.compile("(spell :mana 5 (phantasm %portal :count 1000))"), null, at);
        SpellInstance dry = Casting.cast(caster, Casting.compile("(spell :mana 1 (phantasm %portal :count 64))"), null, at);
        h.assertTrue(one != null && full != null && past != null && dry != null, "a cast was refused");
        h.assertTrue(Math.abs(one.injected - one.mana - 0.02) < 1e-6, "one particle should cost 0.02, cost " + (one.injected - one.mana));
        h.assertTrue(Math.abs(full.injected - full.mana - 1.28) < 1e-6, "a full burst should cost 1.28, cost " + (full.injected - full.mana));
        h.assertTrue(Math.abs(past.injected - past.mana - 1.28) < 1e-6, "past 64 it stays a full burst, cost " + (past.injected - past.mana));
        h.assertTrue(dry.dead && dry.endReason().contains("魔力耗尽"), "1 mana should not pay for 64 particles: " + dry.endReason());
        h.runAfterDelay(1, () -> {
            List<FxPayload> shown = effects(sent(near)).stream().filter(e -> e.kindEnum() == Kind.PHANTASM).toList();
            h.assertTrue(shown.stream().map(FxPayload::variant).toList().equals(List.of((byte) 1, (byte) 64, (byte) 64)), "only what was paid for should be shown: " + shown.size());
            h.succeed();
        });
    }

    /**
     * A zone following its caster, with three more standing still, far off where no other test's weather is: as it moves the
     * players are told of it alone, not of every zone.
     */
    static void followZone(GameTestHelper h) {
        Vec3 base = h.absoluteVec(new Vec3(0, 2, 0)).add(5000, 0, 0);
        ServerPlayer watcher = listener(h, base.add(0, 0, 40));
        ServerPlayer caster = caster(h, base);
        for (int i = 1; i <= 3; i++) Casting.cast(caster, Casting.compile("(spell :mana 500 (weather clear :radius 2 :life 10))"), null, base.add(0, 0, -10 * i));
        Casting.cast(caster, Casting.compile("(spell :mana 500 (weather clear :radius 2 :life 10 :follow))"), null, base);
        for (int t = 1; t <= WINDOW + 2; t++) {
            int step = t;
            h.runAfterDelay(t, () -> caster.snapTo(base.x + step, base.y, base.z, 0, 0));
        }
        h.runAfterDelay(1, () -> sent(watcher));
        h.runAfterDelay(1 + WINDOW, () -> {
            List<CustomPacketPayload> weather = sent(watcher).stream().filter(p -> p instanceof AreaWeatherPayload || p instanceof AreaWeatherPayload.Moved).toList();
            LogosMod.LOGGER.info("[fx-traffic] a following zone over three more, {} ticks: {}", WINDOW, Traffic.of(h, weather).perSecond(WINDOW));
            List<AreaWeatherPayload.Moved> moved = weather.stream().filter(AreaWeatherPayload.Moved.class::isInstance).map(AreaWeatherPayload.Moved.class::cast).toList();
            h.assertTrue(Math.abs(moved.size() - WINDOW / 2) <= 2, "it should be told of every 2 ticks as it moves, got " + moved.size() + " in " + WINDOW);
            h.assertTrue(moved.stream().map(AreaWeatherPayload.Moved::id).distinct().count() == 1, "only the zone that moves should be told of");
            h.assertTrue(moved.getLast().x() > moved.getFirst().x(), "with where it went");
            // Other tests' zones starting or ending send the whole list as well, a few times at most: moving used to send it every 2 ticks.
            h.assertTrue(weather.size() - moved.size() <= 8, "moving should not send the whole list, got " + (weather.size() - moved.size()) + " in " + WINDOW + " ticks");
            h.succeed();
        });
    }
}

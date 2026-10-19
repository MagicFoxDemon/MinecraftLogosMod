package dev.logos.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.block.CaputMortuumBlock;
import dev.logos.net.ManaFieldPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 魔力场: mana in the air, kept per chunk and worked once a second. Two kinds are counted apart.
 * <ul>
 * <li><b>Ambient</b> mana wells up in every chunk at a rate its biome sets ({@link #ambientRate}) and is
 * shared by the collectors there, each taking at most {@link #COLLECTOR_AMBIENT}, so packing
 * collectors into one chunk does not pay.</li>
 * <li><b>Spread</b> mana is what spreaders pour into their chunk, up to {@link #CAP}. It flows into a
 * neighbouring chunk only while the difference is over a threshold ({@link #threshold}): 70, so one
 * spreader settles into 200, 130, 60 around it, and more for each block of caput mortuum on either
 * side. It flows back where collectors draw a chunk down, and leaks away slowly ({@link #LEAK}) as
 * entropic mana that nothing can collect again.</li>
 * </ul>
 * Spread mana is saved with its chunk and stands still while the chunk is unloaded. Clients are told
 * each chunk's level roughly (0 to {@link #LEVELS}) for the motes drifting in it.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class ManaField {
    /** Most spread mana a chunk holds. */
    public static final double CAP = 200;
    /** Past this many blocks of caput mortuum in a chunk, more insulate it no further. */
    public static final int MAX_INSULATION = 3;
    /** Share of a chunk's spread mana that leaks away each second: 0.5% a minute. */
    public static final double LEAK = 1 - Math.pow(1 - 0.005, 1 / 60.0);
    /** Ambient mana a plain chunk gives each second, shared by its collectors. */
    public static final double AMBIENT = 0.2;
    /** Most ambient mana one collector takes each second. */
    public static final double COLLECTOR_AMBIENT = 0.125;
    /** Share of its chunk's spread mana a collector draws each second, and the most it draws. */
    public static final double DRAW_SHARE = 0.02, DRAW_MAX = 10;
    /** How finely clients are told a chunk's spread mana. */
    public static final int LEVELS = 10;
    /** Biomes where twice the ambient mana wells up (cherry groves, mushroom fields, the deep dark). */
    public static final TagKey<Biome> MANA_RICH = TagKey.create(net.minecraft.core.registries.Registries.BIOME,
            Identifier.fromNamespaceAndPath(LogosMod.MODID, "mana_rich"));
    /** Ticks between workings of the field. */
    public static final int PERIOD = 20;
    /** Of a chunk's flow excess, the part that moves in one working: small enough for four neighbours at once. */
    private static final double FLOW = 0.2;
    private static final double EPS = 0.01;

    /** Chunks holding spread mana, by level: only these are worked. */
    private static final Map<ServerLevel, Set<Long>> ACTIVE = new HashMap<>();
    /** Chunks whose spread mana changed since clients were last told. */
    private static final Map<ServerLevel, Set<Long>> CHANGED = new HashMap<>();
    /** The level each chunk's players were last told. */
    private static final Map<ServerLevel, Map<Long, Integer>> TOLD = new HashMap<>();
    /** Collectors drawing ambient mana, by chunk: those counted this second, and last second's count they share by. */
    private static final Map<ServerLevel, Map<Long, Integer>> DRAWING = new HashMap<>(), DREW = new HashMap<>();

    private ManaField() {}

    /** Whether this tick the field is worked, and the blocks that use it act. */
    public static boolean working(Level level) {
        return level.getGameTime() % PERIOD == 0;
    }

    // ---- spread mana ------------------------------------------------------------------------------

    /** A chunk's spread mana, without giving a chunk that has none an attachment. */
    private static double mana(LevelChunk chunk) {
        Double mana = chunk.getExistingDataOrNull(LogosMod.MANA_FIELD);
        return mana == null ? 0 : mana;
    }

    /**
     * How much more spread mana one of two neighbouring chunks must hold for it to flow into the other:
     * the configured threshold, raised for each block of caput mortuum in either chunk (both ways).
     */
    public static double threshold(LevelChunk a, LevelChunk b) {
        return LogosConfig.manaSpreadThreshold() + LogosConfig.caputMortuumInsulation() * (insulation(a) + insulation(b));
    }

    /** Blocks of caput mortuum in a chunk, counted by their markers, at most {@link #MAX_INSULATION}. */
    public static int insulation(LevelChunk chunk) {
        int n = 0;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof CaputMortuumBlock.Marker && ++n >= MAX_INSULATION) break;
        }
        return n;
    }

    private static @Nullable LevelChunk loaded(ServerLevel level, long chunk) {
        return level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
    }

    /** The spread mana of the chunk {@code pos} is in (0 where it is not loaded). */
    public static double spread(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = loaded(level, ChunkPos.pack(pos));
        return chunk == null ? 0 : mana(chunk);
    }

    /** Flow below this (mana a second) is the trickle that makes up for leaking, not spreading. */
    public static final double SURVEY_FLOW = 0.05;

    /**
     * The chunks within {@code r} of {@code pos}'s, row by row from the north-west, for a spreader's
     * screen: first each one's spread mana in tenths (−1 where it is not loaded), then for each the
     * sides mana is flowing out of it (bits: 1 east, 2 west, 4 south, 8 north). Reads only loaded chunks.
     */
    public static int[] survey(ServerLevel level, BlockPos pos, int r) {
        int side = 2 * r + 1, n = side * side, cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        int[] out = new int[2 * n];
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int i = 0; i < n; i++) {
            int x = cx - r + i % side, z = cz - r + i / side;
            LevelChunk here = loaded(level, ChunkPos.pack(x, z));
            if (here == null) {
                out[i] = -1;
                continue;
            }
            double mine = mana(here);
            out[i] = (int) Math.round(mine * 10);
            for (int d = 0; d < 4; d++) {
                LevelChunk there = loaded(level, ChunkPos.pack(x + steps[d][0], z + steps[d][1]));
                if (there != null && (mine - mana(there) - threshold(here, there)) * FLOW >= SURVEY_FLOW) out[n + i] |= 1 << d;
            }
        }
        return out;
    }

    /** Sets a loaded chunk's spread mana, keeping it among the worked chunks while it holds any. */
    static void set(ServerLevel level, LevelChunk chunk, double mana) {
        long key = chunk.getPos().pack();
        double value = mana < EPS ? 0 : Math.min(CAP, mana);
        chunk.setData(LogosMod.MANA_FIELD, value);
        Set<Long> active = ACTIVE.computeIfAbsent(level, l -> new HashSet<>());
        if (value > 0) active.add(key);
        else active.remove(key);
        CHANGED.computeIfAbsent(level, l -> new HashSet<>()).add(key);
    }

    /** Pours up to {@code amount} into the chunk {@code pos} is in, as far as {@link #CAP}; returns what went in. */
    public static double pour(ServerLevel level, BlockPos pos, double amount) {
        LevelChunk chunk = loaded(level, ChunkPos.pack(pos));
        if (chunk == null || amount <= 0) return 0;
        double had = mana(chunk);
        double in = Math.min(amount, CAP - had);
        if (in <= 0) return 0;
        set(level, chunk, had + in);
        return in;
    }

    /** What a collector at {@code pos} draws of its chunk's spread mana this second: its share, at most {@code room}. */
    public static double drawSpread(ServerLevel level, BlockPos pos, double room) {
        LevelChunk chunk = loaded(level, ChunkPos.pack(pos));
        if (chunk == null || room <= 0) return 0;
        double had = mana(chunk);
        double out = Math.min(room, Math.min(had * DRAW_SHARE, DRAW_MAX));
        if (out <= 0) return 0;
        set(level, chunk, had - out);
        return out;
    }

    // ---- ambient mana -----------------------------------------------------------------------------

    /** Ambient mana a chunk gives each second at {@code pos}: twice in mana-rich biomes, four times in the End. */
    public static double ambientRate(ServerLevel level, BlockPos pos) {
        if (level.dimension() == Level.END) return AMBIENT * 4;
        return level.getBiome(pos).is(MANA_RICH) ? AMBIENT * 2 : AMBIENT;
    }

    /**
     * What a collector at {@code pos} takes of its chunk's ambient mana this second, at most {@code room}:
     * the chunk's rate shared by the collectors that drew there last second, and no more than {@link #COLLECTOR_AMBIENT}.
     */
    public static double drawAmbient(ServerLevel level, BlockPos pos, double room) {
        if (room <= 0) return 0;
        long key = ChunkPos.pack(pos);
        DRAWING.computeIfAbsent(level, l -> new HashMap<>()).merge(key, 1, Integer::sum);
        int sharers = Math.max(1, DREW.getOrDefault(level, Map.of()).getOrDefault(key, 1));
        return Math.min(room, Math.min(COLLECTOR_AMBIENT, ambientRate(level, pos) / sharers));
    }

    // ---- working ----------------------------------------------------------------------------------

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !working(level)) return;
        // The collectors that drew this second are who share the next second's ambient mana.
        Map<Long, Integer> drawing = DRAWING.remove(level);
        if (drawing == null) DREW.remove(level);
        else DREW.put(level, drawing);
        work(level);
    }

    /** One second of the field in {@code level}: flow between chunks, then leaking, then telling clients. */
    public static void work(ServerLevel level) {
        Set<Long> active = ACTIVE.getOrDefault(level, Set.of());
        Map<Long, Double> delta = new HashMap<>();
        for (long key : active) {
            LevelChunk here = loaded(level, key);
            if (here == null) continue;
            double mine = mana(here);
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            for (long next : new long[] {ChunkPos.pack(x + 1, z), ChunkPos.pack(x - 1, z), ChunkPos.pack(x, z + 1), ChunkPos.pack(x, z - 1)}) {
                LevelChunk there = loaded(level, next);
                if (there == null) continue;
                double excess = mine - mana(there) - threshold(here, there);
                if (excess <= 0) continue;
                delta.merge(key, -excess * FLOW, Double::sum);
                delta.merge(next, excess * FLOW, Double::sum);
            }
        }
        Set<Long> touched = new HashSet<>(active);
        touched.addAll(delta.keySet());
        for (long key : touched) {
            LevelChunk chunk = loaded(level, key);
            if (chunk == null) continue;
            double v = mana(chunk) + delta.getOrDefault(key, 0.0);
            set(level, chunk, v * (1 - LEAK));
        }
        Set<Long> changed = CHANGED.remove(level);
        if (changed == null) return;
        for (long key : changed) {
            LevelChunk chunk = loaded(level, key);
            if (chunk != null) tell(level, key, mana(chunk));
        }
    }

    // ---- clients ----------------------------------------------------------------------------------

    /** The rough level clients are told for {@code mana}. */
    public static int levelOf(double mana) {
        return (int) Math.round(mana / CAP * LEVELS);
    }

    /** Tells the players watching a chunk its level, when it changed. */
    private static void tell(ServerLevel level, long key, double mana) {
        int now = levelOf(mana);
        Map<Long, Integer> told = TOLD.computeIfAbsent(level, l -> new HashMap<>());
        Integer before = told.get(key);
        if (before != null && before == now || before == null && now == 0) return;
        if (now == 0) told.remove(key);
        else told.put(key, now);
        ManaFieldPayload payload = new ManaFieldPayload(key, now);
        for (ServerPlayer p : level.getChunkSource().chunkMap.getPlayers(ChunkPos.unpack(key), false)) send(p, payload);
    }

    private static void send(ServerPlayer p, ManaFieldPayload payload) {
        if (p.connection.hasChannel(ManaFieldPayload.TYPE)) PacketDistributor.sendToPlayer(p, payload);
    }

    /** A chunk coming into a player's view brings its level with it. */
    @SubscribeEvent
    static void onWatch(ChunkWatchEvent.Watch event) {
        int now = levelOf(mana(event.getChunk()));
        if (now > 0) send(event.getPlayer(), new ManaFieldPayload(event.getPos().pack(), now));
    }

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && mana(chunk) > 0) {
            ACTIVE.computeIfAbsent(level, l -> new HashSet<>()).add(chunk.getPos().pack());
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long key = event.getChunk().getPos().pack();
        Set<Long> active = ACTIVE.get(level);
        if (active != null) active.remove(key);
        Map<Long, Integer> told = TOLD.get(level);
        if (told != null) told.remove(key);
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (var map : List.of(ACTIVE, CHANGED, TOLD, DRAWING, DREW)) map.remove(level);
    }

    // ---- client copy ------------------------------------------------------------------------------

    private static final Map<Long, Integer> CLIENT = new HashMap<>();
    private static @Nullable Level clientLevel;

    /** A level change on the client forgets what it knew of the last one. */
    private static Map<Long, Integer> clientFor(Level level) {
        if (clientLevel != level) {
            CLIENT.clear();
            clientLevel = level;
        }
        return CLIENT;
    }

    public static void receive(Level level, ManaFieldPayload payload) {
        Map<Long, Integer> known = clientFor(level);
        if (payload.level() <= 0) known.remove(payload.chunk());
        else known.put(payload.chunk(), payload.level());
    }

    /** The level a client knows for the chunk {@code pos} is in (0 to {@link #LEVELS}). */
    public static int clientLevel(Level level, BlockPos pos) {
        return clientFor(level).getOrDefault(ChunkPos.pack(pos), 0);
    }

    /** Whether a client knows any mana in the air around it. */
    public static boolean clientKnowsAny(Level level) {
        return !clientFor(level).isEmpty();
    }

}

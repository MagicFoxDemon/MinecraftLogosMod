package dev.logos.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.lang.Weather;
import dev.logos.net.AreaWeatherPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.WeatherAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Area weather: columns of rain, storm, snow or clear sky, each a cylinder over a spot, laid over the
 * world's own weather. Where it rains is decided in one place ({@link #precipitationAt}, behind
 * {@code Level.precipitationAt}), so everything vanilla ties to rain follows: fire goes out, farmland
 * stays wet, cauldrons fill, lightning hurts more in it. Both sides answer from the zones they know:
 * the server from the live spells, a client from what the server last sent for that dimension.
 * Clients also fade the sky, fog and rain loudness by how close the camera is to a zone.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class AreaWeather {
    /** A zone as both sides see it: a vertical cylinder of {@code radius} around (x, z). */
    public record Zone(int id, Weather kind, double x, double z, double radius) {
        double edge(double px, double pz) {
            double dx = px - x, dz = pz - z;
            return Math.sqrt(dx * dx + dz * dz) - radius;
        }
    }

    /** Blocks over which a zone's sky and fog fade in, outside its edge. */
    static final double FADE = 8;
    /** A zone this close to the camera (past its edge) makes the rain renderer run; its columns stay inside the zone. */
    static final double RENDER_REACH = 24;

    private static final Map<ServerLevel, List<AreaWeatherObj>> SERVER = new HashMap<>();
    private static volatile Map<String, List<Zone>> client = Map.of();

    /** The client's view, eased tick by tick so a zone's sky does not snap on. */
    private static float skyRain, skyThunder, skyClear, render;

    private AreaWeather() {}

    // ---- server ------------------------------------------------------------------------------

    static void add(AreaWeatherObj obj) {
        SERVER.computeIfAbsent(obj.level(), l -> new ArrayList<>()).add(obj);
        sync(obj.level());
    }

    static void remove(AreaWeatherObj obj) {
        List<AreaWeatherObj> list = SERVER.get(obj.level());
        if (list == null || !list.remove(obj)) return;
        if (list.isEmpty()) SERVER.remove(obj.level());
        sync(obj.level());
    }

    /** Sends every zone of {@code level} to the players there. */
    static void sync(ServerLevel level) {
        AreaWeatherPayload payload = payload(level);
        for (ServerPlayer p : level.players()) send(p, payload);
    }

    /** Only to clients that speak it (a vanilla client or a test's mock player does not). */
    private static void send(ServerPlayer p, AreaWeatherPayload payload) {
        if (p.connection.hasChannel(AreaWeatherPayload.TYPE)) PacketDistributor.sendToPlayer(p, payload);
    }

    private static AreaWeatherPayload payload(ServerLevel level) {
        List<AreaWeatherPayload.Zone> zones = new ArrayList<>();
        for (Zone z : zones(level)) zones.add(new AreaWeatherPayload.Zone(z.id(), z.kind().ordinal(), z.x(), z.z(), (float) z.radius()));
        return new AreaWeatherPayload(level.dimension().identifier().toString(), zones);
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) send(p, payload(p.level()));
    }

    @SubscribeEvent
    static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) send(p, payload(p.level()));
    }

    public static void clearServer() {
        SERVER.clear();
    }

    // ---- both sides --------------------------------------------------------------------------

    private static List<Zone> zones(Level level) {
        if (level.isClientSide()) {
            Map<String, List<Zone>> all = client;
            return all.isEmpty() ? List.of() : all.getOrDefault(level.dimension().identifier().toString(), List.of());
        }
        List<AreaWeatherObj> objs = SERVER.get(level);
        if (objs == null) return List.of();
        List<Zone> out = new ArrayList<>(objs.size());
        for (AreaWeatherObj o : objs) out.add(o.zone());
        return out;
    }

    private static boolean any(Level level) {
        return level.isClientSide() ? !client.isEmpty() : !SERVER.isEmpty() && SERVER.containsKey(level);
    }

    /** The weather of the latest zone over (x, z), or null where none is. */
    public static @Nullable Weather at(Level level, double x, double z) {
        if (!any(level)) return null;
        List<Zone> zs = zones(level);
        for (int i = zs.size() - 1; i >= 0; i--) if (zs.get(i).edge(x, z) <= 0) return zs.get(i).kind();
        return null;
    }

    /** What falls at {@code pos} by area weather, as {@code Level.precipitationAt} answers; null where no zone is. */
    public static Biome.@Nullable Precipitation precipitationAt(Level level, BlockPos pos) {
        Weather w = at(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        if (w == null) return null;
        if (w == Weather.CLEAR || !level.canSeeSky(pos) || level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() > pos.getY()) {
            return Biome.Precipitation.NONE;
        }
        return falling(level, pos, w);
    }

    /** Rain turns to snow where it is cold, as vanilla rain does; a snow zone snows anywhere. */
    private static Biome.Precipitation falling(Level level, BlockPos pos, Weather w) {
        if (w == Weather.CLEAR) return Biome.Precipitation.NONE;
        if (w == Weather.SNOW) return Biome.Precipitation.SNOW;
        return level.getBiome(pos).value().coldEnoughToSnow(pos, level.getSeaLevel()) ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
    }

    /**
     * What the rain renderer draws in the column at {@code pos}: a zone's weather inside it; outside every
     * zone, nothing unless the world itself is raining (null: draw as vanilla would).
     */
    public static Biome.@Nullable Precipitation renderedAt(Level level, BlockPos pos) {
        if (!any(level)) return null;
        Weather w = at(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        if (w != null) return falling(level, pos, w);
        return level.getRainLevel(1) > 0 ? null : Biome.Precipitation.NONE;
    }

    /**
     * Snow and ice in a zone, in place of vanilla's (which only snows in cold biomes, only while the world
     * rains). Returns false where no zone is, to leave the column to vanilla.
     */
    public static boolean tickPrecipitation(ServerLevel level, BlockPos pos) {
        Weather w = at(level, pos.getX() + 0.5, pos.getZ() + 0.5);
        if (w == null) return false;
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        BlockPos below = top.below();
        if (!level.isAreaLoaded(below, 1)) return true;
        Biome biome = level.getBiome(top).value();
        BlockState under = level.getBlockState(below);
        boolean still = under.is(Blocks.WATER) && level.getFluidState(below).isSourceOfType(Fluids.WATER);
        if (w == Weather.SNOW ? still : biome.shouldFreeze(level, below)) level.setBlockAndUpdate(below, Blocks.ICE.defaultBlockState());
        if (w == Weather.CLEAR) return true;
        Biome.Precipitation p = falling(level, top, w);
        int maxHeight = level.getGameRules().get(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);
        if (p == Biome.Precipitation.SNOW && maxHeight > 0 && level.getBrightness(LightLayer.BLOCK, top) < 10) {
            BlockState state = level.getBlockState(top);
            if (state.is(Blocks.SNOW)) {
                int layers = state.getValue(SnowLayerBlock.LAYERS);
                if (layers < Math.min(maxHeight, 8)) {
                    BlockState more = state.setValue(SnowLayerBlock.LAYERS, layers + 1);
                    net.minecraft.world.level.block.Block.pushEntitiesUp(state, more, level, top);
                    level.setBlockAndUpdate(top, more);
                }
            } else if (state.isAir() && Blocks.SNOW.defaultBlockState().canSurvive(level, top)) {
                level.setBlockAndUpdate(top, Blocks.SNOW.defaultBlockState());
            }
        }
        BlockState belowState = level.getBlockState(below);
        belowState.getBlock().handlePrecipitation(belowState, level, below, p);
        return true;
    }

    // ---- client ------------------------------------------------------------------------------

    /** What the server sent for one dimension; replaces whatever was known there. */
    public static void receive(AreaWeatherPayload p) {
        List<Zone> zones = new ArrayList<>();
        for (AreaWeatherPayload.Zone z : p.zones()) zones.add(new Zone(z.id(), Weather.values()[z.kind()], z.x(), z.z(), z.radius()));
        Map<String, List<Zone>> next = new HashMap<>(client);
        if (zones.isEmpty()) next.remove(p.dimension());
        else next.put(p.dimension(), List.copyOf(zones));
        client = Map.copyOf(next);
    }

    public static void clearClient() {
        client = Map.of();
        skyRain = skyThunder = skyClear = render = 0;
    }

    /** Eases the camera's view of nearby zones; called once per client tick. */
    public static void clientTick(Level level, Vec3 camera) {
        float rain = 0, thunder = 0, clear = 0, near = 0;
        for (Zone z : zones(level)) {
            double edge = z.edge(camera.x, camera.z);
            float f = (float) Math.clamp(1 - edge / FADE, 0, 1);
            if (z.kind() == Weather.CLEAR) clear = Math.max(clear, f);
            else {
                rain = Math.max(rain, f);
                if (edge < RENDER_REACH) near = 1;
            }
            if (z.kind() == Weather.THUNDER) thunder = Math.max(thunder, f);
        }
        skyRain = ease(skyRain, rain);
        skyThunder = ease(skyThunder, thunder);
        skyClear = ease(skyClear, clear);
        render = near;
    }

    private static float ease(float from, float to) {
        return from + Math.clamp(to - from, -0.05f, 0.05f);
    }

    /** The rain level the sky, fog and sun see at the camera: the world's, raised by a storm nearby, lowered under a clear zone. */
    public static float skyRain(Level level, float world) {
        if (!level.isClientSide()) return world;
        return Math.max(world * (1 - skyClear), skyRain);
    }

    public static float skyThunder(Level level, float world) {
        if (!level.isClientSide()) return world;
        return Math.max(world * (1 - skyClear), skyThunder);
    }

    /** How hard the rain renderer draws: at least full near a zone, whose columns it then limits to the zone. */
    public static float renderRain(Level level, float world) {
        return level.isClientSide() ? Math.max(world, render) : world;
    }

    /**
     * A client's weather layer (sky colour, light) sees the eased camera view; a server's sees the world's.
     * Asked as each level is built, so the side is checked on every read, not here.
     */
    public static WeatherAttributes.WeatherAccess access(Level level, WeatherAttributes.WeatherAccess world) {
        return new WeatherAttributes.WeatherAccess() {
            @Override
            public float rainLevel() {
                return skyRain(level, world.rainLevel());
            }

            @Override
            public float thunderLevel() {
                return skyThunder(level, world.thunderLevel());
            }
        };
    }
}

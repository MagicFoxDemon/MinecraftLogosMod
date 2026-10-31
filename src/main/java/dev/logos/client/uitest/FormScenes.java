package dev.logos.client.uitest;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** What the form effect scenes (FORM_VFX.md: pillar burst, mana tornado, surface aura) share: the viewport, the ground they play on, moving the player. */
final class FormScenes {
    /** Where the effects play: on the arena's centre. */
    static final Vec3 AT = new Vec3(.5, 100, .5);

    private FormScenes() {}

    /** A 1280×720 view in first person with the GUI hidden; returns whether it was hidden before. */
    static boolean viewport() {
        var mc = Minecraft.getInstance();
        boolean hide = mc.options.hideGui;
        mc.options.hideGui = true;
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
        mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
        mc.options.fov().set(70); mc.options.renderDistance().set(8); mc.options.broadcastOptions();
        return hide;
    }

    static void restore(boolean hideGui) {
        Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
        Minecraft.getInstance().options.hideGui = hideGui;
    }

    /** Ground round {@link #AT}: stone in the middle, grass outside patched with dirt, coarse dirt and cobblestone, so debris shows its own textures. */
    static void arena(ServerLevel level) {
        BlockState[] patches = {Blocks.DIRT.defaultBlockState(), Blocks.COARSE_DIRT.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState()};
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 40; z++) {
            int hash = Math.floorMod(x * 73856093 ^ z * 19349663, 11);
            BlockState ground = x * x + z * z <= 6 ? Blocks.STONE.defaultBlockState()
                    : hash < 3 ? patches[hash] : Blocks.GRASS_BLOCK.defaultBlockState();
            level.setBlock(new BlockPos(x, 99, z), ground, 2);
            for (int y = 100; y < 112; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
    }

    static void teleport(ServerPlayer player, Vec3 pos, float yaw, float pitch) {
        ServerLevel level = player.level();
        level.getChunk(BlockPos.containing(pos));
        player.teleport(new TeleportTransition(level, pos, Vec3.ZERO, yaw, pitch, TeleportTransition.DO_NOTHING));
    }

    /** Puts the player at {@code from}, looking at {@code to}. */
    static void look(ServerPlayer player, Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z)), pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        teleport(player, from, yaw, pitch);
    }
}

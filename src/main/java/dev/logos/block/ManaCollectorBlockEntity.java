package dev.logos.block;

import dev.logos.LogosMod;
import dev.logos.runtime.ManaField;
import dev.logos.runtime.ManaInfusion;
import dev.logos.runtime.ManaVessel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 魔力收集器: once a second it takes in mana from the air of its chunk, ambient mana first and then
 * spread mana ({@link ManaField}), and passes what it holds, without loss, to the block it faces
 * (anything that holds mana: an alchemy table, a spreader, another collector…). When that block is
 * full it stops drawing, and the mana stays in the air; it glows while it draws.
 */
public class ManaCollectorBlockEntity extends BlockEntity implements ManaVessel {
    public static final double MAX_MANA = 200;
    /** Most mana it passes on each second. */
    public static final double PASS = 20;

    private double mana;
    /** What it drew last second from ambient and from spread mana, for its screen's gauge. */
    private double lastAmbient, lastSpread;

    public ManaCollectorBlockEntity(BlockPos pos, BlockState state) {
        super(LogosMod.MANA_COLLECTOR_BE.get(), pos, state);
    }

    @Override
    public double mana() {
        return mana;
    }

    public double lastAmbient() {
        return lastAmbient;
    }

    public double lastSpread() {
        return lastSpread;
    }

    @Override
    public double maxMana() {
        return MAX_MANA;
    }

    @Override
    public double receiveMana(double amount) {
        double taken = Math.clamp(amount, 0, MAX_MANA - mana);
        if (taken > 0) {
            mana += taken;
            setChanged();
        }
        return taken;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ManaCollectorBlockEntity collector) {
        if (!(level instanceof ServerLevel server) || !ManaField.working(level)) return;
        double room = MAX_MANA - collector.mana;
        double ambient = ManaField.drawAmbient(server, pos, room);
        double spread = ManaField.drawSpread(server, pos, room - ambient);
        collector.mana += ambient + spread;
        collector.lastAmbient = ambient;
        collector.lastSpread = spread;
        ManaVessel target = ManaInfusion.vesselAt(level, pos.relative(state.getValue(ManaCollectorBlock.FACING)));
        if (target != null && target != collector) collector.mana -= target.receiveMana(Math.min(PASS, collector.mana));
        if (ambient + spread > 0 || target != null) collector.setChanged();
        // Lit while it draws; dark when it and what it feeds are full.
        ManaSpreaderBlock.light(level, pos, state, ambient + spread > 0);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putDouble("mana", mana);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        mana = input.getDoubleOr("mana", 0);
    }
}

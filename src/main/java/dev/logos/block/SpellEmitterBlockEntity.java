package dev.logos.block;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.mojang.authlib.GameProfile;

import dev.logos.LogosMod;
import dev.logos.graph.SpecialNodes.EmitterMode;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Compiler;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Charging;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.ManaVessel;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.Vfx;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * 术式发射器: keeps a store of mana (fed by a collector facing it, or by 灌魔), and charges the
 * spell on its page from it by the wand's rule ({@link Charging}) at a third-grade wand's flow and
 * surge; the page caps what one cast injects. A full charge waits for its trigger ({@link EmitterMode}):
 * a redstone pulse, a held signal, a timer, or none at all. Casts that go off by themselves cost
 * {@link #AUTO_COST} more. It casts through a {@link Shadow} of whoever placed it, from its centre,
 * aiming where it faces.
 */
public class SpellEmitterBlockEntity extends BlockEntity implements ManaVessel {
    public static final double MAX_MANA = 1000;
    /** A third-grade wand's flow and surge (CONTRIBUTING, 法杖数值). */
    public static final double FLOW = 70, SURGE = 130;
    /** What a cast costs on top of its injection when nothing but the emitter itself set it off. */
    public static final double AUTO_COST = 2;
    public static final int DEFAULT_INTERVAL = 5, MAX_INTERVAL = 3600;

    private ItemStack page = ItemStack.EMPTY;
    private double mana;
    private EmitterMode mode = EmitterMode.PULSE;
    /** Seconds between casts in {@link EmitterMode#TIMED}. */
    private int interval = DEFAULT_INTERVAL;
    private @Nullable UUID owner;
    private String ownerName = "";
    /** The owner's stability when they last set the page; a spell more complex than that will not go off. -1: never set. */
    private int ownerStability = -1;
    /** The charge: mana injected so far into the next cast, and the ticks paid for. */
    private double injected;
    private int done;
    /** A cast asked for (a pulse, or 试射) that goes off once the charge is full. */
    private boolean pending;
    private int timer;
    private @Nullable Shadow shadow;

    public SpellEmitterBlockEntity(BlockPos pos, BlockState state) {
        super(LogosMod.SPELL_EMITTER_BE.get(), pos, state);
    }

    public ItemStack page() {
        return page;
    }

    @Override
    public double mana() {
        return mana;
    }

    @Override
    public double maxMana() {
        return MAX_MANA;
    }

    public double injected() {
        return injected;
    }

    public EmitterMode mode() {
        return mode;
    }

    public int interval() {
        return interval;
    }

    public @Nullable UUID owner() {
        return owner;
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

    void setOwner(Player player) {
        owner = player.getUUID();
        ownerName = player.getGameProfile().name();
        setChanged();
    }

    /** Whether {@code player} may change it: whoever placed it (anyone, if no one did). Tells anyone else whose it is. */
    public boolean mayChange(Player player) {
        if (owner == null || owner.equals(player.getUUID())) return true;
        player.sendOverlayMessage(Component.literal("这台术式发射器属于 " + ownerName + "，只有放置者能更改它").withStyle(ChatFormatting.YELLOW));
        return false;
    }

    /** Puts in or takes out the page; whatever was charged for the old page goes back into the store. */
    public void setPage(ItemStack stack, @Nullable Player by) {
        page = stack;
        refund();
        if (by != null) ownerStability = PlayerMagic.of(by).stability;
        changed();
    }

    /** Writes an edit made in the workbench: the page's new spell and the emitter's own settings. */
    public void edit(ItemStack written, EmitterMode mode, int interval, Player by) {
        this.mode = mode;
        this.interval = Math.clamp(interval, 1, MAX_INTERVAL);
        timer = 0;
        pending = false;
        setPage(written, by);
    }

    /** A redstone pulse: in pulse mode it asks for one cast. */
    void pulse() {
        if (mode == EmitterMode.PULSE) pending = true;
    }

    /** 试射: one cast as soon as the charge is full, whatever the mode. */
    public void testFire() {
        pending = true;
    }

    private void refund() {
        if (injected > 0 && level instanceof ServerLevel server) {
            double back = Math.min(injected, MAX_MANA - mana);
            mana += back;
            if (injected > back) FreeManaEntity.release(server, worldPosition.getCenter(), injected - back);
        }
        injected = 0;
        done = 0;
    }

    private void changed() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    /** The active spell on its page, or null. */
    public @Nullable Spell spell() {
        Spell s = SpellPages.isInscribed(page) ? SpellPages.spell(page) : null;
        return s == null || s.isPassive() ? null : s;
    }

    private PageTier tier() {
        PageTier t = SpellPages.tier(page);
        return t == null ? PageTier.BASIC : t;
    }

    /** What one cast injects: the spell's mana, as far as the page holds. No immolation: there is no wand. */
    public double target() {
        Spell s = spell();
        return s == null ? 0 : Casting.injection(s, tier(), 0).mana();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SpellEmitterBlockEntity emitter) {
        if (level instanceof ServerLevel server) emitter.tick(server, state);
    }

    private void tick(ServerLevel level, BlockState state) {
        Spell spell = spell();
        double target = target();
        if (spell == null || injected > target + 1e-9) refund();
        if (spell != null && injected < target - 1e-9) {
            int ticks = Charging.ticks(target, FLOW, SURGE);
            double share = Charging.injectedAfter(done + 1, target, FLOW, SURGE, ticks) - injected;
            if (mana + 1e-9 >= share) {
                mana = Math.max(0, mana - share);
                injected += share;
                done++;
                if (injected >= target - 1e-9) Vfx.sound(level, worldPosition.getCenter(), SoundEvents.AMETHYST_BLOCK_CHIME, 0.6f, 1.6f);
                setChanged();
            }
        }
        boolean full = spell != null && target > 0 && injected >= target - 1e-9;
        if (mode == EmitterMode.TIMED && timer < interval * 20) timer++;
        if (full) {
            if (pending) fire(level, spell, 0);
            else if (mode == EmitterMode.HOLD && state.getValue(BlockStateProperties.TRIGGERED)) fire(level, spell, 0);
            else if (mode == EmitterMode.TIMED && timer >= interval * 20 && mana + 1e-9 >= AUTO_COST) fire(level, spell, AUTO_COST);
            else if (mode == EmitterMode.FULL && mana + 1e-9 >= AUTO_COST) fire(level, spell, AUTO_COST);
        }
        ManaSpreaderBlock.light(level, worldPosition, level.getBlockState(worldPosition), mana > 0 || injected > 0);
    }

    /** Lets go of the full charge into the spell, paying {@code extra} from the store. */
    private void fire(ServerLevel level, Spell spell, double extra) {
        pending = false;
        timer = 0;
        Vec3 centre = worldPosition.getCenter();
        if (ownerStability >= 0 && spell.stability() > ownerStability) {
            Vfx.sound(level, centre, SoundEvents.FIRE_EXTINGUISH, 0.5f, 1.4f);
            return;
        }
        mana = Math.max(0, mana - extra);
        double charge = injected;
        injected = 0;
        done = 0;
        setChanged();
        var element = Compiler.inferElement(spell.body());
        Vfx.sound(level, centre, SoundEvents.EVOKER_CAST_SPELL, 0.6f, 1.4f);
        Vfx.burst(level, centre, element, 6);
        Casting.castAs(caster(level), spell, charge, tier().recovery(), null, centre, true);
    }

    /** The owner's stand-in, its eyes at the centre and looking where the emitter faces. */
    public Shadow caster(ServerLevel level) {
        if (shadow == null || shadow.level() != level) {
            GameProfile profile = owner == null ? new GameProfile(UUIDUtil.createOfflinePlayerUUID("SpellEmitter"), "SpellEmitter")
                    : new GameProfile(owner, ownerName.isEmpty() ? "SpellEmitter" : ownerName);
            shadow = new Shadow(level, profile, worldPosition);
        }
        Direction facing = getBlockState().getValue(SpellEmitterBlock.FACING);
        Vec3 centre = worldPosition.getCenter();
        shadow.setPos(centre.x, centre.y - shadow.getEyeHeight(), centre.z);
        float yRot = facing.getAxis().isHorizontal() ? facing.toYRot() : 0;
        float xRot = facing == Direction.UP ? -90 : facing == Direction.DOWN ? 90 : 0;
        shadow.setYRot(yRot);
        shadow.setYHeadRot(yRot);
        shadow.setXRot(xRot);
        // Their anchors go with them, as they were when last seen.
        ServerPlayer online = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        if (online != null) shadow.setData(LogosMod.ANCHORS, online.getData(LogosMod.ANCHORS));
        return shadow;
    }

    /**
     * Who an emitter casts as: a stand-in for whoever placed it, who may be away. It carries their name
     * and id, so damage, kills and permissions are theirs; the emitter it stands in lets its spells through.
     */
    public static final class Shadow extends FakePlayer {
        private final BlockPos home;

        Shadow(ServerLevel level, GameProfile profile, BlockPos home) {
            super(level, profile);
            this.home = home;
        }

        public BlockPos home() {
            return home;
        }

        /** As a player would: unless PvP is off or they are on one team without friendly fire. */
        @Override
        public boolean canHarmPlayer(Player other) {
            if (!level().isPvpAllowed()) return false;
            PlayerTeam mine = getTeam(), theirs = other.getTeam();
            return mine == null || !mine.isAlliedTo(theirs) || mine.isAllowFriendlyFire();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (!page.isEmpty()) output.store("page", ItemStack.CODEC, page);
        output.putDouble("mana", mana);
        output.putDouble("injected", injected);
        output.putInt("done", done);
        output.putString("mode", mode.name());
        output.putInt("interval", interval);
        if (owner != null) output.store("owner", UUIDUtil.CODEC, owner);
        output.putString("owner_name", ownerName);
        output.putInt("owner_stability", ownerStability);
        output.putBoolean("pending", pending);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        page = input.read("page", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        mana = input.getDoubleOr("mana", 0);
        injected = input.getDoubleOr("injected", 0);
        done = input.getIntOr("done", 0);
        mode = EmitterMode.of(input.getStringOr("mode", EmitterMode.PULSE.name()));
        interval = Math.clamp(input.getIntOr("interval", DEFAULT_INTERVAL), 1, MAX_INTERVAL);
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        ownerName = input.getStringOr("owner_name", "");
        ownerStability = input.getIntOr("owner_stability", -1);
        pending = input.getBooleanOr("pending", false);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Broken: the page drops, and the mana it held goes out free. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level == null) return;
        if (!page.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, page);
        page = ItemStack.EMPTY;
        if (level instanceof ServerLevel server && mana + injected > 0) FreeManaEntity.release(server, pos.getCenter(), mana + injected);
        mana = 0;
        injected = 0;
    }
}

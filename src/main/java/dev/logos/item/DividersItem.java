package dev.logos.item;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosMod;
import dev.logos.runtime.Offsets;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 两脚规: measures where one block lies from another, as forward, up and right of the first (the
 * {@code offset} rune's terms). Right-click the first block, usually a spell emitter, then the target;
 * forward is the first block's facing, or the way the player faced if it has none. The workbench
 * pastes the measurement as an offset node. Sneak + right-click folds it up again.
 */
public final class DividersItem extends Item {
    /** Farther than this, or in another world, an anchor serves better than an offset. */
    public static final int REACH = 48;

    /** Where it was set down and which way is forward there; once measured, how far the target lies. */
    public record Measure(BlockPos from, Identifier dimension, Direction forward, boolean done, int f, int u, int r) {
        public static final Codec<Measure> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("from").forGetter(Measure::from),
                Identifier.CODEC.fieldOf("dimension").forGetter(Measure::dimension),
                Direction.CODEC.fieldOf("forward").forGetter(Measure::forward),
                Codec.BOOL.fieldOf("done").forGetter(Measure::done),
                Codec.INT.fieldOf("f").forGetter(Measure::f),
                Codec.INT.fieldOf("u").forGetter(Measure::u),
                Codec.INT.fieldOf("r").forGetter(Measure::r)).apply(i, Measure::new));

        /** "前 3、上 1、右 0", as the offset rune reads it. */
        public Component describe() {
            return Component.translatable("logos.dividers.offset", f, u, r);
        }
    }

    public DividersItem(Properties props) {
        super(props);
    }

    /** The finished measurement on {@code stack}, or null. */
    public static @Nullable Measure measured(ItemStack stack) {
        Measure m = stack.get(LogosMod.DIVIDERS.get());
        return m != null && m.done() ? m : null;
    }

    /** The first finished measurement the player carries, the hands first: what the workbench pastes. */
    public static @Nullable Measure carried(Player player) {
        for (var hand : net.minecraft.world.InteractionHand.values()) {
            Measure m = measured(player.getItemInHand(hand));
            if (m != null) return m;
        }
        for (ItemStack s : player.getInventory()) {
            Measure m = measured(s);
            if (m != null) return m;
        }
        return null;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack held, UseOnContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (ctx.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        ItemStack stack = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos();
        Identifier dimension = ctx.getLevel().dimension().identifier();
        Measure m = stack.get(LogosMod.DIVIDERS.get());
        if (player.isSecondaryUseActive()) {
            stack.remove(LogosMod.DIVIDERS.get());
            player.sendOverlayMessage(Component.translatable("logos.dividers.put_away").withStyle(ChatFormatting.GRAY));
        } else if (m == null || m.done()) {
            Direction forward = forwardOf(ctx.getLevel().getBlockState(pos), player);
            stack.set(LogosMod.DIVIDERS.get(), new Measure(pos, dimension, forward, false, 0, 0, 0));
            player.sendOverlayMessage(Component.translatable("logos.dividers.started", name(forward)).withStyle(ChatFormatting.AQUA));
        } else if (!m.dimension().equals(dimension)) {
            player.sendOverlayMessage(Component.translatable("logos.dividers.other_dimension").withStyle(ChatFormatting.YELLOW));
        } else {
            int[] d = Offsets.measure(m.from(), m.forward(), pos);
            Measure done = new Measure(m.from(), m.dimension(), m.forward(), true, d[0], d[1], d[2]);
            stack.set(LogosMod.DIVIDERS.get(), done);
            boolean far = Math.max(Math.abs(d[0]), Math.max(Math.abs(d[1]), Math.abs(d[2]))) > REACH;
            player.sendOverlayMessage(Component.translatable(far ? "logos.dividers.measured_far" : "logos.dividers.measured", done.describe())
                    .withStyle(far ? ChatFormatting.YELLOW : ChatFormatting.AQUA));
        }
        return InteractionResult.SUCCESS;
    }

    /** Forward at the first block: its facing (south if it faces up or down), or else the way the player faced. */
    static Direction forwardOf(BlockState state, Player player) {
        if (state.hasProperty(BlockStateProperties.FACING)) return Offsets.forward(state.getValue(BlockStateProperties.FACING));
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        return player.getDirection();
    }

    private static Component name(Direction d) {
        return Component.translatable("logos.direction." + d.getSerializedName());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        Measure m = stack.get(LogosMod.DIVIDERS.get());
        if (m == null) builder.accept(Component.translatable("logos.dividers.hint").withStyle(ChatFormatting.GRAY));
        else if (!m.done()) builder.accept(Component.translatable("logos.dividers.from", m.from().toShortString(), name(m.forward())).withStyle(ChatFormatting.GRAY));
        else builder.accept(m.describe().copy().withStyle(ChatFormatting.AQUA));
        builder.accept(Component.translatable("logos.dividers.use_hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}

package dev.logos.item;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.runtime.Lifeforms;
import dev.logos.runtime.ManaLifeform;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Spawner;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.HitResult;

/** A tiered vanilla egg: a full lifeform of its level, loyal to the player who raises it. */
public final class ManaLifeformSpawnEggItem extends SpawnEggItem {
    private final int lifeLevel;

    public ManaLifeformSpawnEggItem(Properties props, int lifeLevel) {
        super(props);
        this.lifeLevel = lifeLevel;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("item.logos.mana_lifeform_spawn_egg.mana", Math.round(ManaLifeform.maxMana(lifeLevel)))
                .withStyle(ChatFormatting.AQUA));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (getType(ctx.getItemInHand()) != LogosMod.MANA_LIFEFORM.get()
                || ctx.getLevel().getBlockEntity(ctx.getClickedPos()) instanceof Spawner) return super.useOn(ctx);
        if (!(ctx.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos clicked = ctx.getClickedPos();
        BlockPos at = level.getBlockState(clicked).getCollisionShape(level, clicked).isEmpty()
                ? clicked : clicked.relative(ctx.getClickedFace());
        return spawn(level, ctx.getItemInHand(), ctx.getPlayer(), at, EntitySpawnReason.SPAWN_ITEM_USE,
                true, !at.equals(clicked) && ctx.getClickedFace() == Direction.UP);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (getType(stack) != LogosMod.MANA_LIFEFORM.get()) return super.use(level, player, hand);
        var hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel server)) return InteractionResult.SUCCESS;
        BlockPos at = hit.getBlockPos();
        if (!(level.getBlockState(at).getBlock() instanceof LiquidBlock)) return InteractionResult.PASS;
        if (!level.mayInteract(player, at) || !player.mayUseItemAt(at, hit.getDirection(), stack)) return InteractionResult.FAIL;
        var result = spawn(server, stack, player, at, EntitySpawnReason.SPAWN_ITEM_USE, false, false);
        if (result == InteractionResult.SUCCESS) player.awardStat(Stats.ITEM_USED.get(this));
        return result;
    }

    private InteractionResult spawn(ServerLevel level, ItemStack stack, @Nullable Player player, BlockPos at,
            EntitySpawnReason reason, boolean moveDown, boolean movedUp) {
        var type = getType(stack);
        if (type == null || type == LogosMod.MANA_LIFEFORM.get() && LogosConfig.maxLifeforms() <= 0) return InteractionResult.FAIL;
        var entity = type.create(level, EntityType.createDefaultStackConfig(level, stack, player), at, reason, moveDown, movedUp);
        if (entity == null) return InteractionResult.FAIL;
        if (entity instanceof ManaLifeform lf) {
            lf.setLifeLevel(lifeLevel);
            lf.setMana(lf.maxMana());
            if (player instanceof ServerPlayer sp) Lifeforms.bindSummoned(lf, sp);
        }
        level.addFreshEntityWithPassengers(entity);
        stack.consume(1, player);
        level.gameEvent(player, GameEvent.ENTITY_PLACE, at);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected DispenseItemBehavior createDispenseBehavior() {
        return new DefaultDispenseItemBehavior() {
            @Override
            protected ItemStack execute(BlockSource source, ItemStack stack) {
                Direction facing = source.state().getValue(DispenserBlock.FACING);
                spawn(source.level(), stack, null, source.pos().relative(facing), EntitySpawnReason.DISPENSER,
                        facing != Direction.UP, false);
                return stack;
            }
        };
    }
}

package dev.logos.block;

import dev.logos.LogosMod;
import dev.logos.item.SpellPages;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 术式拓印台: the original (a written page or an ember) and the empty pages to copy onto. The
 * copy itself is never stored; {@link #result} works it out from what lies here.
 */
public class CopyTableBlockEntity extends BlockEntity {
    public static final int ORIGINAL = 0, BLANK = 1;

    private final SimpleContainer inputs = new SimpleContainer(2) {
        @Override
        public void setChanged() {
            super.setChanged();
            CopyTableBlockEntity.this.setChanged();
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return slot == ORIGINAL ? isOriginal(stack) : SpellPages.isBlank(stack);
        }
    };

    /** The copy on offer; taking it uses up one empty page. */
    private final Container result = new SimpleContainer(1) {
        @Override
        public ItemStack getItem(int slot) {
            return result();
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return result();
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return result();
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            // Computed from the inputs; nothing to store.
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return false;
        }
    };

    public CopyTableBlockEntity(BlockPos pos, BlockState state) {
        super(LogosMod.COPY_TABLE_BE.get(), pos, state);
    }

    @Override
    public void setChanged() {
        super.setChanged();
        syncAppearance();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        syncAppearance();
    }

    private void syncAppearance() {
        if (level == null || level.isClientSide()) return;
        BlockState state = getBlockState();
        if (!state.hasProperty(CopyTableBlock.LEFT_PAGE)) return;
        BlockState shown = state.setValue(CopyTableBlock.LEFT_PAGE, CopyTableBlock.Sheet.of(inputs.getItem(ORIGINAL)))
                .setValue(CopyTableBlock.RIGHT_PAGE, SpellPages.isBlank(inputs.getItem(BLANK))
                        ? CopyTableBlock.Sheet.of(inputs.getItem(BLANK)) : CopyTableBlock.Sheet.NONE);
        if (shown != state) level.setBlock(worldPosition, shown, Block.UPDATE_ALL);
    }

    /** A written page, or the ember of a burnt one: anything that still carries a spell. */
    public static boolean isOriginal(ItemStack stack) {
        return (SpellPages.isInscribed(stack) || SpellPages.isEmber(stack)) && stack.has(LogosMod.SPELL_SOURCE.get());
    }

    public SimpleContainer inputs() {
        return inputs;
    }

    public Container resultContainer() {
        return result;
    }

    /** The original's spell (and drawing) written onto one of the empty pages, keeping that page's grade. */
    public ItemStack result() {
        ItemStack original = inputs.getItem(ORIGINAL), blank = inputs.getItem(BLANK);
        if (!isOriginal(original) || !SpellPages.isBlank(blank)) return ItemStack.EMPTY;
        CompoundTag graph = original.get(LogosMod.SPELL_GRAPH.get());
        return SpellPages.write(blank.copyWithCount(1), original.get(LogosMod.SPELL_SOURCE.get()), graph);
    }

    /** Called when a copy is taken: the empty page it was written on is used up. */
    public void onCopyTaken(Player player) {
        inputs.removeItem(BLANK, 1);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        for (int i = 0; i < inputs.getContainerSize(); i++) {
            ItemStack s = inputs.getItem(i);
            if (!s.isEmpty()) output.store("slot" + i, ItemStack.CODEC, s);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        for (int i = 0; i < inputs.getContainerSize(); i++) inputs.setItem(i, input.read("slot" + i, ItemStack.CODEC).orElse(ItemStack.EMPTY));
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) Containers.dropContents(level, pos, inputs);
    }
}

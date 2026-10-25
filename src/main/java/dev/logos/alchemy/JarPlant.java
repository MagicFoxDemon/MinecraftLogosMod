package dev.logos.alchemy;

import java.util.Collections;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * A plant that grows in one block of a cultivation jar, stage by stage on its {@code age} property:
 * any {@link VegetationBlock} with one (crops, sweet berry bushes, nether wart, and most modded crops and
 * bushes), except stems, whose fruit grows beside them, and two-block plants. No tag names such plants,
 * so they are told by their shape; the block tags below add or remove ones the shape gets wrong.
 * <p>
 * Harvested, a crop (or a plant tagged {@link #REPLANTED}) gives its loot less a seed to replant, as a
 * field is replanted; any other plant fruits where it stands, gives all its loot and grows back from
 * stage 1, as a sweet berry bush does when picked.
 */
public record JarPlant(Block block, IntegerProperty age) {
    /** Plants that grow in a jar though their shape says otherwise (they still need an {@code age}). */
    public static final TagKey<Block> GROWS = tag("grows_in_cultivation_jar");
    /** Plants kept out of jars though their shape would let them in. */
    public static final TagKey<Block> EXCLUDED = tag("not_in_cultivation_jar");
    /** Plants harvested like crops, a seed taken from the loot to replant (nether wart). */
    public static final TagKey<Block> REPLANTED = tag("replanted_in_cultivation_jar");

    private static TagKey<Block> tag(String name) {
        return TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(LogosMod.MODID, name));
    }

    /** {@code block} as a plant of a jar, or null if it does not grow in one. */
    public static @Nullable JarPlant of(@Nullable Block block) {
        if (block == null) return null;
        BlockState state = block.defaultBlockState();
        if (state.is(EXCLUDED) || !(block.getStateDefinition().getProperty("age") instanceof IntegerProperty age)) return null;
        boolean shaped = block instanceof VegetationBlock && !(block instanceof StemBlock) && !(block instanceof DoublePlantBlock);
        return shaped || state.is(GROWS) ? new JarPlant(block, age) : null;
    }

    public int maxAge() {
        return Collections.max(age.getPossibleValues());
    }

    public BlockState state(int stage) {
        return block.defaultBlockState().setValue(age, Math.clamp(stage, Collections.min(age.getPossibleValues()), maxAge()));
    }

    /** Whether a harvest takes a seed out of the loot to replant, rather than the plant fruiting again where it stands. */
    public boolean replanted() {
        return block instanceof CropBlock || block.defaultBlockState().is(REPLANTED);
    }

    /** The stage it grows back from once picked: the first, or the second for a plant that fruits again (as a picked berry bush). */
    public int regrowFrom() {
        return replanted() ? 0 : Math.min(1, maxAge());
    }
}

package dev.logos.gametest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.block.JarCropBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Ground substrates stay opt-in and are independent for each jar crop. */
final class JarCropGameTests {
    private static final BlockPos PLANT = new BlockPos(2, 2, 2);

    private JarCropGameTests() {}

    /** Every bundled jar crop has a distinct, empty substrate tag and rejects ordinary ground. */
    static void emptySubstrates(GameTestHelper h) {
        h.setBlock(PLANT.west(), Blocks.GLOWSTONE);
        Set<TagKey<Block>> tags = new HashSet<>();
        var crops = BuiltInRegistries.BLOCK.stream().filter(JarCropBlock.class::isInstance).map(JarCropBlock.class::cast).toList();
        h.assertTrue(!crops.isEmpty(), "no jar crops registered");
        for (JarCropBlock crop : crops) {
            h.assertTrue(tags.add(crop.plantableOn()), "jar crops share substrate tag " + crop.plantableOn());
            var substrates = BuiltInRegistries.BLOCK.get(crop.plantableOn());
            h.assertTrue(substrates.isPresent() && substrates.get().size() == 0, "substrate tag missing or nonempty: " + crop.plantableOn());
            for (Block soil : List.of(Blocks.DIRT, Blocks.FARMLAND, Blocks.STONE, Blocks.AIR)) {
                h.setBlock(PLANT.below(), soil);
                h.assertFalse(crop.defaultBlockState().canSurvive(h.getLevel(), h.absolutePos(PLANT)),
                        crop + " survives on untagged " + soil);
            }
        }
        h.succeed();
    }

    /** Reloaded tags permit only each crop's substrates, and removing entries takes effect immediately. */
    static void taggedSubstrates(GameTestHelper h) {
        var registry = BuiltInRegistries.BLOCK;
        Map<TagKey<Block>, List<Holder<Block>>> original = new HashMap<>();
        registry.getTags().forEach(tag -> original.put(tag.key(), tag.stream().toList()));
        var mandrake = LogosMod.MANDRAKE_CROP.get();
        var mercury = LogosMod.MERCURY_MUSHROOM_CROP.get();
        var moss = LogosMod.STARLIGHT_MOSS_CROP.get();
        h.assertTrue(mandrake.plantableOn().location().equals(Identifier.fromNamespaceAndPath("logos", "plantable_on/mandrake_crop")),
                "unexpected mandrake substrate tag");
        Map<TagKey<Block>, List<Holder<Block>>> modified = new HashMap<>(original);
        modified.put(mandrake.plantableOn(), List.of(Blocks.FARMLAND.builtInRegistryHolder()));
        modified.put(mercury.plantableOn(), List.of(Blocks.MYCELIUM.builtInRegistryHolder()));
        h.setBlock(PLANT.west(), Blocks.GLOWSTONE);
        // Keep the tag reload and assertions synchronous, restoring the registry before another test ticks.
        try {
            registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, modified)).apply();
            for (Block soil : List.of(Blocks.FARMLAND, Blocks.MYCELIUM, Blocks.STONE, Blocks.AIR)) {
                h.setBlock(PLANT.below(), soil);
                h.assertTrue(mandrake.defaultBlockState().canSurvive(h.getLevel(), h.absolutePos(PLANT)) == (soil == Blocks.FARMLAND),
                        "mandrake substrate mismatch on " + soil);
                h.assertTrue(mercury.defaultBlockState().canSurvive(h.getLevel(), h.absolutePos(PLANT)) == (soil == Blocks.MYCELIUM),
                        "mercury mushroom substrate mismatch on " + soil);
                h.assertFalse(moss.defaultBlockState().canSurvive(h.getLevel(), h.absolutePos(PLANT)),
                        "starlight moss inherited another crop's substrates");
            }
        } finally {
            registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, original)).apply();
        }
        h.setBlock(PLANT.below(), Blocks.FARMLAND);
        h.assertFalse(mandrake.defaultBlockState().canSurvive(h.getLevel(), h.absolutePos(PLANT)),
                "mandrake still accepts farmland after its substrate tag was emptied");
        h.succeed();
    }
}

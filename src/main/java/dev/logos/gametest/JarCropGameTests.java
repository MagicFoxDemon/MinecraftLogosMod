package dev.logos.gametest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.logos.LogosMod;
import dev.logos.block.CultivationJarBlockEntity;
import dev.logos.block.JarCropBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Magic crops share ground and jar loot while their ground substrates remain opt-in. */
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

    /** Every magic crop drops its planting material, reserving exactly one in either jar harvest mode. */
    static void seedLoot(GameTestHelper h) {
        var crops = magicCrops();
        h.assertTrue(crops.size() == BuiltInRegistries.BLOCK.stream().filter(JarCropBlock.class::isInstance).count(),
                "seed loot fixtures do not cover every magic crop");
        for (int i = 0; i < crops.size(); i++) {
            CropYield crop = crops.get(i);
            BlockPos at = new BlockPos(i % 5, 2, 3 + i / 5);
            for (int age = 0; age <= crop.crop.getMaxAge(); age++) {
                boolean ripe = age == crop.crop.getMaxAge();
                var drops = Block.getDrops(crop.crop.getStateForAge(age), h.getLevel(), h.absolutePos(at), null);
                assertYield(h, crop, drops, ripe ? crop.seeds : 1, ripe ? crop.materials : 0, "ground age " + age);
            }
            h.setBlock(at, LogosMod.CULTIVATION_JAR.get());
            var jar = (CultivationJarBlockEntity) h.getBlockEntity(at, CultivationJarBlockEntity.class);
            h.assertTrue(jar.plant(new ItemStack(crop.seed)), crop.crop + " cannot be planted from its seed");
            h.assertTrue(jar.recipe().harvest().isEmpty(), crop.crop + " still uses a fixed recipe harvest");
            for (int harvest = 0; harvest < 2; harvest++) {
                jar.receiveMana(CultivationJarBlockEntity.MAX_MANA);
                while (jar.grow()) {}
                h.assertTrue(jar.isRipe(), crop.crop + " did not ripen");
                h.assertTrue(jar.mana() == CultivationJarBlockEntity.MAX_MANA - crop.crop.getMaxAge() * jar.recipe().mana(),
                        crop.crop + " growth mana cost changed");
                List<ItemStack> drops;
                if (harvest == 0) {
                    jar.randomTick(h.getLevel(), h.getLevel().getRandom());
                    drops = new ArrayList<>();
                    for (int slot = 0; slot < jar.outputs().size(); slot++) {
                        drops.add(jar.outputs().getResource(slot).toStack(jar.outputs().getAmountAsInt(slot)));
                    }
                } else drops = jar.harvest(h.getLevel());
                assertYield(h, crop, drops, crop.seeds - 1, crop.materials, harvest == 0 ? "automatic jar" : "manual jar");
                h.assertTrue(jar.seed().is(crop.seed) && jar.seed().getCount() == 1 && jar.age() == 0,
                        crop.crop + " did not reserve exactly one seed at age 0");
            }
            h.assertTrue(jar.uproot().is(crop.seed) && jar.isEmpty(), crop.crop + " cannot return its reserved seed");
        }
        h.succeed();
    }

    /** An add-on's substrate tag is sufficient for every crop's inherited ground growth to ripen it. */
    static void groundGrowth(GameTestHelper h) {
        FamiliarGameTests.forceAround(h, 1);
        h.setBlock(PLANT.west(), Blocks.GLOWSTONE);
        h.setBlock(PLANT.below(), Blocks.FARMLAND);
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().isAreaLoaded(h.absolutePos(PLANT), 1), "growth area is not loaded");
            h.assertTrue(h.getLevel().getRawBrightness(h.absolutePos(PLANT), 0) >= 9, "growth light is not ready");
            var registry = BuiltInRegistries.BLOCK;
            Map<TagKey<Block>, List<Holder<Block>>> original = new HashMap<>();
            registry.getTags().forEach(tag -> original.put(tag.key(), tag.stream().toList()));
            Map<TagKey<Block>, List<Holder<Block>>> modified = new HashMap<>(original);
            for (CropYield crop : magicCrops()) modified.put(crop.crop.plantableOn(), List.of(Blocks.FARMLAND.builtInRegistryHolder()));
            try {
                registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, modified)).apply();
                for (CropYield crop : magicCrops()) {
                    h.setBlock(PLANT, crop.crop);
                    var state = h.getBlockState(PLANT);
                    h.assertTrue(state.canSurvive(h.getLevel(), h.absolutePos(PLANT)), crop.crop + " rejects its tagged ground");
                    h.assertTrue(state.isRandomlyTicking(), crop.crop + " does not receive random growth ticks");
                    var random = RandomSource.create(0);
                    for (int tick = 0; tick < 512 && !crop.crop.isMaxAge(state); tick++) {
                        state.randomTick(h.getLevel(), h.absolutePos(PLANT), random);
                        state = h.getBlockState(PLANT);
                    }
                    h.assertTrue(state.is(crop.crop) && crop.crop.isMaxAge(state), crop.crop + " did not ripen on the ground");
                }
            } finally {
                h.setBlock(PLANT, Blocks.AIR);
                registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, original)).apply();
            }
        });
    }

    private record CropYield(JarCropBlock crop, Item seed, Item material, int materials, int seeds) {}

    private static List<CropYield> magicCrops() {
        List<CropYield> crops = new ArrayList<>(List.of(
                new CropYield(LogosMod.NITER_SPROUT_CROP.get(), LogosMod.NITER_SPROUT.get(), LogosMod.SALTPETER.get(), 2, 3),
                new CropYield(LogosMod.MANDRAKE_CROP.get(), LogosMod.MANDRAKE_SEED.get(), LogosMod.MANDRAKE_ROOT.get(), 2, 2),
                new CropYield(LogosMod.MERCURY_MUSHROOM_CROP.get(), LogosMod.MERCURY_SPORE.get(), LogosMod.MERCURY_MUSHROOM.get(), 2, 2),
                new CropYield(LogosMod.STARLIGHT_MOSS_CROP.get(), LogosMod.STARLIGHT_MOSS_SPORE.get(), LogosMod.STARLIGHT_MOSS.get(), 2, 2)));
        for (var metal : LogosMod.JarMetal.values()) crops.add(new CropYield(metal.crop.get(), metal.seed.get(), metal.pod.get(), 1, 2));
        for (var herb : LogosMod.JarHerb.values()) crops.add(new CropYield(herb.crop.get(), herb.seed.get(), herb.harvest.get(), 2, 2));
        return crops;
    }

    private static void assertYield(GameTestHelper h, CropYield crop, List<ItemStack> drops, int seeds, int materials, String context) {
        h.assertTrue(drops.stream().filter(s -> s.is(crop.seed)).mapToInt(ItemStack::getCount).sum() == seeds,
                crop.crop + " " + context + " should yield " + seeds + " seeds");
        h.assertTrue(drops.stream().filter(s -> s.is(crop.material)).mapToInt(ItemStack::getCount).sum() == materials,
                crop.crop + " " + context + " should yield " + materials + " materials");
        h.assertTrue(drops.stream().allMatch(s -> s.isEmpty() || s.is(crop.seed) || s.is(crop.material)),
                crop.crop + " " + context + " yielded unexpected items");
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

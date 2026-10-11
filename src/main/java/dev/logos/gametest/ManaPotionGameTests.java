package dev.logos.gametest;

import java.math.BigInteger;

import com.mojang.serialization.JsonOps;

import dev.logos.LogosMod;
import dev.logos.item.ManaPotionItem;
import dev.logos.runtime.PlayerMagic;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/** Actual stand cycles, drinking and persistent/network round trips for mana potions. */
@SuppressWarnings("removal")
final class ManaPotionGameTests {
    private ManaPotionGameTests() {}

    private static void brewRound(GameTestHelper h, BlockPos pos, BrewingStandBlockEntity stand) {
        // Tick the real stand synchronously so full 400-tick rounds fit the shared test timeout.
        BlockPos worldPos = h.absolutePos(pos);
        for (int tick = 0; tick <= 400; tick++) {
            BrewingStandBlockEntity.serverTick(h.getLevel(), worldPos, h.getLevel().getBlockState(worldPos), stand);
        }
    }

    static void brewing(GameTestHelper h) {
        BlockPos pos = new BlockPos(4, 1, 4);
        h.setBlock(pos, Blocks.BREWING_STAND);
        BrewingStandBlockEntity stand = (BrewingStandBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(pos));
        for (int slot = 0; slot < 3; slot++) stand.setItem(slot, PotionContents.createItemStack(Items.POTION, Potions.WATER));
        ItemStack shards = new ItemStack(LogosMod.TAINTED_MANA_SHARD.get(), 64);
        var brewing = h.getLevel().potionBrewing();
        h.assertTrue(!brewing.hasMix(stand.getItem(0), shards), "water must not become a mana potion directly");
        h.assertTrue(!brewing.hasMix(PotionContents.createItemStack(Items.SPLASH_POTION, Potions.AWKWARD), shards), "splash potions are not mana potion inputs");
        h.assertTrue(!brewing.hasMix(PotionContents.createItemStack(Items.POTION, Potions.HEALING), shards), "other potions are not mana potion inputs");
        stand.setItem(3, new ItemStack(Items.NETHER_WART));
        stand.setItem(4, new ItemStack(Items.BLAZE_POWDER));
        brewRound(h, pos, stand);
        for (int slot = 0; slot < 3; slot++) {
            h.assertTrue(stand.getItem(slot).get(DataComponents.POTION_CONTENTS).is(Potions.AWKWARD), "the vanilla water/nether wart recipe still works");
        }

        stand.setItem(3, shards);
        BlockPos worldPos = h.absolutePos(pos);
        for (int tick = 0; tick < 400; tick++) {
            BrewingStandBlockEntity.serverTick(h.getLevel(), worldPos, h.getLevel().getBlockState(worldPos), stand);
        }
        h.assertTrue(stand.getItem(0).is(Items.POTION) && stand.getItem(3).getCount() == 64, "an incomplete brewing round changes neither bottle nor ingredient");
        BrewingStandBlockEntity.serverTick(h.getLevel(), worldPos, h.getLevel().getBlockState(worldPos), stand);
        for (int slot = 0; slot < 3; slot++) {
            h.assertTrue(stand.getItem(slot).is(LogosMod.MANA_POTION.get()) && ManaPotionItem.mana(stand.getItem(slot)).equals(BigInteger.valueOf(40)),
                    "a stack of 64 shards gives each bottle only 40 mana after the first round");
        }
        h.assertTrue(stand.getItem(3).getCount() == 63, "one round consumes one shard for all three bottles");
        ItemStack firstPotion = stand.getItem(0);
        stand.setItem(0, ItemStack.EMPTY);
        h.assertTrue(stand.canPlaceItem(0, firstPotion), "hoppers can insert a mana potion into an empty bottle slot for another round");
        stand.setItem(0, firstPotion);
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        BrewingStandMenu menu = new BrewingStandMenu(0, player.getInventory(), stand, new SimpleContainerData(2));
        h.assertTrue(menu.getSlot(0).mayPlace(stand.getItem(0)), "the brewing stand screen accepts mana potions in its bottle slots");
        h.assertTrue(menu.getSlot(3).mayPlace(new ItemStack(LogosMod.TAINTED_MANA_SHARD.get())), "the brewing stand screen accepts the existing shard as its ingredient");

        brewRound(h, pos, stand);
        h.assertTrue(ManaPotionItem.mana(stand.getItem(0)).equals(BigInteger.valueOf(80)) && stand.getItem(3).getCount() == 62,
                "the second round adds exactly 40 and consumes exactly one more shard");
        BigInteger large = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        stand.getItem(1).set(LogosMod.MANA_POTION_SHARDS.get(), large);
        stand.getItem(1).set(DataComponents.CUSTOM_NAME, Component.literal("Kept name"));
        stand.setItem(2, PotionContents.createItemStack(Items.POTION, Potions.WATER));
        brewRound(h, pos, stand);
        h.assertTrue(ManaPotionItem.mana(stand.getItem(0)).equals(BigInteger.valueOf(120)), "the third round restores 120 mana");
        h.assertTrue(ManaPotionItem.shards(stand.getItem(1)).equals(large.add(BigInteger.ONE)), "shard counts beyond long range keep increasing");
        h.assertTrue(stand.getItem(1).getHoverName().getString().equals("Kept name"), "upgrading preserves custom components");
        h.assertTrue(stand.getItem(2).is(Items.POTION) && stand.getItem(2).get(DataComponents.POTION_CONTENTS).is(Potions.WATER), "an ineligible bottle stays unchanged");
        h.assertTrue(stand.getItem(3).getCount() == 61, "mixed bottle strengths still consume just one shard per round");
        h.assertTrue(!brewing.hasMix(stand.getItem(0), new ItemStack(Items.REDSTONE)), "unrelated ingredients cannot strengthen mana potions");
        h.succeed();
    }

    static void drinking(GameTestHelper h) {
        ServerPlayer player = h.makeMockServerPlayerInLevel();
        player.getAbilities().instabuild = false;
        PlayerMagic magic = PlayerMagic.of(player);
        magic.maxMana = 200;
        magic.mana = 10;
        ItemStack first = new ItemStack(LogosMod.MANA_POTION.get());
        h.assertTrue(first.getUseDuration(player) == 32, "mana potions use the standard drink duration");
        h.assertTrue(first.finishUsingItem(h.getLevel(), player).is(Items.GLASS_BOTTLE), "survival drinking returns a glass bottle");
        h.assertTrue(first.isEmpty() && magic.mana == 50, "one shard restores exactly 40 and consumes the potion");
        ItemStack stronger = new ItemStack(LogosMod.MANA_POTION.get());
        stronger.set(LogosMod.MANA_POTION_SHARDS.get(), BigInteger.valueOf(3));
        stronger.finishUsingItem(h.getLevel(), player);
        h.assertTrue(magic.mana == 170, "three shards restore exactly 120 mana");
        new ItemStack(LogosMod.MANA_POTION.get()).finishUsingItem(h.getLevel(), player);
        h.assertTrue(magic.mana == 200 && magic.maxMana == 200, "drinking respects the existing mana capacity");
        player.getAbilities().instabuild = true;
        magic.mana = 0;
        ItemStack creative = new ItemStack(LogosMod.MANA_POTION.get());
        ItemStack result = creative.finishUsingItem(h.getLevel(), player);
        h.assertTrue(result.is(LogosMod.MANA_POTION.get()) && result.getCount() == 1 && magic.mana == 40,
                "creative drinking restores mana without consuming the potion");
        h.succeed();
    }

    static void persistence(GameTestHelper h) {
        BigInteger large = BigInteger.TEN.pow(40).add(BigInteger.valueOf(7));
        ItemStack potion = new ItemStack(LogosMod.MANA_POTION.get());
        potion.set(LogosMod.MANA_POTION_SHARDS.get(), large);
        var ops = h.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        var saved = ItemStack.CODEC.encodeStart(ops, potion).getOrThrow();
        ItemStack loaded = ItemStack.CODEC.parse(ops, saved).getOrThrow();
        h.assertTrue(ManaPotionItem.shards(loaded).equals(large) && ManaPotionItem.mana(loaded).equals(large.multiply(BigInteger.valueOf(40))),
                "saving and loading preserve the exact count and restore amount beyond long range");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), h.getLevel().registryAccess());
        try {
            ItemStack.STREAM_CODEC.encode(buffer, potion);
            ItemStack received = ItemStack.STREAM_CODEC.decode(buffer);
            h.assertTrue(ManaPotionItem.shards(received).equals(large), "network synchronization preserves the exact count");
        } finally {
            buffer.release();
        }
        for (String invalid : new String[] { "0", "-1", "not-a-number" }) {
            h.assertTrue(ManaPotionItem.SHARD_COUNT_CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(invalid)).error().isPresent(),
                    "invalid shard counts are rejected: " + invalid);
        }
        h.succeed();
    }
}

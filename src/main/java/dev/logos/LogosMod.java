package dev.logos;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import dev.logos.command.LogosCommands;
import dev.logos.item.PageTier;
import dev.logos.item.WandItem;
import net.minecraft.world.item.Rarity;
import dev.logos.lang.LogosError;
import dev.logos.lang.Node;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SpellRuntime;
import dev.logos.runtime.StatusData;
import dev.logos.runtime.Targeting;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Logos: a prototype of spells as a typed, event-driven, mana-metered language. */
@Mod(LogosMod.MODID)
public class LogosMod {
    public static final String MODID = "logos";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(net.minecraft.core.registries.Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> SPELL_SOURCE = COMPONENTS.registerComponentType("spell",
            b -> b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));
    /** The node graph the inscribed spell was drawn with, so the editor can reopen it. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CompoundTag>> SPELL_GRAPH = COMPONENTS.registerComponentType("spell_graph",
            b -> b.persistent(CompoundTag.CODEC).networkSynchronized(ByteBufCodecs.COMPOUND_TAG));

    /**
     * The mana a mana storage tank held when it was broken; placed again, it holds it still. Also the mana a
     * staff holds, which changes every tick it fills: the staff in hand is not lowered and raised again for it.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Double>> STORED_MANA = COMPONENTS.registerComponentType("stored_mana",
            b -> b.persistent(Codec.DOUBLE).ignoreSwapAnimation().networkSynchronized(ByteBufCodecs.DOUBLE));

    /** Which spell (index into wand pages + off-hand codex pages) the wand will cast. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> SELECTED = COMPONENTS.registerComponentType("selected",
            b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));
    /** What a pair of dividers has measured. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.logos.item.DividersItem.Measure>> DIVIDERS = COMPONENTS.registerComponentType("dividers",
            b -> b.persistent(dev.logos.item.DividersItem.Measure.CODEC).networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(dev.logos.item.DividersItem.Measure.CODEC)));

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister<net.minecraft.world.item.CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<net.minecraft.world.entity.EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, MODID);

    /** The world presence of a {@code construct} form; never saved, never summoned by hand. */
    public static final net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.entity.EntityType<?>,
            net.minecraft.world.entity.EntityType<dev.logos.runtime.ConstructEntity>> CONSTRUCT = ENTITY_TYPES.register("construct",
                    key -> net.minecraft.world.entity.EntityType.Builder.<dev.logos.runtime.ConstructEntity>of(dev.logos.runtime.ConstructEntity::new,
                                    net.minecraft.world.entity.MobCategory.MISC)
                            .sized(1, 1).noSave().noSummon().fireImmune().clientTrackingRange(10).updateInterval(1)
                            .build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, key)));

    /**
     * Wand tiers differ in how many spell pages they hold and how fast they charge (channel flow, then surge).
     * These three are stand-in art for now, placed at tiers 2, 4 and 6.
     */
    public static final DeferredItem<WandItem> WAND = ITEMS.registerItem("wand", p -> new WandItem(p, 3, 55, 60), p -> p.stacksTo(1));
    public static final DeferredItem<WandItem> ADEPT_WAND = ITEMS.registerItem("adept_wand", p -> new WandItem(p, 6, 90, 184), p -> p.stacksTo(1).rarity(net.minecraft.world.item.Rarity.RARE));
    public static final DeferredItem<WandItem> MASTER_WAND = ITEMS.registerItem("master_wand",
            p -> new WandItem(p, 9, 135, 445), p -> p.stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC));
    /** The woods the first-tier wands and staffs are made of. */
    private static final List<String> WOODS = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry");
    /** The first-tier wands, one per wood (log, planks and a gold nugget); each holds three pages like the novice wand. */
    public static final List<DeferredItem<WandItem>> WOOD_WANDS = WOODS.stream()
            .map(wood -> ITEMS.registerItem(wood + "_wand", p -> new WandItem(p, 3, 40, 20), p -> p.stacksTo(1))).toList();
    /**
     * The first-tier staffs, one per wood: hold up to 150 mana of their own, three tier-1 pages' worth. They flow
     * slower than the wood wands, so one long fill of 150 takes about as long as three wand charges of 50 (3 s).
     */
    public static final List<DeferredItem<dev.logos.item.StaffItem>> WOOD_STAFFS = WOODS.stream()
            .map(wood -> ITEMS.registerItem(wood + "_staff", p -> new dev.logos.item.StaffItem(p, 3, 28, 14, 150), p -> p.stacksTo(1))).toList();
    /** Every grade of 术式载页: an empty and a written item each. */
    public static final Map<PageTier, PageTier.Items> PAGES = new EnumMap<>(PageTier.class);

    static {
        for (PageTier t : PageTier.values()) {
            Rarity rarity = t.ordinal() >= PageTier.SAGE.ordinal() ? Rarity.EPIC : t.ordinal() >= PageTier.CHALK.ordinal() ? Rarity.RARE
                    : t.ordinal() >= PageTier.CRYSTAL.ordinal() ? Rarity.UNCOMMON : Rarity.COMMON;
            PAGES.put(t, new PageTier.Items(
                    ITEMS.registerItem(t.blankId(), p -> new dev.logos.item.SpellPageItem(p, t, false), p -> p.rarity(rarity)),
                    ITEMS.registerItem(t.inscribedId(), p -> new dev.logos.item.SpellPageItem(p, t, true), p -> p.stacksTo(1).rarity(rarity))));
        }
    }

    public static final DeferredItem<dev.logos.item.SpellPageItem> SPELL_PAGE = PAGES.get(PageTier.BASIC).blank();
    public static final DeferredItem<dev.logos.item.SpellPageItem> INSCRIBED_PAGE = PAGES.get(PageTier.BASIC).inscribed();
    /** 术式余烬: what a page burnt by Formula Immolation leaves. It keeps the spell for copying but cannot be cast. */
    public static final DeferredItem<dev.logos.item.SpellEmberItem> SPELL_EMBER = ITEMS.registerItem("spell_ember",
            dev.logos.item.SpellEmberItem::new, p -> p.stacksTo(1));
    public static final DeferredItem<dev.logos.item.CodexItem> SPELL_CODEX = ITEMS.registerItem("spell_codex",
            dev.logos.item.CodexItem::new, p -> p.stacksTo(1));
    /** 锚钉: spent to set an anchor, by hand or by the anchor rune. Crafted later through alchemy. */
    public static final DeferredItem<dev.logos.item.AnchorNailItem> ANCHOR_NAIL = ITEMS.registerItem("anchor_nail",
            dev.logos.item.AnchorNailItem::new, p -> p.stacksTo(64));
    /** 两脚规: measures one block from another as forward, up and right, for the offset rune. */
    public static final DeferredItem<dev.logos.item.DividersItem> DIVIDERS_ITEM = ITEMS.registerItem("dividers",
            dev.logos.item.DividersItem::new, p -> p.stacksTo(1));

    // Alchemical colossus materials. Placeholders for now: their alchemy (recipes, planting) comes later.
    /** 巨像之心: the flower a colossus offers its master. An alchemical material. */
    public static final DeferredItem<net.minecraft.world.item.Item> COLOSSUS_HEART = ITEMS.registerSimpleItem("colossus_heart",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    /** 失序结晶: left behind when a colossus's mana frenzy burns out. */
    public static final DeferredItem<net.minecraft.world.item.Item> DISORDER_CRYSTAL = ITEMS.registerSimpleItem("disorder_crystal",
            p -> p.rarity(net.minecraft.world.item.Rarity.RARE));
    /** 维护套件: heals a colossus and mends its scorched shell; caput mortuum as filler, adhesive as binder, a copper patch. */
    public static final DeferredItem<net.minecraft.world.item.Item> MAINTENANCE_KIT = ITEMS.registerSimpleItem("maintenance_kit", p -> p.stacksTo(16));
    /** 被污染的魔力碎片: an unstable fuel; fed to a colossus, it sends it into a mana frenzy. */
    public static final DeferredItem<net.minecraft.world.item.Item> TAINTED_MANA_SHARD = ITEMS.registerSimpleItem("tainted_mana_shard",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    /** 魔力碎片: a tainted shard purified with philosophers' mercury; the same mana, burnt safely. */
    public static final DeferredItem<net.minecraft.world.item.Item> MANA_SHARD = ITEMS.registerSimpleItem("mana_shard",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    /** 魔力结晶: a mana shard grown on in a storage tank; 1000 mana, a full alchemy table, spreader or emitter. */
    public static final DeferredItem<net.minecraft.world.item.Item> MANA_CRYSTAL = ITEMS.registerSimpleItem("mana_crystal",
            p -> p.rarity(net.minecraft.world.item.Rarity.RARE));
    /** 魔力晶石: a mana crystal grown on in a storage tank; 5000 mana, a full tank, which only a tank can take back. */
    public static final DeferredItem<net.minecraft.world.item.Item> MANA_GEMSTONE = ITEMS.registerSimpleItem("mana_gemstone",
            p -> p.rarity(net.minecraft.world.item.Rarity.EPIC));

    // Alchemy materials: the fermentation line. The alchemy table's processes make most of them.
    /**
     * A bottled alchemy liquid one can drink like a potion (same animation and sound): it stacks to 16
     * like a honey bottle, applies {@code effects} and leaves the glass bottle.
     */
    private static net.minecraft.world.item.Item.Properties drinkable(net.minecraft.world.item.Item.Properties p,
            net.minecraft.world.effect.MobEffectInstance... effects) {
        return p.stacksTo(16).usingConvertsTo(net.minecraft.world.item.Items.GLASS_BOTTLE).component(net.minecraft.core.component.DataComponents.CONSUMABLE,
                net.minecraft.world.item.component.Consumables.defaultDrink()
                        .onConsume(new net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect(List.of(effects))).build());
    }

    /** 酵种: ferments; turns water into raw mash. */
    public static final DeferredItem<net.minecraft.world.item.Item> STARTER_CULTURE = ITEMS.registerSimpleItem("starter_culture");
    /** 初醪: the base of Logos potions, as the awkward potion is of vanilla ones. Its own item so it keeps its look; the brewing stand takes it. */
    public static final DeferredItem<net.minecraft.world.item.Item> RAW_MASH = ITEMS.registerSimpleItem("raw_mash", p -> drinkable(p,
            // Sour, a little like ale: a brief surge of strength, and a queasy stomach.
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.STRENGTH, 30 * 20),
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NAUSEA, 10 * 20)));
    /** 臭卤: fermented meat, distilled into sulfur and niter mother liquor. */
    public static final DeferredItem<net.minecraft.world.item.Item> FOUL_BRINE = ITEMS.registerSimpleItem("foul_brine", p -> drinkable(p,
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 30 * 20, 2),
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NAUSEA, 30 * 20),
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 90 * 20, 1)));
    /** 硝母: what is left of foul brine once distilled; it settles into saltpeter. */
    public static final DeferredItem<net.minecraft.world.item.Item> NITER_MOTHER_LIQUOR = ITEMS.registerSimpleItem("niter_mother_liquor", p -> drinkable(p,
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 20 * 20)));
    public static final DeferredItem<net.minecraft.world.item.Item> SULFUR_POWDER = ITEMS.registerSimpleItem("sulfur_powder");
    public static final DeferredItem<net.minecraft.world.item.Item> SALTPETER = ITEMS.registerSimpleItem("saltpeter");
    /** 粘合剂: dissolved clay and raw mash. */
    public static final DeferredItem<net.minecraft.world.item.Item> ADHESIVE = ITEMS.registerSimpleItem("adhesive");
    /** 回响酊: sculk or an echo shard dissolved in raw mash; with adhesive it makes and raises echo pages, and gives its bottle back. */
    public static final DeferredItem<net.minecraft.world.item.Item> ECHO_TINCTURE = ITEMS.registerSimpleItem("echo_tincture",
            p -> p.stacksTo(16).craftRemainder(net.minecraft.world.item.Items.GLASS_BOTTLE));

    // The three principles: sulfur, salt (saltpeter) and mercury, joined into philosophers' mercury and settled into rebis.
    /** 水银瓶: distilled out of redstone, which gives sulfur besides. Raw mercury: poisonous to drink. */
    public static final DeferredItem<net.minecraft.world.item.Item> MERCURY_BOTTLE = ITEMS.registerSimpleItem("mercury_bottle", p -> drinkable(p,
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.POISON, 30 * 20, 1),
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NAUSEA, 20 * 20)));
    /**
     * 哲人汞: mercury dissolved with sulfur and saltpeter, the three principles joined. It dissolves up
     * to three potion powders into one potion of all their effects, purifies tainted mana shards, and settles into rebis.
     */
    public static final DeferredItem<net.minecraft.world.item.Item> PHILOSOPHERS_MERCURY = ITEMS.registerSimpleItem("philosophers_mercury",
            p -> p.stacksTo(16).rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    /** 雷比斯: the "double thing" philosophers' mercury settles into; the whitening washes soul humus into chalk with it. */
    public static final DeferredItem<net.minecraft.world.item.Item> REBIS = ITEMS.registerSimpleItem("rebis",
            p -> p.rarity(net.minecraft.world.item.Rarity.RARE));
    /**
     * 鸦首 (caput corvi): caput mortuum rotted with philosophers' mercury, the blackening. With adhesive it
     * makes and raises transmuted pages, and gives its bottle back.
     */
    public static final DeferredItem<net.minecraft.world.item.Item> RAVEN_HEAD = ITEMS.registerSimpleItem("raven_head",
            p -> p.stacksTo(16).rarity(net.minecraft.world.item.Rarity.UNCOMMON).craftRemainder(net.minecraft.world.item.Items.GLASS_BOTTLE));
    /**
     * 生机醪: mandrake roots fermented with raven head in raw mash, a mash teeming with the force of life.
     * Poured in a cultivation jar it makes things grow several stages a random tick; distilled, it gives the tincture of life.
     */
    public static final DeferredItem<net.minecraft.world.item.Item> VITAL_MASH = ITEMS.registerSimpleItem("vital_mash", p -> drinkable(p,
            // Raw mash's kick, much stronger: the body mends and swells with strength, and the head swims.
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.REGENERATION, 20 * 20, 1),
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.STRENGTH, 90 * 20, 1),
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NAUSEA, 15 * 20)).rarity(net.minecraft.world.item.Rarity.RARE));
    /** 生命精酊: the force of life distilled out of vital mash, leaving dead ash behind; it gives its bottle back when used. */
    public static final DeferredItem<net.minecraft.world.item.Item> TINCTURE_OF_LIFE = ITEMS.registerSimpleItem("tincture_of_life",
            p -> p.stacksTo(16).rarity(net.minecraft.world.item.Rarity.EPIC).craftRemainder(net.minecraft.world.item.Items.GLASS_BOTTLE));

    // Albedo, the whitening: blackstone burnt to dead ash, rotted with soul soil, washed white with philosophers' mercury.
    /** 死灰 (caput mortuum): what calcining blackstone leaves, the dead residue the whitening starts from. */
    public static final DeferredItem<net.minecraft.world.item.Item> CAPUT_MORTUUM = ITEMS.registerSimpleItem("caput_mortuum");
    /** 死灰块: nine caput mortuum; it insulates its chunk against spreading mana (dev.logos.runtime.ManaField). */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.CaputMortuumBlock> CAPUT_MORTUUM_BLOCK = BLOCKS.registerBlock(
            "caput_mortuum_block", dev.logos.block.CaputMortuumBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.TERRACOTTA_PURPLE).strength(0.6f)
                    .sound(net.minecraft.world.level.block.SoundType.SAND));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> CAPUT_MORTUUM_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("caput_mortuum_block", CAPUT_MORTUUM_BLOCK);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.CaputMortuumBlock.Marker>> CAPUT_MORTUUM_BLOCK_BE =
            BLOCK_ENTITIES.register("caput_mortuum_block", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.CaputMortuumBlock.Marker::new, CAPUT_MORTUUM_BLOCK.get()));
    /** 腐殖灵土: dead ash rotted with soul soil. A soil of its own; herbs may be grown in it later. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<net.minecraft.world.level.block.Block> SOUL_HUMUS = BLOCKS.registerSimpleBlock("soul_humus",
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_BROWN).strength(0.5f)
                    .sound(net.minecraft.world.level.block.SoundType.SOUL_SOIL));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> SOUL_HUMUS_ITEM = ITEMS.registerSimpleBlockItem("soul_humus", SOUL_HUMUS);
    /** 培养罐: soul humus under glass; a plant's seed (a crop, a berry bush…) grows in it a stage at every random tick. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.CultivationJarBlock> CULTIVATION_JAR = BLOCKS.registerBlock(
            "cultivation_jar", dev.logos.block.CultivationJarBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.NONE).strength(0.3f).noOcclusion().randomTicks()
                    .sound(net.minecraft.world.level.block.SoundType.GLASS));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> CULTIVATION_JAR_ITEM = ITEMS.registerSimpleBlockItem("cultivation_jar", CULTIVATION_JAR);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.CultivationJarBlockEntity>> CULTIVATION_JAR_BE =
            BLOCK_ENTITIES.register("cultivation_jar", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.CultivationJarBlockEntity::new, CULTIVATION_JAR.get()));

    // What grows in a cultivation jar: seeds fermented on the alchemy table, and the plants the jar draws.
    /** A plant grown in a cultivation jar by default; add-ons can opt into ground substrates through its block tag. */
    private static net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.JarCropBlock> jarCrop(String name) {
        return BLOCKS.registerBlock(name, dev.logos.block.JarCropBlock::new,
                p -> p.mapColor(net.minecraft.world.level.material.MapColor.PLANT).noCollision().instabreak()
                        .sound(net.minecraft.world.level.block.SoundType.CROP));
    }

    /** 硝之芽: saltpeter fermented into a sprout; in a jar it gives saltpeter and more sprouts. */
    public static final DeferredItem<net.minecraft.world.item.Item> NITER_SPROUT = ITEMS.registerSimpleItem("niter_sprout");
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.JarCropBlock> NITER_SPROUT_CROP = jarCrop("niter_sprout_crop");
    /** 萌生精质: the quickening of seeds and a sapling, fermented in raw mash; what makes a seed of what is not one. */
    public static final DeferredItem<net.minecraft.world.item.Item> GERMINAL_ESSENCE = ITEMS.registerSimpleItem("germinal_essence",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));

    /** The metals that grow in a jar: each has an embryo (settled from its ingot), a seed, a plant and the pod it bears. */
    public enum JarMetal {
        COPPER(net.minecraft.world.item.Rarity.COMMON), IRON(net.minecraft.world.item.Rarity.COMMON),
        GOLD(net.minecraft.world.item.Rarity.UNCOMMON), NETHERITE(net.minecraft.world.item.Rarity.RARE);

        public final String id;
        /** 铜之胚…: the metal settled into a germ that will take root. */
        public final DeferredItem<net.minecraft.world.item.Item> embryo;
        /** 铜之种…: the germ quickened; planted in a jar it bears pods slowly, on mana, harvest after harvest. */
        public final DeferredItem<net.minecraft.world.item.Item> seed;
        /** 铜荚…: the living metal a plant bears; calcined, it becomes the ingot. */
        public final DeferredItem<net.minecraft.world.item.Item> pod;
        public final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.JarCropBlock> crop;

        JarMetal(net.minecraft.world.item.Rarity rarity) {
            id = name().toLowerCase(java.util.Locale.ROOT);
            embryo = ITEMS.registerSimpleItem(id + "_embryo", p -> p.rarity(rarity));
            seed = ITEMS.registerSimpleItem(id + "_seed", p -> p.rarity(rarity));
            pod = ITEMS.registerSimpleItem(id + "_pod", p -> p.rarity(rarity));
            crop = jarCrop(id + "_pod_crop");
        }
    }

    static {
        // Registers the metals' items and plants with the rest.
        JarMetal.values();
    }

    /**
     * The herbs of the element potions (dev.logos.alchemy.BuffPotions): seeds fermented with germinal
     * essence, grown in a jar and harvested again and again for what brews their potions.
     */
    public enum JarHerb {
        /** 赤脉根: beetroot quickened on bone meal, red-veined; the root is itself the harvest. */
        REDVEIN("redvein_root", null, net.minecraft.world.item.Rarity.COMMON),
        /** 烛焰花: a poppy that drank blaze powder; its stamens burn like wicks. */
        CANDLEFLAME("candleflame", "candleflame_stamen", net.minecraft.world.item.Rarity.COMMON),
        /** 霜铃花: lily of the valley grown in powder snow; its petals ring with cold. */
        FROSTBELL("frostbell", "frostbell_petal", net.minecraft.world.item.Rarity.COMMON),
        /** 鸣雷蓟: a dead bush on copper and echo; its down crackles. */
        THUNDER_THISTLE("thunder_thistle", "thunder_thistle_down", net.minecraft.world.item.Rarity.UNCOMMON),
        /** 隙影花: chorus fruit on echo and dragon's breath, a flower half in the void; its pollen is shadow. */
        RIFTSHADE("riftshade", "riftshade_pollen", net.minecraft.world.item.Rarity.UNCOMMON);

        /** The seed fermented on the alchemy table and planted in the jar. */
        public final DeferredItem<net.minecraft.world.item.Item> seed;
        /** The herb itself, separate from its seed and any harvested brewing ingredient. */
        public final DeferredItem<net.minecraft.world.item.Item> plant;
        /** What it gives, again and again: the brewing ingredient of its potion (the plant itself for redvein root). */
        public final DeferredItem<net.minecraft.world.item.Item> harvest;
        public final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.JarCropBlock> crop;

        JarHerb(String plant, @org.jspecify.annotations.Nullable String harvest, net.minecraft.world.item.Rarity rarity) {
            this.seed = ITEMS.registerSimpleItem(plant + "_seed", p -> p.rarity(rarity));
            this.plant = ITEMS.registerSimpleItem(plant, p -> p.rarity(rarity));
            this.harvest = harvest == null ? this.plant : ITEMS.registerSimpleItem(harvest, p -> p.rarity(rarity));
            crop = jarCrop(plant + "_crop");
        }
    }

    static {
        JarHerb.values();
    }

    /** 曼德拉草: a carrot quickened and blackened into a root that grows only under glass. */
    public static final DeferredItem<net.minecraft.world.item.Item> MANDRAKE_SEED = ITEMS.registerSimpleItem("mandrake_seed",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    public static final DeferredItem<net.minecraft.world.item.Item> MANDRAKE_ROOT = ITEMS.registerSimpleItem("mandrake_root",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.JarCropBlock> MANDRAKE_CROP = jarCrop("mandrake_crop");
    /** 汞菇: a nether fungus that drinks mercury; distilled, it gives the mercury back. */
    public static final DeferredItem<net.minecraft.world.item.Item> MERCURY_SPORE = ITEMS.registerSimpleItem("mercury_spore");
    public static final DeferredItem<net.minecraft.world.item.Item> MERCURY_MUSHROOM = ITEMS.registerSimpleItem("mercury_mushroom");
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.JarCropBlock> MERCURY_MUSHROOM_CROP = jarCrop("mercury_mushroom_crop");
    /** 星辉苔: glow lichen grown on amethyst and echo, glittering like the night sky. */
    public static final DeferredItem<net.minecraft.world.item.Item> STARLIGHT_MOSS_SPORE = ITEMS.registerSimpleItem("starlight_moss_spore",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    public static final DeferredItem<net.minecraft.world.item.Item> STARLIGHT_MOSS = ITEMS.registerSimpleItem("starlight_moss",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.JarCropBlock> STARLIGHT_MOSS_CROP = jarCrop("starlight_moss_crop");
    /** 白垩: soul humus washed white with rebis and philosophers' mercury; it raises a page to the chalk grade. */
    public static final DeferredItem<net.minecraft.world.item.Item> CHALK = ITEMS.registerSimpleItem("chalk",
            p -> p.rarity(net.minecraft.world.item.Rarity.RARE));

    // Citrinitas, the yellowing: gold in philosophers' mercury sublimed into solar flakes, the white stone
    // fired yellow, dissolved under the sky into the firmament's liquor, and settled into its stone.
    /** 日金汞齐: gold dissolved in philosophers' mercury, a silvery paste in its bottle; sublimed, the mercury rises off and leaves the gold. */
    public static final DeferredItem<net.minecraft.world.item.Item> SOLAR_AMALGAM = ITEMS.registerSimpleItem("solar_amalgam",
            p -> p.stacksTo(16).rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    /** 日华: the golden flakes sublimation leaves of solar amalgam, as fire-gilding leaves gold. */
    public static final DeferredItem<net.minecraft.world.item.Item> SOLAR_BLOSSOM = ITEMS.registerSimpleItem("solar_blossom",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    /** 炽白之石: rebis and chalk calcined together, the white stone glowing toward yellow; fired again in the reddening. */
    public static final DeferredItem<net.minecraft.world.item.Item> INCANDESCENT_WHITE_STONE = ITEMS.registerSimpleItem("incandescent_white_stone",
            p -> p.rarity(net.minecraft.world.item.Rarity.RARE));
    /** 天穹灵液: solar flakes and the white stone dissolved with starlight. Drunk, one rises softly and sees by night; settled, it becomes the firmament stone. */
    public static final DeferredItem<net.minecraft.world.item.Item> FIRMAMENT_LIQUID = ITEMS.registerSimpleItem("firmament_liquid", p -> drinkable(p,
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION, 180 * 20),
            new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 60 * 20)).rarity(net.minecraft.world.item.Rarity.RARE));
    /** 天穹之石: the firmament liquor settled; it raises a page to the firmament grade. */
    public static final DeferredItem<net.minecraft.world.item.Item> FIRMAMENT_STONE = ITEMS.registerSimpleItem("firmament_stone",
            p -> p.rarity(net.minecraft.world.item.Rarity.RARE));

    // Rubedo, the reddening: life, matter and mana, each through two of the seven processes, settled together
    // into the philosophers' stone. Life is the tincture of life (fermented, distilled).
    /** 赤化之石: the incandescent white stone fired again with living gold and sulfur, turning red. */
    public static final DeferredItem<net.minecraft.world.item.Item> REDDENING_STONE = ITEMS.registerSimpleItem("reddening_stone",
            p -> p.rarity(net.minecraft.world.item.Rarity.RARE));
    /** 红狮: the reddening stone washed pure with philosophers' mercury, the sages' red sulfur; matter, made ready for the stone. */
    public static final DeferredItem<net.minecraft.world.item.Item> RED_LION = ITEMS.registerSimpleItem("red_lion",
            p -> p.rarity(net.minecraft.world.item.Rarity.EPIC));
    /** 以太: what lies above the firmament, firmament liquid and starlight moss dissolved on a great deal of the table's own mana. */
    public static final DeferredItem<net.minecraft.world.item.Item> AETHER = ITEMS.registerSimpleItem("aether",
            p -> p.stacksTo(16).rarity(net.minecraft.world.item.Rarity.RARE));
    /** 第五精质: aether sublimed, the fifth element beyond the four; mana, made ready for the stone. */
    public static final DeferredItem<net.minecraft.world.item.Item> QUINTESSENCE = ITEMS.registerSimpleItem("quintessence",
            p -> p.rarity(net.minecraft.world.item.Rarity.EPIC));
    /** 贤者之石: the tincture of life, the red lion and the quintessence settled together; it raises a page to the sage grade. */
    public static final DeferredItem<net.minecraft.world.item.Item> PHILOSOPHERS_STONE = ITEMS.registerSimpleItem("philosophers_stone",
            p -> p.rarity(net.minecraft.world.item.Rarity.EPIC));
    /** 药粉: a potion settled into powder; eaten for the potion's effects, and dissolved back into it. */
    public static final DeferredItem<dev.logos.item.PotionPowderItem> POTION_POWDER = ITEMS.registerItem("potion_powder", dev.logos.item.PotionPowderItem::new,
            p -> p.component(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                    .component(net.minecraft.core.component.DataComponents.CONSUMABLE, net.minecraft.world.item.component.Consumable.builder().consumeSeconds(1.6f)
                            .animation(net.minecraft.world.item.ItemUseAnimation.EAT).sound(net.minecraft.sounds.SoundEvents.GENERIC_EAT).build()));

    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.InscriptionTableBlock> INSCRIPTION_TABLE = BLOCKS.registerBlock(
            "inscription_table", dev.logos.block.InscriptionTableBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.WOOD).strength(2.5f).noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.WOOD));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> INSCRIPTION_TABLE_ITEM = ITEMS.registerSimpleBlockItem("inscription_table", INSCRIPTION_TABLE);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.InscriptionTableBlockEntity>> INSCRIPTION_TABLE_BE =
            BLOCK_ENTITIES.register("inscription_table", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.InscriptionTableBlockEntity::new, INSCRIPTION_TABLE.get()));

    /** 术式拓印台: copies the spell of a page (or ember) onto an empty page. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.CopyTableBlock> COPY_TABLE = BLOCKS.registerBlock(
            "copy_table", dev.logos.block.CopyTableBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.WOOD).strength(2.5f).noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.WOOD));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> COPY_TABLE_ITEM = ITEMS.registerSimpleBlockItem("copy_table", COPY_TABLE);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.CopyTableBlockEntity>> COPY_TABLE_BE =
            BLOCK_ENTITIES.register("copy_table", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.CopyTableBlockEntity::new, COPY_TABLE.get()));

    /** 炼金术桌: a two-block table holding materials, products, mana and a fluid tank for the alchemical processes. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.AlchemyTableBlock> ALCHEMY_TABLE = BLOCKS.registerBlock(
            "alchemy_table", dev.logos.block.AlchemyTableBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.WOOD).strength(2.5f).noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.WOOD).pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK));
    public static final DeferredItem<dev.logos.item.AlchemyTableItem> ALCHEMY_TABLE_ITEM = ITEMS.registerItem("alchemy_table",
            p -> new dev.logos.item.AlchemyTableItem(ALCHEMY_TABLE.get(), p), p -> p.useBlockDescriptionPrefix());
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.AlchemyTableBlockEntity>> ALCHEMY_TABLE_BE =
            BLOCK_ENTITIES.register("alchemy_table", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.AlchemyTableBlockEntity::new, ALCHEMY_TABLE.get()));

    // Mana in the air (dev.logos.runtime.ManaField): spreaders pour it out, collectors take it in and pass it on.
    /** 魔力散布器: burns mana fuel into the air of its chunk. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.ManaSpreaderBlock> MANA_SPREADER = BLOCKS.registerBlock(
            "mana_spreader", dev.logos.block.ManaSpreaderBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_BROWN).strength(2.5f).noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.WOOD));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> MANA_SPREADER_ITEM = ITEMS.registerSimpleBlockItem("mana_spreader", MANA_SPREADER);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.ManaSpreaderBlockEntity>> MANA_SPREADER_BE =
            BLOCK_ENTITIES.register("mana_spreader", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.ManaSpreaderBlockEntity::new, MANA_SPREADER.get()));
    /** 魔力收集器: draws mana from the air and passes it to the block it faces. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.ManaCollectorBlock> MANA_COLLECTOR = BLOCKS.registerBlock(
            "mana_collector", dev.logos.block.ManaCollectorBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_BROWN).strength(2.5f).noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.WOOD));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> MANA_COLLECTOR_ITEM = ITEMS.registerSimpleBlockItem("mana_collector", MANA_COLLECTOR);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.ManaCollectorBlockEntity>> MANA_COLLECTOR_BE =
            BLOCK_ENTITIES.register("mana_collector", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.ManaCollectorBlockEntity::new, MANA_COLLECTOR.get()));
    /** 魔力存储罐: holds 5000 mana; stores it, supplies its neighbours, or condenses it into solid mana. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.ManaStorageTankBlock> MANA_STORAGE_TANK = BLOCKS.registerBlock(
            "mana_storage_tank", dev.logos.block.ManaStorageTankBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_BROWN).strength(2.5f).noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.WOOD));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> MANA_STORAGE_TANK_ITEM = ITEMS.registerSimpleBlockItem("mana_storage_tank", MANA_STORAGE_TANK);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.ManaStorageTankBlockEntity>> MANA_STORAGE_TANK_BE =
            BLOCK_ENTITIES.register("mana_storage_tank", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.ManaStorageTankBlockEntity::new, MANA_STORAGE_TANK.get()));
    /** The fog and the growing crystal drawn in a tank: never placed, no item, no loot. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.ManaStorageTankBlock.Fog> MANA_FOG = BLOCKS.registerBlock(
            "mana_fog", dev.logos.block.ManaStorageTankBlock.Fog::new, p -> p.noCollision().noOcclusion().noLootTable());
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.ManaStorageTankBlock.Cluster> MANA_CLUSTER = BLOCKS.registerBlock(
            "mana_cluster", dev.logos.block.ManaStorageTankBlock.Cluster::new, p -> p.noCollision().noOcclusion().noLootTable());
    /** 术式发射器: charges the spell on the page it holds from its own mana, and casts it on a trigger. */
    public static final net.neoforged.neoforge.registries.DeferredBlock<dev.logos.block.SpellEmitterBlock> SPELL_EMITTER = BLOCKS.registerBlock(
            "spell_emitter", dev.logos.block.SpellEmitterBlock::new,
            p -> p.mapColor(net.minecraft.world.level.material.MapColor.COLOR_BROWN).strength(2.5f).noOcclusion()
                    .sound(net.minecraft.world.level.block.SoundType.WOOD)
                    .lightLevel(s -> s.getValue(dev.logos.block.ManaSpreaderBlock.ACTIVE) ? 6 : 0));
    public static final DeferredItem<net.minecraft.world.item.BlockItem> SPELL_EMITTER_ITEM = ITEMS.registerSimpleBlockItem("spell_emitter", SPELL_EMITTER);
    public static final Supplier<net.minecraft.world.level.block.entity.BlockEntityType<dev.logos.block.SpellEmitterBlockEntity>> SPELL_EMITTER_BE =
            BLOCK_ENTITIES.register("spell_emitter", () -> new net.minecraft.world.level.block.entity.BlockEntityType<>(
                    dev.logos.block.SpellEmitterBlockEntity::new, SPELL_EMITTER.get()));

    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeType<?>> RECIPE_TYPES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.RECIPE_TYPE, MODID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.RECIPE_SERIALIZER, MODID);
    public static final DeferredRegister<net.minecraft.world.item.crafting.RecipeBookCategory> RECIPE_BOOK_CATEGORIES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.RECIPE_BOOK_CATEGORY, MODID);
    /** What an alchemy table's process cards make (data/…/recipe, type logos:alchemy). */
    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeType<?>, net.minecraft.world.item.crafting.RecipeType<dev.logos.alchemy.AlchemyRecipe>> ALCHEMY_RECIPE =
            RECIPE_TYPES.register("alchemy", net.minecraft.world.item.crafting.RecipeType::simple);
    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>, net.minecraft.world.item.crafting.RecipeSerializer<dev.logos.alchemy.AlchemyRecipe>> ALCHEMY_SERIALIZER =
            RECIPE_SERIALIZERS.register("alchemy", () -> dev.logos.alchemy.AlchemyRecipe.SERIALIZER);
    /** What grows in a cultivation jar (data/…/recipe, type logos:cultivation). */
    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeType<?>, net.minecraft.world.item.crafting.RecipeType<dev.logos.alchemy.CultivationRecipe>> CULTIVATION_RECIPE =
            RECIPE_TYPES.register("cultivation", net.minecraft.world.item.crafting.RecipeType::simple);
    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>, net.minecraft.world.item.crafting.RecipeSerializer<dev.logos.alchemy.CultivationRecipe>> CULTIVATION_SERIALIZER =
            RECIPE_SERIALIZERS.register("cultivation", () -> dev.logos.alchemy.CultivationRecipe.SERIALIZER);
    /** Crafting a page into a higher grade, spell kept (type logos:page_upgrade). */
    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeSerializer<?>, net.minecraft.world.item.crafting.RecipeSerializer<dev.logos.item.PageUpgradeRecipe>> PAGE_UPGRADE_SERIALIZER =
            RECIPE_SERIALIZERS.register("page_upgrade", () -> dev.logos.item.PageUpgradeRecipe.SERIALIZER);
    public static final DeferredHolder<net.minecraft.world.item.crafting.RecipeBookCategory, net.minecraft.world.item.crafting.RecipeBookCategory> ALCHEMY_BOOK_CATEGORY =
            RECIPE_BOOK_CATEGORIES.register("alchemy", net.minecraft.world.item.crafting.RecipeBookCategory::new);

    /** 游离魔力: mana a finished spell leaves in the world, gathered like experience. */
    public static final DeferredHolder<net.minecraft.world.entity.EntityType<?>, net.minecraft.world.entity.EntityType<dev.logos.runtime.FreeManaEntity>> FREE_MANA =
            ENTITY_TYPES.register("free_mana", key -> net.minecraft.world.entity.EntityType.Builder.<dev.logos.runtime.FreeManaEntity>of(
                            dev.logos.runtime.FreeManaEntity::new, net.minecraft.world.entity.MobCategory.MISC)
                    .sized(0.5f, 0.5f).clientTrackingRange(6).updateInterval(2)
                    .build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, key)));

    /** 魔力生命体: the most basic alchemical life, summoned from mana alone; its size grows with its level. */
    public static final DeferredHolder<net.minecraft.world.entity.EntityType<?>, net.minecraft.world.entity.EntityType<dev.logos.runtime.ManaLifeform>> MANA_LIFEFORM =
            ENTITY_TYPES.register("mana_lifeform", key -> net.minecraft.world.entity.EntityType.Builder.<dev.logos.runtime.ManaLifeform>of(
                            dev.logos.runtime.ManaLifeform::new, net.minecraft.world.entity.MobCategory.MISC)
                    .sized(0.4f, 0.5f).clientTrackingRange(10)
                    .build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, key)));

    /** One full-mana egg for each of the five lifeform levels. */
    public static final List<DeferredItem<dev.logos.item.ManaLifeformSpawnEggItem>> MANA_LIFEFORM_SPAWN_EGGS =
            java.util.stream.IntStream.rangeClosed(1, dev.logos.runtime.ManaLifeform.MAX_LEVEL)
                    .mapToObj(lv -> ITEMS.registerItem("mana_lifeform_spawn_egg_" + lv,
                            p -> new dev.logos.item.ManaLifeformSpawnEggItem(p, lv), p -> p.spawnEgg(MANA_LIFEFORM.get())))
                    .toList();

    /** 炼金术巨像: a built colossus; raised by command until alchemy can build one. */
    public static final DeferredHolder<net.minecraft.world.entity.EntityType<?>, net.minecraft.world.entity.EntityType<dev.logos.runtime.AlchemicalColossus>> ALCHEMICAL_COLOSSUS =
            ENTITY_TYPES.register("alchemical_colossus", key -> net.minecraft.world.entity.EntityType.Builder.<dev.logos.runtime.AlchemicalColossus>of(
                            dev.logos.runtime.AlchemicalColossus::new, net.minecraft.world.entity.MobCategory.MISC)
                    .sized(1.9f, 3.4f).clientTrackingRange(10)
                    .build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, key)));

    /** A colossus egg: the same full, loyal guardian the command raises. */
    public static final DeferredItem<dev.logos.item.ColossusSpawnEggItem> ALCHEMICAL_COLOSSUS_SPAWN_EGG =
            ITEMS.registerItem("alchemical_colossus_spawn_egg", dev.logos.item.ColossusSpawnEggItem::new,
                    p -> p.spawnEgg(ALCHEMICAL_COLOSSUS.get()));

    /** 汲魔蛭: a hostile arthropod that leaps onto the face of whoever holds the most mana and drinks it. Not yet spawned naturally. */
    public static final DeferredHolder<net.minecraft.world.entity.EntityType<?>, net.minecraft.world.entity.EntityType<dev.logos.runtime.ManaLeech>> MANA_LEECH =
            ENTITY_TYPES.register("mana_leech", key -> net.minecraft.world.entity.EntityType.Builder.<dev.logos.runtime.ManaLeech>of(
                            dev.logos.runtime.ManaLeech::new, net.minecraft.world.entity.MobCategory.MONSTER)
                    .sized(0.8f, 0.45f).clientTrackingRange(8)
                    .build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, key)));
    public static final DeferredItem<net.minecraft.world.item.SpawnEggItem> MANA_LEECH_SPAWN_EGG =
            ITEMS.registerItem("mana_leech_spawn_egg", net.minecraft.world.item.SpawnEggItem::new, p -> p.spawnEgg(MANA_LEECH.get()));

    /** Items that give a mana lifeform mana when it is fed with them (data/logos/data_maps/item/mana_fuel.json). */
    public static final net.neoforged.neoforge.registries.datamaps.DataMapType<net.minecraft.world.item.Item, dev.logos.runtime.Lifeforms.ManaFuel> MANA_FUEL =
            net.neoforged.neoforge.registries.datamaps.DataMapType.builder(net.minecraft.resources.Identifier.fromNamespaceAndPath(MODID, "mana_fuel"),
                    net.minecraft.core.registries.Registries.ITEM, dev.logos.runtime.Lifeforms.ManaFuel.CODEC)
                    .synced(dev.logos.runtime.Lifeforms.ManaFuel.CODEC, false).build();
    /** The bottled liquids a cultivation jar takes as its medium (data_maps/item/cultivation_medium.json). */
    public static final net.neoforged.neoforge.registries.datamaps.DataMapType<net.minecraft.world.item.Item, dev.logos.alchemy.CultivationMedium> CULTIVATION_MEDIUM =
            net.neoforged.neoforge.registries.datamaps.DataMapType.builder(net.minecraft.resources.Identifier.fromNamespaceAndPath(MODID, "cultivation_medium"),
                    net.minecraft.core.registries.Registries.ITEM, dev.logos.alchemy.CultivationMedium.CODEC)
                    .synced(dev.logos.alchemy.CultivationMedium.CODEC, false).build();

    public static final DeferredRegister<net.minecraft.world.entity.ai.attributes.Attribute> ATTRIBUTES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.ATTRIBUTE, MODID);
    /** How far free mana is drawn towards a player (experience orbs use 8). Gear can raise it. */
    public static final DeferredHolder<net.minecraft.world.entity.ai.attributes.Attribute, net.minecraft.world.entity.ai.attributes.Attribute> MANA_ATTRACTION =
            ATTRIBUTES.register("mana_attraction_range", () -> new net.minecraft.world.entity.ai.attributes.RangedAttribute(
                    "attribute.logos.mana_attraction_range", 8, 0, 64).setSyncable(true));
    /** How much of the free mana a player gathers becomes their own mana. Gear can raise it. */
    public static final DeferredHolder<net.minecraft.world.entity.ai.attributes.Attribute, net.minecraft.world.entity.ai.attributes.Attribute> MANA_ABSORPTION =
            ATTRIBUTES.register("free_mana_efficiency", () -> new net.minecraft.world.entity.ai.attributes.RangedAttribute(
                    "attribute.logos.free_mana_efficiency", 0.5, 0, 16).setSyncable(true));
    /** Mana per second a charging wand starts injecting at. The wand in the main hand gives it; gear can add to it. */
    public static final DeferredHolder<net.minecraft.world.entity.ai.attributes.Attribute, net.minecraft.world.entity.ai.attributes.Attribute> CHANNEL_FLOW =
            ATTRIBUTES.register("channel_flow", () -> new net.minecraft.world.entity.ai.attributes.RangedAttribute(
                    "attribute.logos.channel_flow", 0, 0, 1e6).setSyncable(true));
    /** How much faster, per second, a charging wand's injection grows. The wand in the main hand gives it; gear can add to it. */
    public static final DeferredHolder<net.minecraft.world.entity.ai.attributes.Attribute, net.minecraft.world.entity.ai.attributes.Attribute> CHANNEL_SURGE =
            ATTRIBUTES.register("channel_surge", () -> new net.minecraft.world.entity.ai.attributes.RangedAttribute(
                    "attribute.logos.channel_surge", 0, 0, 1e6).setSyncable(true));

    public static final DeferredRegister<net.minecraft.world.effect.MobEffect> MOB_EFFECTS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.MOB_EFFECT, MODID);
    /** 缩放: a timed change of the vanilla scale attribute. */
    public static final DeferredHolder<net.minecraft.world.effect.MobEffect, net.minecraft.world.effect.MobEffect> RESIZED =
            MOB_EFFECTS.register("resized", dev.logos.runtime.Resized::new);

    /** 焚式: a data-driven enchantment (data/logos/enchantment/formula_immolation.json). */
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> FORMULA_IMMOLATION =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(MODID, "formula_immolation"));

    // Four creative tabs: spell supplies, the example spells, the Great Work (alchemy) and spawn eggs.
    /** 术式用品: wands, pages and the tables and blocks that write, copy and cast them. */
    public static final DeferredHolder<net.minecraft.world.item.CreativeModeTab, net.minecraft.world.item.CreativeModeTab> TAB = TABS.register("logos",
            () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.logos"))
            .icon(() -> new ItemStack(INSCRIBED_PAGE.get()))
            .displayItems((params, out) -> {
                for (var wand : WOOD_WANDS) out.accept(wand.get());
                for (var staff : WOOD_STAFFS) out.accept(staff.get());
                out.accept(WAND.get());
                out.accept(ADEPT_WAND.get());
                out.accept(MASTER_WAND.get());
                for (PageTier t : PageTier.values()) out.accept(t.blank());
                out.accept(SPELL_EMBER.get());
                out.accept(SPELL_CODEX.get());
                out.accept(INSCRIPTION_TABLE_ITEM.get());
                out.accept(COPY_TABLE_ITEM.get());
                out.accept(SPELL_EMITTER_ITEM.get());
                out.accept(MANA_COLLECTOR_ITEM.get());
                out.accept(MANA_STORAGE_TANK_ITEM.get());
                out.accept(MANA_SPREADER_ITEM.get());
            }).build());
    /** 术式范本: every example spell, written on the page grade that fits it. */
    public static final DeferredHolder<net.minecraft.world.item.CreativeModeTab, net.minecraft.world.item.CreativeModeTab> EXAMPLES_TAB = TABS.register("logos_examples",
            () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.logos_examples"))
            .icon(() -> new ItemStack(PAGES.get(PageTier.CRYSTAL).inscribed().get()))
            .withTabsBefore(TAB.getKey())
            .displayItems((params, out) -> {
                for (var e : dev.logos.lang.Examples.ALL.values()) out.accept(dev.logos.item.SpellPages.fitting(e));
            }).build());
    /** 伟大功业: the alchemy table, its materials and products, the dividers and the anchor nail. */
    public static final DeferredHolder<net.minecraft.world.item.CreativeModeTab, net.minecraft.world.item.CreativeModeTab> OPUS_TAB = TABS.register("logos_opus",
            () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.logos_opus"))
            .icon(() -> new ItemStack(PHILOSOPHERS_MERCURY.get()))
            .withTabsBefore(EXAMPLES_TAB.getKey())
            .displayItems((params, out) -> {
                out.accept(ALCHEMY_TABLE_ITEM.get());
                out.accept(DIVIDERS_ITEM.get());
                out.accept(ANCHOR_NAIL.get());
                out.accept(COLOSSUS_HEART.get());
                out.accept(DISORDER_CRYSTAL.get());
                out.accept(MAINTENANCE_KIT.get());
                out.accept(TAINTED_MANA_SHARD.get());
                out.accept(MANA_SHARD.get());
                out.accept(MANA_CRYSTAL.get());
                out.accept(MANA_GEMSTONE.get());
                out.accept(STARTER_CULTURE.get());
                out.accept(RAW_MASH.get());
                out.accept(FOUL_BRINE.get());
                out.accept(NITER_MOTHER_LIQUOR.get());
                out.accept(SULFUR_POWDER.get());
                out.accept(SALTPETER.get());
                out.accept(ADHESIVE.get());
                out.accept(ECHO_TINCTURE.get());
                out.accept(MERCURY_BOTTLE.get());
                out.accept(PHILOSOPHERS_MERCURY.get());
                out.accept(REBIS.get());
                out.accept(RAVEN_HEAD.get());
                out.accept(VITAL_MASH.get());
                out.accept(TINCTURE_OF_LIFE.get());
                out.accept(CAPUT_MORTUUM.get());
                out.accept(CAPUT_MORTUUM_BLOCK_ITEM.get());
                out.accept(SOUL_HUMUS_ITEM.get());
                out.accept(CULTIVATION_JAR_ITEM.get());
                out.accept(GERMINAL_ESSENCE.get());
                out.accept(NITER_SPROUT.get());
                for (JarMetal metal : JarMetal.values()) out.accept(metal.embryo.get());
                for (JarMetal metal : JarMetal.values()) out.accept(metal.seed.get());
                for (JarMetal metal : JarMetal.values()) out.accept(metal.pod.get());
                out.accept(MANDRAKE_SEED.get());
                out.accept(MANDRAKE_ROOT.get());
                out.accept(MERCURY_SPORE.get());
                out.accept(MERCURY_MUSHROOM.get());
                out.accept(STARLIGHT_MOSS_SPORE.get());
                out.accept(STARLIGHT_MOSS.get());
                for (JarHerb herb : JarHerb.values()) {
                    out.accept(herb.seed.get());
                    out.accept(herb.plant.get());
                    if (herb.harvest != herb.plant) out.accept(herb.harvest.get());
                }
                out.accept(CHALK.get());
                out.accept(SOLAR_AMALGAM.get());
                out.accept(SOLAR_BLOSSOM.get());
                out.accept(INCANDESCENT_WHITE_STONE.get());
                out.accept(FIRMAMENT_LIQUID.get());
                out.accept(FIRMAMENT_STONE.get());
                out.accept(REDDENING_STONE.get());
                out.accept(RED_LION.get());
                out.accept(AETHER.get());
                out.accept(QUINTESSENCE.get());
                out.accept(PHILOSOPHERS_STONE.get());
            }).build());
    /** 刷怪蛋: the colossus, the mana leech and the five lifeform levels. */
    public static final DeferredHolder<net.minecraft.world.item.CreativeModeTab, net.minecraft.world.item.CreativeModeTab> SPAWN_EGGS_TAB = TABS.register("logos_spawn_eggs",
            () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.logos_spawn_eggs"))
            .icon(() -> new ItemStack(MANA_LIFEFORM_SPAWN_EGGS.getLast().get()))
            .withTabsBefore(OPUS_TAB.getKey())
            .displayItems((params, out) -> {
                out.accept(ALCHEMICAL_COLOSSUS_SPAWN_EGG.get());
                out.accept(MANA_LEECH_SPAWN_EGG.get());
                for (var egg : MANA_LIFEFORM_SPAWN_EGGS) out.accept(egg.get());
            }).build());
    /** 魔药: the Logos potions, drunk, splash and lingering, then as tipped arrows; vanilla's tabs leave them to this one. */
    public static final DeferredHolder<net.minecraft.world.item.CreativeModeTab, net.minecraft.world.item.CreativeModeTab> POTIONS_TAB = TABS.register("logos_potions",
            () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.logos_potions"))
            .icon(() -> net.minecraft.world.item.alchemy.PotionContents.createItemStack(net.minecraft.world.item.Items.POTION,
                    dev.logos.alchemy.BuffPotions.MANA_FLOW_POTION))
            .withTabsBefore(SPAWN_EGGS_TAB.getKey())
            .displayItems((params, out) -> {
                var potions = params.holders().lookupOrThrow(net.minecraft.core.registries.Registries.POTION).listElements()
                        .filter(p -> p.key().identifier().getNamespace().equals(MODID) && p.value().isEnabled(params.enabledFeatures())).toList();
                for (var item : List.of(net.minecraft.world.item.Items.POTION, net.minecraft.world.item.Items.SPLASH_POTION,
                        net.minecraft.world.item.Items.LINGERING_POTION, net.minecraft.world.item.Items.TIPPED_ARROW)) {
                    for (var potion : potions) out.accept(net.minecraft.world.item.alchemy.PotionContents.createItemStack(item, potion));
                }
            }).build());

    public static final Supplier<AttachmentType<StatusData>> STATUS_DATA = ATTACHMENTS.register("status",
            () -> AttachmentType.builder(StatusData::new).build());
    public static final Supplier<AttachmentType<PlayerMagic>> PLAYER_MAGIC = ATTACHMENTS.register("magic",
            () -> AttachmentType.builder(() -> new PlayerMagic()).serialize(PlayerMagic.CODEC).copyOnDeath().build());
    /** On a summoned creature: whose familiar it is and until when. Saved only so a reloaded one is recognised and removed. */
    public static final Supplier<AttachmentType<dev.logos.runtime.Familiars.Bond>> FAMILIAR = ATTACHMENTS.register("familiar",
            () -> AttachmentType.builder(() -> dev.logos.runtime.Familiars.Bond.NONE).serialize(dev.logos.runtime.Familiars.Bond.CODEC.fieldOf("bond"))
                    // Synced: clients must know who may ride a familiar and who steers it.
                    .sync(dev.logos.runtime.Familiars.Bond.STREAM_CODEC).build());
    public static final Supplier<AttachmentType<dev.logos.runtime.Anchors>> ANCHORS = ATTACHMENTS.register("anchors",
            () -> AttachmentType.builder(() -> new dev.logos.runtime.Anchors()).serialize(dev.logos.runtime.Anchors.CODEC).copyOnDeath().build());
    public static final Supplier<AttachmentType<dev.logos.runtime.Attunement>> ATTUNEMENT = ATTACHMENTS.register("attunement",
            () -> AttachmentType.builder(() -> new dev.logos.runtime.Attunement()).serialize(dev.logos.runtime.Attunement.CODEC).copyOnDeath().build());
    /** On a chunk: the spread mana in its air (dev.logos.runtime.ManaField); saved only while there is some. */
    public static final Supplier<AttachmentType<Double>> MANA_FIELD = ATTACHMENTS.register("mana_field",
            () -> AttachmentType.builder(() -> 0.0).serialize(com.mojang.serialization.Codec.DOUBLE.fieldOf("mana"), v -> v > 0).build());

    public static final DeferredRegister<net.minecraft.core.particles.ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.PARTICLE_TYPE, MODID);
    /** A mote of mana drifting in the air (dev.logos.client.ManaMote). */
    public static final Supplier<net.minecraft.core.particles.SimpleParticleType> MANA_MOTE =
            PARTICLE_TYPES.register("mana_mote", () -> new net.minecraft.core.particles.SimpleParticleType(false));

    /** Passive spells triggered inside damage events run on the next tick, outside the event. */
    private static final List<Runnable> DEFERRED = new ArrayList<>();

    public LogosMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, LogosConfig.SPEC);
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        RECIPE_TYPES.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
        RECIPE_BOOK_CATEGORIES.register(modBus);
        ENTITY_TYPES.register(modBus);
        TABS.register(modBus);
        COMPONENTS.register(modBus);
        ATTACHMENTS.register(modBus);
        PARTICLE_TYPES.register(modBus);
        dev.logos.runtime.SpellModifiers.init();
        ATTRIBUTES.register(modBus);
        dev.logos.alchemy.BuffPotions.init();
        MOB_EFFECTS.register(modBus);
        dev.logos.alchemy.BuffPotions.POTIONS.register(modBus);
        modBus.addListener(this::registerPayloads);
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
                e.accept(ALCHEMICAL_COLOSSUS_SPAWN_EGG.get());
                e.accept(MANA_LEECH_SPAWN_EGG.get());
                for (var egg : MANA_LIFEFORM_SPAWN_EGGS) e.accept(egg.get());
            }
            // Logos potions live in their own tab; search still finds them.
            if (e.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS || e.getTabKey() == CreativeModeTabs.COMBAT) {
                e.removeIf(dev.logos.alchemy.BuffPotions::isLogosPotion, net.minecraft.world.item.CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
            }
        });
        modBus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent e) -> {
            e.put(MANA_LIFEFORM.get(), dev.logos.runtime.ManaLifeform.createAttributes().build());
            e.put(ALCHEMICAL_COLOSSUS.get(), dev.logos.runtime.AlchemicalColossus.createAttributes().build());
            e.put(MANA_LEECH.get(), dev.logos.runtime.ManaLeech.createAttributes().build());
        });
        modBus.addListener((net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent e) -> {
            e.register(MANA_FUEL);
            e.register(CULTIVATION_MEDIUM);
        });
        // Either half of an alchemy table answers hoppers, pipes and tanks for the whole table.
        modBus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent e) -> {
            e.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, (level, pos, state, be, side) -> {
                var table = dev.logos.block.AlchemyTableBlock.table(level, pos, state);
                return table == null ? null : table.automation();
            }, ALCHEMY_TABLE.get());
            // A spreader's drawer takes fuel from hoppers and pipes.
            e.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, (level, pos, state, be, side) ->
                    be instanceof dev.logos.block.ManaSpreaderBlockEntity spreader ? spreader.fuel() : null, MANA_SPREADER.get());
            // A storage tank takes mana items and seeds from hoppers and pipes, and gives up grown crystals.
            e.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, (level, pos, state, be, side) ->
                    be instanceof dev.logos.block.ManaStorageTankBlockEntity tank ? tank.automation() : null, MANA_STORAGE_TANK.get());
            // A jar takes seeds and media from hoppers and pipes, and gives up its harvests.
            e.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, (level, pos, state, be, side) ->
                    be instanceof dev.logos.block.CultivationJarBlockEntity jar ? jar.automation() : null, CULTIVATION_JAR.get());
            e.registerBlock(net.neoforged.neoforge.capabilities.Capabilities.Fluid.BLOCK, (level, pos, state, be, side) -> {
                var table = dev.logos.block.AlchemyTableBlock.table(level, pos, state);
                return table == null ? null : table.tank();
            }, ALCHEMY_TABLE.get());
        });
        dev.logos.runtime.Lifeforms.registerUI();
        dev.logos.runtime.Colossi.registerUI();
        modBus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent e) -> {
            e.add(net.minecraft.world.entity.EntityType.PLAYER, MANA_ATTRACTION);
            e.add(net.minecraft.world.entity.EntityType.PLAYER, MANA_ABSORPTION);
            e.add(net.minecraft.world.entity.EntityType.PLAYER, CHANNEL_FLOW);
            e.add(net.minecraft.world.entity.EntityType.PLAYER, CHANNEL_SURGE);
            dev.logos.runtime.SpellModifiers.addTo(e);
        });
        // ❖ looks (&mace…) must name an item the game actually has, modded items included.
        dev.logos.lang.Compiler.itemExists = id -> {
            var key = net.minecraft.resources.Identifier.tryParse(id);
            return key != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key);
        };
        // ✧ particles (%portal…) likewise; only option-less ones, since a spell has no way to give dust a colour yet.
        dev.logos.lang.Compiler.particleExists = id -> {
            var key = net.minecraft.resources.Identifier.tryParse(id);
            return key != null && net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getOptional(key)
                    .filter(t -> t instanceof net.minecraft.core.particles.SimpleParticleType).isPresent();
        };
        // ❦ creatures (summon zombie) must be entity types the game has, modded ones included.
        dev.logos.lang.Compiler.creatureExists = id -> {
            var key = net.minecraft.resources.Identifier.tryParse(id);
            return key != null && net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(key);
        };
        // ⚗ effects (potion target speed …) likewise: any registered mob effect, modded ones included.
        dev.logos.lang.Compiler.effectExists = id -> {
            var key = net.minecraft.resources.Identifier.tryParse(id);
            return key != null && net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.containsKey(key);
        };
        dev.logos.gametest.LogosGameTests.register(modBus);
        NeoForge.EVENT_BUS.register(this);
    }

    private void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(dev.logos.net.InscribePayload.TYPE, dev.logos.net.InscribePayload.CODEC, dev.logos.net.InscribePayload::handle);
        registrar.playToServer(dev.logos.net.EmitterEditPayload.TYPE, dev.logos.net.EmitterEditPayload.CODEC, dev.logos.net.EmitterEditPayload::handle);
        registrar.playToClient(dev.logos.net.FxPayload.TYPE, dev.logos.net.FxPayload.CODEC);
        registrar.playToClient(dev.logos.net.LifeformCorePayload.TYPE, dev.logos.net.LifeformCorePayload.CODEC);
        registrar.playToServer(dev.logos.net.SelectSpellPayload.TYPE, dev.logos.net.SelectSpellPayload.CODEC, dev.logos.net.SelectSpellPayload::handle);
        registrar.playToServer(dev.logos.net.StaffFillPayload.TYPE, dev.logos.net.StaffFillPayload.CODEC, dev.logos.net.StaffFillPayload::handle);
        registrar.playToServer(dev.logos.net.ColossusControlPayload.TYPE, dev.logos.net.ColossusControlPayload.CODEC, dev.logos.net.ColossusControlPayload::handle);
        registrar.playToServer(dev.logos.net.DragonControlPayload.TYPE, dev.logos.net.DragonControlPayload.CODEC, dev.logos.net.DragonControlPayload::handle);
        registrar.playToClient(dev.logos.net.AnchorsPayload.TYPE, dev.logos.net.AnchorsPayload.CODEC);
        registrar.playToClient(dev.logos.net.ChargePayload.TYPE, dev.logos.net.ChargePayload.CODEC);
        registrar.playToClient(dev.logos.net.AreaWeatherPayload.TYPE, dev.logos.net.AreaWeatherPayload.CODEC);
        registrar.playToClient(dev.logos.net.ManaFieldPayload.TYPE, dev.logos.net.ManaFieldPayload.CODEC);
        registrar.playToServer(dev.logos.net.AnchorEditPayload.TYPE, dev.logos.net.AnchorEditPayload.CODEC, dev.logos.net.AnchorEditPayload::handle);
        registrar.playToServer(dev.logos.net.AlchemyCardPayload.TYPE, dev.logos.net.AlchemyCardPayload.CODEC, dev.logos.net.AlchemyCardPayload::handle);
        registrar.playToServer(dev.logos.net.SageAskPayload.TYPE, dev.logos.net.SageAskPayload.CODEC, dev.logos.net.SageAskPayload::handle);
        registrar.playToServer(dev.logos.net.SageCancelPayload.TYPE, dev.logos.net.SageCancelPayload.CODEC, dev.logos.net.SageCancelPayload::handle);
        registrar.playToServer(dev.logos.net.SageAdminPayload.TYPE, dev.logos.net.SageAdminPayload.CODEC, dev.logos.net.SageAdminPayload::handle);
        registrar.playToClient(dev.logos.net.SageReplyPayload.TYPE, dev.logos.net.SageReplyPayload.CODEC);
        registrar.playToClient(dev.logos.net.SageStatusPayload.TYPE, dev.logos.net.SageStatusPayload.CODEC);
        registrar.playToClient(dev.logos.net.SageAdminStatePayload.TYPE, dev.logos.net.SageAdminStatePayload.CODEC);
    }

    /** The client keeps a copy of its anchors (editor list, markers); resend whenever the player is (re)created. */
    @SubscribeEvent
    public void onLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            dev.logos.runtime.Anchors.sync(sp);
            dev.logos.runtime.Attunement.record(sp);
        }
    }

    @SubscribeEvent
    public void onRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) dev.logos.runtime.Anchors.sync(sp);
    }

    @SubscribeEvent
    public void onChangeDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            dev.logos.runtime.Anchors.sync(sp);
            dev.logos.runtime.Attunement.record(sp);
            dev.logos.runtime.Charging.interrupt(sp);
        }
    }

    @SubscribeEvent
    public void onLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) dev.logos.runtime.Charging.interrupt(sp);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        LogosCommands.register(event.getDispatcher());
    }

    /** A water bottle and starter culture brew into raw mash; raw and vital mash brew the Logos potions. */
    @SubscribeEvent
    public void onBrewing(net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent event) {
        dev.logos.alchemy.BuffPotions.brewing(event.getBuilder());
        event.getBuilder().addRecipe(new net.neoforged.neoforge.common.brewing.IBrewingRecipe() {
            @Override
            public boolean isInput(ItemStack input) {
                return input.is(net.minecraft.world.item.Items.POTION) && input.getOrDefault(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                        net.minecraft.world.item.alchemy.PotionContents.EMPTY).is(net.minecraft.world.item.alchemy.Potions.WATER);
            }

            @Override
            public boolean isIngredient(ItemStack ingredient) {
                return ingredient.is(STARTER_CULTURE.get());
            }

            @Override
            public ItemStack getOutput(ItemStack input, ItemStack ingredient) {
                return isInput(input) && isIngredient(ingredient) ? new ItemStack(RAW_MASH.get()) : ItemStack.EMPTY;
            }
        });
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        LogosCommands.exportExamples();
    }

    /** Runs {@code r} after this server tick's events, outside whatever event is being handled now. */
    public static void defer(Runnable r) {
        DEFERRED.add(r);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        SpellRuntime.clear();
        dev.logos.runtime.AreaWeather.clearServer();
        DEFERRED.clear();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        List<Runnable> run = new ArrayList<>(DEFERRED);
        DEFERRED.clear();
        run.forEach(Runnable::run);
        SpellRuntime.tick();
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        PlayerMagic m = PlayerMagic.of(sp);
        m.mana = Math.min(m.maxMana, m.mana + dev.logos.runtime.SpellModifiers.regenerationPerTick(sp));
        dev.logos.runtime.StaffCharging.tick(sp);
        long now = sp.level().getGameTime();
        if (now % 10 != 0 || now < m.hudQuietUntil) return;
        ItemStack wand = dev.logos.item.SpellBag.heldWand(sp);
        if (wand == null) return;
        int filled = (int) Math.round(10 * m.mana / Math.max(1, m.maxMana));
        Component hud = Component.literal("☿ ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("█".repeat(filled)).withStyle(ChatFormatting.BLUE))
                .append(Component.literal("█".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(" " + (int) m.mana + "/" + (int) m.maxMana).withStyle(ChatFormatting.AQUA));
        // A staff casts from what it holds, not from the pool.
        double source = m.mana;
        if (wand.getItem() instanceof dev.logos.item.StaffItem staff) {
            source = dev.logos.item.StaffItem.stored(wand);
            hud = hud.copy().append(Component.literal("  杖内 " + (int) source + "/" + (int) staff.capacity()).withStyle(ChatFormatting.DARK_AQUA));
        }
        var selected = dev.logos.item.SpellBag.selected(sp, wand);
        if (selected != null) {
            Spell s = selected.spell();
            var in = Casting.injection(s, selected.tier(), Casting.immolation(sp, wand));
            ChatFormatting c = source >= in.mana() ? ChatFormatting.GRAY : ChatFormatting.RED;
            String inject = in.mana() < s.mana() ? Node.fmt(in.mana()) + "/" + Node.fmt(s.mana()) : Node.fmt(in.mana());
            hud = hud.copy().append(Component.literal("  《" + s.name() + "》注入 " + inject + (in.burns() ? " 🜂焚" : "")
                    + " · 稳定 " + Node.fmt(s.stability()) + "/" + m.stability).withStyle(c));
        }
        sp.sendOverlayMessage(hud);
    }

    @SubscribeEvent
    public void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (Effects.inSpell() || victim.level().isClientSide()) return;
        Entity attacker = event.getSource().getEntity();
        if (victim instanceof ServerPlayer p) {
            DEFERRED.add(() -> Casting.firePassives(p, "hurt", attacker, p.getEyePosition()));
        }
        if (attacker instanceof ServerPlayer p && attacker != victim) {
            DEFERRED.add(() -> Casting.firePassives(p, "attack", victim, Targeting.center(victim)));
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof ServerPlayer sp) dev.logos.runtime.Charging.interrupt(sp);
        if (Effects.inSpell() || dead.level().isClientSide()) return;
        if (event.getSource().getEntity() instanceof ServerPlayer p && p != dead) {
            var at = Targeting.center(dead);
            DEFERRED.add(() -> Casting.firePassives(p, "kill", dead, at));
        }
    }
}

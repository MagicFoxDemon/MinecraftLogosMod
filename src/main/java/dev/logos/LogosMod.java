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
import dev.logos.runtime.Limits;
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

    /** Which spell (index into wand pages + off-hand codex pages) the wand will cast. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> SELECTED = COMPONENTS.registerComponentType("selected",
            b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** Each completed brewing round adds one shard; the count has no fixed-width integer limit. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<java.math.BigInteger>> MANA_POTION_SHARDS =
            COMPONENTS.registerComponentType("mana_potion_shards",
                    b -> b.persistent(dev.logos.item.ManaPotionItem.SHARD_COUNT_CODEC)
                            .networkSynchronized(ByteBufCodecs.fromCodec(dev.logos.item.ManaPotionItem.SHARD_COUNT_CODEC)));

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

    /** Wand tiers differ only in how many spell pages they can hold. */
    public static final DeferredItem<WandItem> WAND = ITEMS.registerItem("wand", p -> new WandItem(p, 3), p -> p.stacksTo(1));
    public static final DeferredItem<WandItem> ADEPT_WAND = ITEMS.registerItem("adept_wand", p -> new WandItem(p, 6), p -> p.stacksTo(1).rarity(net.minecraft.world.item.Rarity.RARE));
    public static final DeferredItem<WandItem> MASTER_WAND = ITEMS.registerItem("master_wand",
            p -> new WandItem(p, 9), p -> p.stacksTo(1).rarity(net.minecraft.world.item.Rarity.EPIC));
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

    // Alchemical colossus materials. Placeholders for now: their alchemy (recipes, planting) comes later.
    /** 巨像之心: the flower a colossus offers its master. An alchemical material. */
    public static final DeferredItem<net.minecraft.world.item.Item> COLOSSUS_HEART = ITEMS.registerSimpleItem("colossus_heart",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    /** 失序结晶: left behind when a colossus's mana frenzy burns out. */
    public static final DeferredItem<net.minecraft.world.item.Item> DISORDER_CRYSTAL = ITEMS.registerSimpleItem("disorder_crystal",
            p -> p.rarity(net.minecraft.world.item.Rarity.RARE));
    /** 维护套件: heals a colossus and mends its scorched shell. */
    public static final DeferredItem<net.minecraft.world.item.Item> MAINTENANCE_KIT = ITEMS.registerSimpleItem("maintenance_kit", p -> p.stacksTo(16));
    /** 被污染的魔力碎片: an unstable fuel; fed to a colossus, it sends it into a mana frenzy. */
    public static final DeferredItem<net.minecraft.world.item.Item> TAINTED_MANA_SHARD = ITEMS.registerSimpleItem("tainted_mana_shard",
            p -> p.rarity(net.minecraft.world.item.Rarity.UNCOMMON));
    public static final DeferredItem<dev.logos.item.ManaPotionItem> MANA_POTION = ITEMS.registerItem("mana_potion",
            dev.logos.item.ManaPotionItem::new, p -> p.stacksTo(1));

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

    /** 炼金术巨像: a built colossus; raised by command until alchemy can build one. */
    public static final DeferredHolder<net.minecraft.world.entity.EntityType<?>, net.minecraft.world.entity.EntityType<dev.logos.runtime.AlchemicalColossus>> ALCHEMICAL_COLOSSUS =
            ENTITY_TYPES.register("alchemical_colossus", key -> net.minecraft.world.entity.EntityType.Builder.<dev.logos.runtime.AlchemicalColossus>of(
                            dev.logos.runtime.AlchemicalColossus::new, net.minecraft.world.entity.MobCategory.MISC)
                    .sized(1.9f, 3.4f).clientTrackingRange(10)
                    .build(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENTITY_TYPE, key)));

    /** Items that give a mana lifeform mana when it is fed with them (data/logos/data_maps/item/mana_fuel.json). */
    public static final net.neoforged.neoforge.registries.datamaps.DataMapType<net.minecraft.world.item.Item, dev.logos.runtime.Lifeforms.ManaFuel> MANA_FUEL =
            net.neoforged.neoforge.registries.datamaps.DataMapType.builder(net.minecraft.resources.Identifier.fromNamespaceAndPath(MODID, "mana_fuel"),
                    net.minecraft.core.registries.Registries.ITEM, dev.logos.runtime.Lifeforms.ManaFuel.CODEC)
                    .synced(dev.logos.runtime.Lifeforms.ManaFuel.CODEC, false).build();

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

    /** 焚式: a data-driven enchantment (data/logos/enchantment/formula_immolation.json). */
    public static final net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> FORMULA_IMMOLATION =
            net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(MODID, "formula_immolation"));

    public static final Supplier<net.minecraft.world.item.CreativeModeTab> TAB = TABS.register("logos", () -> net.minecraft.world.item.CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.logos"))
            .icon(() -> new ItemStack(INSCRIBED_PAGE.get()))
            .displayItems((params, out) -> {
                out.accept(WAND.get());
                out.accept(ADEPT_WAND.get());
                out.accept(MASTER_WAND.get());
                for (PageTier t : PageTier.values()) out.accept(t.blank());
                out.accept(SPELL_EMBER.get());
                out.accept(SPELL_CODEX.get());
                out.accept(ANCHOR_NAIL.get());
                out.accept(COLOSSUS_HEART.get());
                out.accept(DISORDER_CRYSTAL.get());
                out.accept(MAINTENANCE_KIT.get());
                out.accept(TAINTED_MANA_SHARD.get());
                out.accept(MANA_POTION.get());
                out.accept(INSCRIPTION_TABLE_ITEM.get());
                out.accept(COPY_TABLE_ITEM.get());
                for (var e : dev.logos.lang.Examples.ALL.values()) out.accept(dev.logos.item.SpellPages.fitting(e));
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

    /** Passive spells triggered inside damage events run on the next tick, outside the event. */
    private static final List<Runnable> DEFERRED = new ArrayList<>();

    public LogosMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, LogosConfig.SPEC);
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITY_TYPES.register(modBus);
        TABS.register(modBus);
        COMPONENTS.register(modBus);
        ATTACHMENTS.register(modBus);
        ATTRIBUTES.register(modBus);
        modBus.addListener(this::registerPayloads);
        modBus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent e) -> {
            e.put(MANA_LIFEFORM.get(), dev.logos.runtime.ManaLifeform.createAttributes().build());
            e.put(ALCHEMICAL_COLOSSUS.get(), dev.logos.runtime.AlchemicalColossus.createAttributes().build());
        });
        modBus.addListener((net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent e) -> e.register(MANA_FUEL));
        dev.logos.runtime.Lifeforms.registerUI();
        dev.logos.runtime.Colossi.registerUI();
        modBus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent e) -> {
            e.add(net.minecraft.world.entity.EntityType.PLAYER, MANA_ATTRACTION);
            e.add(net.minecraft.world.entity.EntityType.PLAYER, MANA_ABSORPTION);
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

    /** Adds the repeatable mana potion recipe to each world's vanilla brewing rules. */
    @SubscribeEvent
    public void registerBrewingRecipes(net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent event) {
        event.getBuilder().addRecipe(new dev.logos.item.ManaPotionBrewingRecipe());
    }

    private void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(dev.logos.net.InscribePayload.TYPE, dev.logos.net.InscribePayload.CODEC, dev.logos.net.InscribePayload::handle);
        registrar.playToClient(dev.logos.net.FxPayload.TYPE, dev.logos.net.FxPayload.CODEC);
        registrar.playToClient(dev.logos.net.LifeformCorePayload.TYPE, dev.logos.net.LifeformCorePayload.CODEC);
        registrar.playToServer(dev.logos.net.SelectSpellPayload.TYPE, dev.logos.net.SelectSpellPayload.CODEC, dev.logos.net.SelectSpellPayload::handle);
        registrar.playToServer(dev.logos.net.ColossusControlPayload.TYPE, dev.logos.net.ColossusControlPayload.CODEC, dev.logos.net.ColossusControlPayload::handle);
        registrar.playToClient(dev.logos.net.AnchorsPayload.TYPE, dev.logos.net.AnchorsPayload.CODEC);
        registrar.playToServer(dev.logos.net.AnchorEditPayload.TYPE, dev.logos.net.AnchorEditPayload.CODEC, dev.logos.net.AnchorEditPayload::handle);
    }

    /** The client keeps a copy of its anchors (editor list, markers); resend whenever the player is (re)created. */
    @SubscribeEvent
    public void onLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) dev.logos.runtime.Anchors.sync(sp);
    }

    @SubscribeEvent
    public void onRespawn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) dev.logos.runtime.Anchors.sync(sp);
    }

    @SubscribeEvent
    public void onChangeDimension(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) dev.logos.runtime.Anchors.sync(sp);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        LogosCommands.register(event.getDispatcher());
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
        m.mana = Math.min(m.maxMana, m.mana + Limits.MANA_REGEN_PER_TICK);
        long now = sp.level().getGameTime();
        if (now % 10 != 0 || now < m.hudQuietUntil) return;
        ItemStack wand = dev.logos.item.SpellBag.heldWand(sp);
        if (wand == null) return;
        int filled = (int) Math.round(10 * m.mana / Math.max(1, m.maxMana));
        Component hud = Component.literal("☿ ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("█".repeat(filled)).withStyle(ChatFormatting.BLUE))
                .append(Component.literal("█".repeat(10 - filled)).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(" " + (int) m.mana + "/" + (int) m.maxMana).withStyle(ChatFormatting.AQUA));
        var selected = dev.logos.item.SpellBag.selected(sp, wand);
        if (selected != null) {
            Spell s = selected.spell();
            var in = Casting.injection(s, selected.tier(), Casting.immolation(sp, wand));
            ChatFormatting c = m.mana >= in.mana() ? ChatFormatting.GRAY : ChatFormatting.RED;
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
        if (Effects.inSpell() || dead.level().isClientSide()) return;
        if (event.getSource().getEntity() instanceof ServerPlayer p && p != dead) {
            var at = Targeting.center(dead);
            DEFERRED.add(() -> Casting.firePassives(p, "kill", dead, at));
        }
    }
}

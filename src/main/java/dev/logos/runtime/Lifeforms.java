package dev.logos.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * Mana lifeforms: their summoning (level, mana, the per-player limit), the registry that keeps that
 * limit across unloaded chunks, their core panel, and the events that wake their passive pages.
 * <p>
 * Passive pages read their triggers from the lifeform's side: {@code hurt} when it is hurt,
 * {@code attack} when its master strikes something, {@code kill} when what it was fighting dies.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Lifeforms {
    public static final Identifier CORE_UI = Identifier.fromNamespaceAndPath(LogosMod.MODID, "lifeform_core");

    /** A {@code logos:mana_fuel} data map entry: how much mana one of this item gives a lifeform. */
    public record ManaFuel(float mana) {
        public static final Codec<ManaFuel> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.floatRange(0, Float.MAX_VALUE).fieldOf("mana").forGetter(ManaFuel::mana)).apply(i, ManaFuel::new));
    }

    /** Which lifeforms each player has, oldest first: kept with the world, so the limit holds while they lie in unloaded chunks. */
    static final class Registry extends SavedData {
        static final Codec<Registry> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, UUIDUtil.CODEC.listOf()).xmap(m -> {
            Registry r = new Registry();
            m.forEach((owner, list) -> r.owned.put(owner, new ArrayList<>(list)));
            return r;
        }, r -> r.owned);
        static final SavedDataType<Registry> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "lifeforms"), Registry::new, CODEC);

        final Map<UUID, List<UUID>> owned = new HashMap<>();

        List<UUID> of(UUID owner) {
            return owned.computeIfAbsent(owner, k -> new ArrayList<>());
        }
    }

    /** Which lifeform's core each player is opening; set on both sides just before the panel opens. */
    private static final Map<UUID, Integer> OPENING = new HashMap<>();

    private Lifeforms() {}

    private static Registry registry(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(Registry.TYPE);
    }

    /** Whether {@code lf} is still one of its master's lifeforms (one unmade while it lay unloaded is not). */
    static boolean registered(ManaLifeform lf) {
        UUID master = Familiars.masterOf(lf);
        return master != null && lf.level().getServer() != null && registry(lf.level().getServer()).of(master).contains(lf.getUUID());
    }

    /** Gone for good (killed or dismissed, not unloaded): it no longer counts. */
    static void forget(ManaLifeform lf) {
        UUID master = Familiars.masterOf(lf);
        if (master == null || lf.level().getServer() == null) return;
        Registry r = registry(lf.level().getServer());
        if (r.of(master).remove(lf.getUUID())) r.setDirty();
    }

    /**
     * Gathers a level-{@code level} lifeform at {@code at} from the spell's mana: {@code wanted}, or by
     * default all the spell has left, up to its pool; at least {@link ManaLifeform#minMana}. What it
     * took becomes its mana. Past the player's limit the oldest is unmade. Returns null or why not.
     */
    public static @Nullable String summon(SpellInstance inst, ServerPlayer owner, ServerLevel level, Vec3 at, @Nullable String nickname,
            int lifeLevel, @Nullable Double wanted) {
        int limit = LogosConfig.maxLifeforms();
        if (limit <= 0) return "魔力生命体已被配置关闭（maxLifeforms = 0）";
        if (lifeLevel < 1 || lifeLevel > ManaLifeform.MAX_LEVEL) return "魔力生命体的等级只能是 1–" + ManaLifeform.MAX_LEVEL + "，不是 " + lifeLevel;
        double min = ManaLifeform.minMana(lifeLevel), max = ManaLifeform.maxMana(lifeLevel);
        double amount = Math.min(wanted != null ? wanted : inst.mana, max);
        if (amount < min || inst.mana < min) {
            return "魔力不足：凝聚 " + lifeLevel + " 级魔力生命体至少要 " + Math.round(min) + "，术式只剩 " + Math.round(inst.mana)
                    + (wanted != null && wanted < min ? "（:mana 写了 " + Math.round(wanted) + "）" : "");
        }
        ManaLifeform lf = LogosMod.MANA_LIFEFORM.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (lf == null) return "魔力生命体无法生成";
        if (!inst.spend(amount, "凝聚魔力生命体")) return null; // the spell itself reports running dry
        Registry r = registry(level.getServer());
        List<UUID> mine = r.of(owner.getUUID());
        while (mine.size() >= limit) unmake(level.getServer(), owner, mine.removeFirst());
        lf.snapTo(at.x, at.y, at.z, owner.getYRot(), 0);
        lf.setLifeLevel(lifeLevel);
        lf.setMana(amount);
        if (nickname != null && !nickname.isBlank()) {
            lf.setCustomName(Component.literal(nickname.strip()));
            lf.setCustomNameVisible(true);
        }
        Familiars.bind(lf, owner, Long.MAX_VALUE);
        level.addFreshEntity(lf);
        mine.add(lf.getUUID());
        r.setDirty();
        Vfx.burst(level, lf.position().add(0, lf.getBbHeight() / 2, 0), dev.logos.lang.Element.ARCANE, 40);
        Vfx.sound(level, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 1f, 0.8f);
        return null;
    }

    /** The oldest lifeform makes way for a new one: if it is loaded it disperses now, otherwise when it next loads. */
    private static void unmake(MinecraftServer server, ServerPlayer owner, UUID id) {
        for (ServerLevel l : server.getAllLevels()) {
            if (l.getEntity(id) instanceof ManaLifeform old) {
                owner.sendSystemMessage(Component.literal("✦ " + old.getName().getString() + " 消散了，为新的魔力生命体让出位置").withStyle(ChatFormatting.GRAY));
                Familiars.dismiss(old);
                return;
            }
        }
        owner.sendSystemMessage(Component.literal("✦ 远方的魔力生命体将在下次被加载时消散，为新的让出位置").withStyle(ChatFormatting.GRAY));
    }

    /** One that was unmade while unloaded comes back only to drop its pages and disperse. */
    static void onLoaded(ManaLifeform lf) {
        if (registered(lf)) {
            Familiars.rebind(lf);
            return;
        }
        List<ItemStack> pages = Familiars.pickedUp(lf);
        Vec3 at = lf.position();
        if (lf.level() instanceof ServerLevel level) LogosMod.defer(() -> {
            for (ItemStack s : pages) level.addFreshEntity(new ItemEntity(level, at.x, at.y + 0.5, at.z, s));
        });
    }

    // ---- the core panel -------------------------------------------------------------------------

    /** Opens {@code lf}'s core for its master: the client is told which lifeform first, then the panel opens. */
    public static void openCore(ServerPlayer player, ManaLifeform lf) {
        OPENING.put(player.getUUID(), lf.getId());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new dev.logos.net.LifeformCorePayload(lf.getId()));
        PlayerUIMenuType.openUI(player, CORE_UI);
    }

    /** Client side: the server is about to open {@code entityId}'s core. */
    public static void opening(Player player, int entityId) {
        OPENING.put(player.getUUID(), entityId);
    }

    /** Registered at startup: the panel for whichever lifeform the player is opening. */
    public static void registerUI() {
        PlayerUIMenuType.register(CORE_UI, player -> new PlayerUIMenuType.PlayerUIHolder() {
            @Override
            public ModularUI createUI(Player p) {
                Integer id = OPENING.get(p.getUUID());
                return corePanel(p, id == null ? null : p.level().getEntity(id) instanceof ManaLifeform lf ? lf : null);
            }

            @Override
            public boolean isStillValid(Player p) {
                Integer id = OPENING.get(p.getUUID());
                return id != null && p.level().getEntity(id) instanceof ManaLifeform lf && lf.core.stillValid(p);
            }
        });
    }

    private static ModularUI corePanel(Player player, @Nullable ManaLifeform lf) {
        UIElement root = new UIElement().addClass("panel_bg").layout(l -> l.paddingAll(7).gapAll(4));
        if (lf == null) {
            root.addChildren(new Label().setText(Component.literal("（魔力生命体已不在）")), new InventorySlots());
            return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
        }
        int lv = lf.lifeLevel();
        UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(8));
        row.addChildren(
                group(lf, "主动", ManaLifeform.ACTIVE_FIRST, ManaLifeform.activeSlots(lv)),
                group(lf, "被动", ManaLifeform.PASSIVE_FIRST, ManaLifeform.passiveSlots(lv)),
                group(lf, "炼金道具", ManaLifeform.DEVICE_FIRST, ManaLifeform.deviceSlots(lv)));
        Label hint = new Label();
        hint.setText(Component.literal("主动：放主动启式的术式。按意图使用：攻击类对准敌人，较便宜的那张是普攻，"
                + "魔力充足（放完仍剩三成）时较贵的那张一起放出；治疗、守护类用来照顾你。"
                + "被动：受击（hurt）、主人出手（attack）、目标倒下（kill）时触发。施法花的是它自己的魔力。"
                + (ManaLifeform.quick(lv) ? "4 级起它的施法、移动与恢复更快。" : "")));
        hint.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));
        hint.layout(x -> x.width(9 * 19));
        root.addChildren(
                new Label().setText(Component.literal("✦ " + lf.getName().getString() + " 的心核 · " + lv + " 级 · " + lf.role().zh)),
                new Label().setText(Component.literal(lf.temperamentZh())),
                row, hint, new InventorySlots());
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    /** A labelled column of {@code n} core slots from {@code first}; a group with none open is left out. */
    private static UIElement group(ManaLifeform lf, String title, int first, int n) {
        UIElement col = new UIElement().layout(l -> l.gapAll(1));
        if (n <= 0) return col;
        col.addChild(new Label().setText(Component.literal(title)));
        UIElement slots = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(1));
        for (int i = 0; i < n; i++) {
            ItemSlot slot = new ItemSlot();
            slot.bind(lf.core.slot(first + i));
            slots.addChild(slot);
        }
        col.addChild(slots);
        return col;
    }

    // ---- passive pages --------------------------------------------------------------------------

    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        // Its alchemical devices take their share first (a mana ward, say), whatever the damage comes from.
        if (victim instanceof ManaLifeform shielded && !victim.level().isClientSide()) {
            event.setAmount(shielded.shieldedDamage(event.getSource(), event.getAmount()));
        }
        if (Effects.inSpell() || victim.level().isClientSide()) return;
        Entity attacker = event.getSource().getEntity();
        if (victim instanceof ManaLifeform lf && !lf.isDormant()) LogosMod.defer(() -> lf.firePassives("hurt", attacker));
        if (attacker instanceof ServerPlayer master && attacker != victim && !Familiars.friendly(master.getUUID(), victim)) {
            for (Mob m : Familiars.of(master)) {
                if (m instanceof ManaLifeform lf && !lf.isDormant()) LogosMod.defer(() -> lf.firePassives("attack", victim));
            }
        }
    }

    /** What it was fighting has fallen — by its spell or anyone's. */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide()) return;
        for (Mob m : List.copyOf(Familiars.live())) {
            if (m instanceof ManaLifeform lf && !lf.isDormant() && Familiars.orderedTarget(lf) == dead) LogosMod.defer(() -> lf.firePassives("kill", dead));
        }
    }
}

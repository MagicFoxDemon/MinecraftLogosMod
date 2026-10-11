package dev.logos.runtime;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;

import dev.logos.LogosConfig;
import dev.logos.LogosMod;
import dev.logos.runtime.AlchemicalColossus.Hold;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

/**
 * The alchemical colossus and the world: summoning (by command until alchemy builds them), its
 * per-player limit, its device panel, its riders (who cannot slip out of its hand, and are lifted
 * down rather than jumping off its shoulder), drive-mode controls, and the hidden challenge for
 * those who see one go berserk.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class Colossi {
    public static final Identifier DEVICES_UI = Identifier.fromNamespaceAndPath(LogosMod.MODID, "colossus_devices");
    /** The hidden challenge 《这巨像魔了，是个魔像》, for everyone near a colossus when it goes berserk. */
    public static final Identifier FRENZY_ADVANCEMENT = Identifier.fromNamespaceAndPath(LogosMod.MODID, "colossus_frenzy");
    public static final int WITNESS_RANGE = 32;

    /** Drive-mode controls a rider sends. */
    public static final byte TOGGLE_DRIVE = 0, STRIKE = 1, CHARGE = 2, LOOSE = 3;

    /** Entities in a colossus's hand: they cannot dismount by themselves. */
    private static final Set<Entity> HELD = Collections.newSetFromMap(new WeakHashMap<>());
    /** Entities being let go of on purpose: their dismount goes through. */
    private static final Set<Entity> LETTING_GO = Collections.newSetFromMap(new WeakHashMap<>());
    /** Entities struck by the cannon's opening seconds: no knockback. */
    private static final Set<Entity> STEADY = Collections.newSetFromMap(new WeakHashMap<>());

    private Colossi() {}

    /**
     * Raises a colossus for {@code owner} at {@code at}, full to its visible pool, bound to them like a
     * familiar. Past their limit (config maxColossi) the oldest is unmade.
     */
    public static @Nullable String summon(ServerPlayer owner, ServerLevel level, Vec3 at, @Nullable String name) {
        if (LogosConfig.maxColossi() <= 0) return "炼金术巨像已被配置关闭（maxColossi = 0）";
        AlchemicalColossus c = LogosMod.ALCHEMICAL_COLOSSUS.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (c == null) return "炼金术巨像无法生成";
        c.snapTo(at.x, at.y, at.z, owner.getYRot() + 180, 0);
        c.yBodyRot = c.yHeadRot = owner.getYRot() + 180;
        c.setMana(AlchemicalColossus.DISPLAY_CAP);
        if (name != null && !name.isBlank()) {
            c.setCustomName(Component.literal(name.strip()));
            c.setCustomNameVisible(true);
        }
        Familiars.bind(c, owner, Long.MAX_VALUE);
        Lifeforms.enlist(level, owner, c, LogosConfig.maxColossi(), "炼金术巨像");
        level.addFreshEntity(c);
        c.play(AlchemicalColossus.Act.STARTUP);
        Vfx.sound(level, at, SoundEvents.IRON_GOLEM_REPAIR, 1.4f, 0.5f);
        return null;
    }

    // ---- holding --------------------------------------------------------------------------------

    static void holding(Entity e) {
        HELD.add(e);
    }

    static void stopHolding(Entity e) {
        HELD.remove(e);
        LETTING_GO.remove(e);
    }

    /** The next dismount of {@code e} is the colossus letting go: allow it. */
    static void letGo(Entity e) {
        LETTING_GO.add(e);
        HELD.remove(e);
    }

    /** Runs {@code r} with {@code e} immune to knockback. */
    static void noKnockback(Entity e, Runnable r) {
        STEADY.add(e);
        try {
            r.run();
        } finally {
            STEADY.remove(e);
        }
    }

    @SubscribeEvent
    static void onKnockback(LivingKnockBackEvent event) {
        if (STEADY.contains(event.getEntity())) event.setCanceled(true);
    }

    /**
     * Its hand does not open because the held one wriggles; a seated rider who wants down is lifted
     * down. Only on the server, which owns the dismount; the client follows.
     */
    @SubscribeEvent
    static void onDismount(EntityMountEvent event) {
        if (!event.isDismounting() || !(event.getEntityBeingMounted() instanceof AlchemicalColossus c) || c.level().isClientSide()) return;
        Entity rider = event.getEntityMounting();
        if (LETTING_GO.remove(rider) || !c.isAlive()) return;
        Hold h = c.holdOf(rider);
        // A seated rider who wants down is lifted down (a seat counts before any leftover hold).
        if ((h == Hold.SEAT_LEFT || h == Hold.SEAT_RIGHT) && rider instanceof Player p && c.mind() != null) {
            event.setCanceled(true);
            LogosMod.defer(() -> c.mind().requestPutDown(p));
            return;
        }
        if (h == Hold.GRIP || HELD.contains(rider)) event.setCanceled(true);
    }

    /** On its shoulders a rider is half sheltered. */
    @SubscribeEvent
    static void onRiderHurt(LivingIncomingDamageEvent event) {
        // Whatever it holds does not choke on its own fingers.
        if (event.getEntity().getVehicle() instanceof AlchemicalColossus held && held.holdOf(event.getEntity()) == Hold.GRIP
                && event.getSource().is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)) {
            event.setCanceled(true);
            return;
        }
        if (event.getEntity().getVehicle() instanceof AlchemicalColossus c && !c.isBerserk()) {
            Hold h = c.holdOf(event.getEntity());
            if (h == Hold.SEAT_LEFT || h == Hold.SEAT_RIGHT) event.setAmount(event.getAmount() * 0.5f);
        }
    }

    // ---- frenzy ---------------------------------------------------------------------------------

    /** Everyone within {@value #WITNESS_RANGE} blocks when it goes berserk earns the hidden challenge. */
    static void witnessFrenzy(AlchemicalColossus c) {
        if (!(c.level() instanceof ServerLevel level)) return;
        var adv = level.getServer().getAdvancements().get(FRENZY_ADVANCEMENT);
        if (adv == null) return;
        for (ServerPlayer p : level.players()) {
            if (p.distanceTo(c) <= WITNESS_RANGE) p.getAdvancements().award(adv, "witnessed");
        }
    }

    // ---- driving ----------------------------------------------------------------------------------

    /** A control from a rider: only its master, seated on it, is obeyed. */
    public static void control(ServerPlayer p, byte action) {
        if (!(p.getVehicle() instanceof AlchemicalColossus c) || c.mind() == null) return;
        Hold h = c.holdOf(p);
        if ((h != Hold.SEAT_LEFT && h != Hold.SEAT_RIGHT) || !p.getUUID().equals(Familiars.masterOf(c)) || c.isBerserk() || c.isDormant()) return;
        switch (action) {
            case TOGGLE_DRIVE -> {
                c.setDriven(!c.isDriven());
                p.sendOverlayMessage(Component.literal(c.isDriven() ? "⛨ 驾驶模式：移动由你控制，左键挥拳/抓取，按住右键蓄力能量炮" : "⛨ 乘坐模式：巨像自行行动"));
            }
            case STRIKE -> {
                if (c.isDriven()) c.mind().driverStrike(p);
            }
            case CHARGE -> {
                if (c.isDriven()) c.mind().driverChargeStart(p);
            }
            case LOOSE -> {
                if (c.isDriven()) c.mind().driverChargeRelease(p);
            }
            default -> {}
        }
    }

    // ---- the device panel -----------------------------------------------------------------------

    public static void openDevices(ServerPlayer player, AlchemicalColossus c) {
        Lifeforms.opening(player, c.getId());
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new dev.logos.net.LifeformCorePayload(c.getId()));
        PlayerUIMenuType.openUI(player, DEVICES_UI);
    }

    public static void registerUI() {
        PlayerUIMenuType.register(DEVICES_UI, player -> new PlayerUIMenuType.PlayerUIHolder() {
            @Override
            public ModularUI createUI(Player p) {
                Entity e = Lifeforms.openingEntity(p);
                return panel(p, e instanceof AlchemicalColossus c ? c : null);
            }

            @Override
            public boolean isStillValid(Player p) {
                return Lifeforms.openingEntity(p) instanceof AlchemicalColossus c && c.devices.stillValid(p);
            }
        });
    }

    private static ModularUI panel(Player player, @Nullable AlchemicalColossus c) {
        UIElement root = new UIElement().addClass("panel_bg").layout(l -> l.paddingAll(7).gapAll(4));
        if (c == null) {
            root.addChildren(new Label().setText(Component.literal("（巨像已不在）")), new InventorySlots());
        } else {
            UIElement row = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(1));
            for (int i = 0; i < c.deviceSlots(); i++) {
                ItemSlot slot = new ItemSlot();
                slot.bind(c.deviceSlot(i));
                row.addChild(slot);
            }
            Label hint = new Label();
            hint.setText(Component.literal("炼金术道具：装在巨像身上持续生效（如缓慢回魔、魔力护盾）。"
                    + (c.isTempered() ? "历经暴走后多出两个槽位。" : "")));
            hint.textStyle(s -> s.textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.WRAP).adaptiveHeight(true));
            hint.layout(x -> x.width(9 * 19));
            root.addChildren(new Label().setText(c.status()), new Label().setText(Component.literal("炼金术道具")), row, hint, new InventorySlots());
        }
        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }
}

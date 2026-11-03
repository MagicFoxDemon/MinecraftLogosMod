package dev.logos.client;

import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import dev.logos.runtime.Incarnation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * How an incarnated player looks (see {@link Incarnation}). This client keeps a shell of each such
 * player's form, never added to the world, and moves, turns and animates it as the player every
 * tick; where the level would draw the player it draws the shell. Its own renderer does the rest,
 * so another mod's creature looks as that mod draws it: walking from how far it moved, a blow
 * from the player's swing, the red flash from their hurt. A form with hands holds and wears what
 * the player does. Without hands, the first-person hand is not drawn.
 */
public final class IncarnationView {
    private static final Map<Player, Mob> SHELLS = new WeakHashMap<>();
    private static final EquipmentSlot[] WORN = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private IncarnationView() {}

    /** The shell to draw in place of {@code e}, if it is an incarnated player this client follows. */
    public static @Nullable Mob shell(Entity e) {
        return e instanceof Player p && Incarnation.active(p) ? SHELLS.get(p) : null;
    }

    /** After the level has ticked: every incarnated player this client sees gets a shell of its form, where and as they are. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            SHELLS.clear();
            return;
        }
        SHELLS.keySet().removeIf(p -> p.isRemoved() || p.level() != mc.level || !Incarnation.active(p));
        for (Player p : mc.level.players()) {
            Incarnation.Form f = Incarnation.form(p);
            EntityType<?> type = Incarnation.formType(p);
            if (f == null || type == null) continue;
            Mob shell = SHELLS.get(p);
            if (shell == null || shell.getType() != type) {
                if (!(type.create(mc.level, EntitySpawnReason.LOAD) instanceof Mob m)) continue;
                shell = m;
                shell.setSilent(true);
                shell.setPos(p.position());
                SHELLS.put(p, shell);
            }
            follow(shell, p, f);
        }
    }

    private static void follow(Mob shell, Player p, Incarnation.Form f) {
        shell.xo = p.xo;
        shell.yo = p.yo;
        shell.zo = p.zo;
        shell.xOld = p.xOld;
        shell.yOld = p.yOld;
        shell.zOld = p.zOld;
        shell.setPos(p.getX(), p.getY(), p.getZ());
        shell.setYRot(p.getYRot());
        shell.yRotO = p.yRotO;
        shell.setXRot(p.getXRot());
        shell.xRotO = p.xRotO;
        shell.yBodyRot = p.yBodyRot;
        shell.yBodyRotO = p.yBodyRotO;
        shell.yHeadRot = p.yHeadRot;
        shell.yHeadRotO = p.yHeadRotO;
        shell.calculateEntityAnimation(f.flies() && !p.onGround());
        shell.tickCount = p.tickCount;
        shell.setOnGround(p.onGround());
        shell.setShiftKeyDown(p.isShiftKeyDown());
        shell.setSprinting(p.isSprinting());
        shell.setInvisible(p.isInvisible());
        shell.setRemainingFireTicks(p.getRemainingFireTicks());
        shell.swinging = p.swinging;
        shell.swingTime = p.swingTime;
        shell.swingingArm = p.swingingArm;
        shell.oAttackAnim = p.oAttackAnim;
        shell.attackAnim = p.attackAnim;
        shell.hurtTime = p.hurtTime;
        shell.hurtDuration = p.hurtDuration;
        shell.deathTime = p.deathTime;
        for (EquipmentSlot slot : WORN) {
            ItemStack want = f.hands() ? p.getItemBySlot(slot) : ItemStack.EMPTY;
            if (!ItemStack.matches(shell.getItemBySlot(slot), want)) shell.setItemSlot(slot, want.copy());
        }
    }

    /** Without hands there is no hand to see. */
    public static void onRenderHand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        Incarnation.Form f = Incarnation.form(Minecraft.getInstance().player);
        if (f != null && !f.hands()) event.setCanceled(true);
    }
}

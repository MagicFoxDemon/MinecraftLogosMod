package dev.logos.runtime;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Riding familiars. Familiars of the {@code #logos:rideable_familiars} entity tag (the ender
 * dragon, warden, iron golem, hoglin, zoglin and villager by default; packs may add more) carry
 * their master when the master right-clicks them. Ground mounts are steered like a horse — the
 * master's own client moves them — and the dragon is flown by its master's gaze (see
 * {@link DragonFamiliar}), with a second seat behind them for one more player.
 */
@EventBusSubscriber(modid = LogosMod.MODID)
public final class FamiliarRiding {
    public static final TagKey<EntityType<?>> RIDEABLE = TagKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(LogosMod.MODID, "rideable_familiars"));

    private FamiliarRiding() {}

    public static boolean rideable(@Nullable Entity e) {
        return e != null && Familiars.isFamiliar(e) && e.getType().builtInRegistryHolder().is(RIDEABLE);
    }

    /** Seats: the dragon carries its master and one more behind them; the rest carry their master. */
    public static int seats(Entity vehicle) {
        return vehicle instanceof EnderDragon ? 2 : 1;
    }

    public static boolean isMaster(Entity familiar, Entity who) {
        var b = Familiars.bondOf(familiar);
        return b != null && b.owner().equals(who.getUUID());
    }

    /** Who may climb on: the master, onto an empty mount; anyone else only into the dragon's back seat behind the master. */
    public static boolean mayMount(Mob mount, Player p) {
        if (!rideable(mount) || p.isPassenger() || mount.getPassengers().size() >= seats(mount)) return false;
        if (isMaster(mount, p)) return mount.getPassengers().isEmpty();
        return mount instanceof EnderDragon && mount.getFirstPassenger() != null && isMaster(mount, mount.getFirstPassenger());
    }

    /** The master steering a ground mount (the dragon is flown on the server instead), or null. */
    public static @Nullable Player controller(Entity mount) {
        // A colossus is driven by its master from a shoulder, in drive mode only.
        if (mount instanceof AlchemicalColossus c) return c.driver();
        if (mount instanceof EnderDragon || !rideable(mount)) return null;
        return mount.getFirstPassenger() instanceof Player p && isMaster(mount, p) ? p : null;
    }

    /** Like a horse: strafing is slower, backing up much slower. A colossus stands still through a blow or a shot. */
    public static Vec3 riddenInput(LivingEntity mount, Player rider) {
        if (mount instanceof AlchemicalColossus c && c.act() != AlchemicalColossus.Act.NONE) return Vec3.ZERO;
        return new Vec3(rider.xxa * 0.5, 0, rider.zza < 0 ? rider.zza * 0.25 : rider.zza);
    }

    public static float riddenSpeed(LivingEntity mount) {
        if (mount instanceof AlchemicalColossus) return AlchemicalColossus.DRIVE_SPEED;
        return (float) mount.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    /** The mount faces where its rider looks. */
    public static void tickRidden(LivingEntity mount, Player rider) {
        mount.setYRot(rider.getYRot());
        mount.yRotO = mount.getYRot();
        mount.setXRot(rider.getXRot() * 0.5f);
        mount.yBodyRot = mount.getYRot();
        mount.yHeadRot = mount.getYRot();
    }

    /** Where a dragon's riders sit: on its back, the master forward and their companion behind. */
    public static Vec3 dragonSeat(EnderDragon dragon, int seat) {
        float yaw = dragon.getYRot() * (float) (Math.PI / 180);
        // The dragon flies along (sin yaw, -cos yaw); its riders sit along that line. Its body part rides half a
        // block ahead of its centre (computed from the dragon itself: parts lag a tick behind, and sit at 0,0,0 before its first).
        Vec3 forward = new Vec3(Math.sin(yaw), 0, -Math.cos(yaw));
        Vec3 back = dragon.position().add(forward.scale(0.5));
        return back.add(forward.scale(seat == 0 ? 1.0 : -0.6)).add(0, 2.4, 0);
    }

    @SubscribeEvent
    static void onInteract(PlayerInteractEvent.EntityInteract event) {
        Entity target = event.getTarget() instanceof EnderDragonPart part ? part.parentMob : event.getTarget();
        if (!(target instanceof Mob mount) || !rideable(mount)) return;
        Player p = event.getEntity();
        if (!mayMount(mount, p)) {
            // A master's villager is a mount, not a shop.
            if (isMaster(mount, p)) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
            return;
        }
        if (!p.level().isClientSide()) p.startRiding(mount, false, true);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /** Stepping off a dragon in mid-air: a gentle fall. */
    @SubscribeEvent
    static void onDismount(EntityMountEvent event) {
        if (event.isDismounting() && event.getEntityBeingMounted() instanceof EnderDragon d && Familiars.isFamiliar(d)
                && event.getEntityMounting() instanceof LivingEntity rider && !rider.level().isClientSide()) {
            rider.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0, false, false, true));
        }
    }
}

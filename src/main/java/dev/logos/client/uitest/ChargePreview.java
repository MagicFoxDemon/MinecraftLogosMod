package dev.logos.client.uitest;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.client.ClientCharges;
import dev.logos.lang.Element;
import dev.logos.net.ChargePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Plays a charge on this client as the server would report it, at a pace a client scene chooses: an
 * update every other tick while it fills over {@code chargeTicks}, then held for {@code holdTicks}, then
 * released into its spell, or fizzled once it reaches {@code stopAt}. Nothing is cast and no mana moves;
 * only {@link ClientCharges} (and through it the crosshair ring and the charge visuals) sees it. The
 * local player's own charge also raises the held wand as a real one does, on this client only.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ChargePreview {
    private record Plan(int entityId, Element element, int tier, int chargeTicks, int holdTicks, float stopAt) {}

    private static @Nullable Plan plan;
    private static int t;

    private ChargePreview() {}

    /** Fills over {@code chargeTicks}, holds full for {@code holdTicks}, then lets go into the spell. */
    public static void release(int entityId, Element element, int tier, int chargeTicks, int holdTicks) {
        start(new Plan(entityId, element, tier, Math.max(1, chargeTicks), holdTicks, 2));
    }

    /** Fills over {@code chargeTicks} but is dropped once {@code stopAt} of it is in. */
    public static void fizzle(int entityId, Element element, int tier, int chargeTicks, float stopAt) {
        start(new Plan(entityId, element, tier, Math.max(1, chargeTicks), 0, stopAt));
    }

    /** Stops sending without ending the charge, as if the caster went out of range. */
    public static void abandon() {
        plan = null;
        lower();
    }

    /** Whether a charge is still being played. */
    public static boolean busy() {
        return plan != null;
    }

    private static void start(Plan p) {
        plan = p;
        t = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getId() == p.entityId()) {
            // Held down too, or the client lets go of an item in use as soon as the key is up.
            mc.player.startUsingItem(InteractionHand.MAIN_HAND);
            mc.options.keyUse.setDown(true);
        }
        send(p, 0, false);
    }

    /** Lowers the local player's wand once its charge is over. */
    private static void lower() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.isUsingItem()) mc.player.stopUsingItem();
        mc.options.keyUse.setDown(false);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Plan p = plan;
        Minecraft mc = Minecraft.getInstance();
        if (p == null || mc.isPaused()) return;
        if (mc.level == null) {
            plan = null;
            return;
        }
        t++;
        float progress = Math.min(1, t / (float) p.chargeTicks());
        if (progress >= p.stopAt()) {
            ClientCharges.receive(ChargePayload.ended(p.entityId()));
            plan = null;
            lower();
        } else if (t > p.chargeTicks() + p.holdTicks()) {
            ClientCharges.receive(ChargePayload.released(p.entityId(), p.element().ordinal(), p.tier()));
            plan = null;
            lower();
        } else if (t % 2 == 0 || t == p.chargeTicks()) {
            send(p, progress, t >= p.chargeTicks());
        }
    }

    private static void send(Plan p, float progress, boolean full) {
        ClientCharges.receive(new ChargePayload(p.entityId(), p.element().ordinal(), p.tier(), progress, full));
    }
}

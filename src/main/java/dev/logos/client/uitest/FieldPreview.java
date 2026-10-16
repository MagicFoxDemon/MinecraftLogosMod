package dev.logos.client.uitest;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.client.ClientCharges;
import dev.logos.client.LogosFx;
import dev.logos.lang.Element;
import dev.logos.net.FxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Plays a field on this client as the server would report it ({@code FIELD_*} packets through
 * {@link LogosFx#handle}), at a pace a client scene chooses: reminded every 4 ticks, a pulse every
 * {@code every} ticks, and its end once {@code life} has run out. Nothing is cast; only the field
 * visuals see it. Its clock stops while {@link ClientCharges#freeze} holds a screenshot still.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class FieldPreview {
    private record Plan(int id, Element element, Vec3 centre, float radius, int life, int every, int follow) {}

    /** Ids well above any the server hands out in a scene. */
    private static int nextId = 900_000;
    private static @Nullable Plan plan;
    private static int t;

    private FieldPreview() {}

    /** Starts a field of {@code element} at {@code centre} lasting {@code life} ticks and pulsing every {@code every}; {@code follow} is a network id or -1. */
    public static int start(Element element, Vec3 centre, float radius, int life, int every, int follow) {
        plan = new Plan(nextId++, element, centre, radius, life, every, follow);
        t = 0;
        return plan.id();
    }

    /** Someone entered the field being played, at {@code at}. */
    public static void enter(Vec3 at, int entityId) {
        Plan p = plan;
        if (p != null) send(p, FxPayload.Kind.FIELD_ENTER, at, 1, Vec3.ZERO, entityId);
    }

    /** Stops sending without ending the field, as if it went out of range. */
    public static void abandon() {
        plan = null;
    }

    /** Client ticks since the last field was started, still counting once it has ended. */
    public static int elapsed() {
        return t;
    }

    public static boolean busy() {
        return plan != null;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused() || ClientCharges.frozen()) return;
        t++;
        Plan p = plan;
        if (p == null) return;
        if (mc.level == null) {
            plan = null;
            return;
        }
        if (t > p.life()) {
            send(p, FxPayload.Kind.FIELD_END, p.centre(), 1, Vec3.ZERO, -1);
            plan = null;
            return;
        }
        if (t == 1 || t % 4 == 0) send(p, FxPayload.Kind.FIELD_HOLD, p.centre(), p.radius(), new Vec3(t, p.life(), p.every()), p.follow());
        if (t % p.every() == 0) send(p, FxPayload.Kind.FIELD_PULSE, p.centre(), p.radius(), Vec3.ZERO, -1);
    }

    private static void send(Plan p, FxPayload.Kind kind, Vec3 at, float size, Vec3 aux, int look) {
        LogosFx.handle(new FxPayload((byte) kind.ordinal(), (byte) p.element().ordinal(), (byte) 0, (float) at.x, (float) at.y, (float) at.z,
                (float) aux.x, (float) aux.y, (float) aux.z, size, p.id(), look, 0, -1));
    }
}

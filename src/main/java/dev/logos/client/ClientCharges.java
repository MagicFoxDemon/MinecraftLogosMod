package dev.logos.client;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.net.ChargePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Wand charges the server has told this client about, by entity id: the crosshair ring and the charging effects read them. */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class ClientCharges {
    /** Updates come every other tick; a charge not heard of for this long is taken as over. */
    private static final int STALE_TICKS = 40;

    /** One entity's charge. Progress is eased between updates from {@code from} to {@code progress}. */
    public static final class State {
        public final Element element;
        public final int tier;
        float from, progress;
        public boolean full;
        long updated;
        /** Client tick at which it became full, for the completion flash; -1 while charging. */
        public long fullSince = -1;

        State(Element element, int tier) {
            this.element = element;
            this.tier = tier;
        }

        /** Injected share at this moment, eased over the two ticks between server updates. */
        public float progress(float partialTick) {
            if (full) return 1;
            float t = Math.min(1, (ticks - updated + partialTick) / 2f);
            return from + (progress - from) * t;
        }
    }

    private static final Map<Integer, State> CHARGES = new HashMap<>();
    private static long ticks;

    private ClientCharges() {}

    static void receive(ChargePayload p) {
        if (p.ended()) {
            CHARGES.remove(p.entityId());
            return;
        }
        Element element = Element.values()[Math.floorMod(p.element(), Element.values().length)];
        State s = CHARGES.get(p.entityId());
        if (s == null || s.element != element || s.tier != p.tier()) {
            s = new State(element, p.tier());
            s.progress = p.progress();
            CHARGES.put(p.entityId(), s);
        }
        s.from = s.progress(0);
        s.progress = p.progress();
        if (p.full() && !s.full) s.fullSince = ticks;
        s.full = p.full();
        s.updated = ticks;
    }

    public static @Nullable State of(@Nullable Entity entity) {
        return entity == null ? null : CHARGES.get(entity.getId());
    }

    public static Map<Integer, State> all() {
        return CHARGES;
    }

    public static long ticks() {
        return ticks;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        ticks++;
        var level = Minecraft.getInstance().level;
        if (level == null) {
            CHARGES.clear();
            return;
        }
        CHARGES.entrySet().removeIf(e -> ticks - e.getValue().updated > STALE_TICKS || level.getEntity(e.getKey()) == null);
    }

    /** The element's colour (opaque RGB): the same palette its projectiles, rays and impacts are drawn in. */
    public static int color(Element e) {
        return LogosFx.coreColor(e);
    }
}

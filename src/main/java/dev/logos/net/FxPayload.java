package dev.logos.net;

import dev.logos.LogosMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client: play a spell visual. The server only says <em>what</em> happened (kind, element,
 * where, how big); the client derives the actual Photon effect from that, so visuals stay a pure
 * function of the program.
 *
 * @param kind    see {@link Kind}
 * @param element {@link dev.logos.lang.Element} ordinal
 * @param variant kind-specific detail, e.g. the motion ordinal for trails
 * @param ax      second point / direction, depending on kind (beam end, trail heading…)
 * @param size    radius or scale
 * @param id      stable id for persistent effects (projectile trails), else 0
 * @param look    trails: raw item registry id of the model the body wears; beams: the anchor entity's network id; -1 for none
 * @param spin    trails only: tumble of that model in turns per second
 * @param particle raw particle type id: the trail particle replacing the element trail, or what a phantasm shows; -1 for none
 */
public record FxPayload(byte kind, byte element, byte variant, float x, float y, float z, float ax, float ay, float az, float size, int id,
        int look, float spin, int particle) implements CustomPacketPayload {
    public enum Kind {
        /** A moving spell body: created on first sight, moved by later packets with the same id. */
        TRAIL,
        /** The spell body ceased to exist. */
        TRAIL_END,
        /**
         * Something took effect: {@code variant} is the {@link dev.logos.runtime.Vfx.Burst} ordinal. Apart from
         * {@code GENERIC}, (ax,ay,az) is the direction it came from (or zero) and {@code size} the target's scale.
         */
        BURST,
        BEAM,
        RING,
        PULSE,
        CAST,
        EXECUTE,
        MARK,
        /** {@code phantasm}: a registered particle; {@code variant} is the count, {@code size} the spread. */
        PHANTASM,
        /**
         * A lasting beam, re-sent every tick under {@code id}: from (x,y,z) to (ax,ay,az), radius
         * {@code size}; {@code look} carries the network id of the entity it is anchored to, or -1.
         */
        BEAM_HOLD,
        /** That beam ceased to exist. */
        BEAM_END,
        /** An actual projectile collision; aux is the outward direction, size the projectile radius. */
        PROJECTILE_IMPACT,
        /**
         * Sent once as a spell body {@code id} sets out from its caster's eyes (x,y,z): {@code look} is the
         * caster's network id, so clients draw the body from the caster's cast point at first.
         */
        TRAIL_FROM;

        public static final Kind[] VALUES = values();
    }

    public static final Type<FxPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "fx"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FxPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeByte(p.kind);
                buf.writeByte(p.element);
                buf.writeByte(p.variant);
                buf.writeFloat(p.x);
                buf.writeFloat(p.y);
                buf.writeFloat(p.z);
                buf.writeFloat(p.ax);
                buf.writeFloat(p.ay);
                buf.writeFloat(p.az);
                buf.writeFloat(p.size);
                buf.writeVarInt(p.id);
                // +1 so "none" (-1) costs a single byte.
                buf.writeVarInt(p.look + 1);
                buf.writeFloat(p.spin);
                buf.writeVarInt(p.particle + 1);
            },
            buf -> new FxPayload(buf.readByte(), buf.readByte(), buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readVarInt() - 1, buf.readFloat(), buf.readVarInt() - 1));

    public Kind kindEnum() {
        return Kind.VALUES[Math.floorMod(kind, Kind.VALUES.length)];
    }

    @Override
    public Type<FxPayload> type() {
        return TYPE;
    }
}

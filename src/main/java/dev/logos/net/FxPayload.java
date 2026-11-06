package dev.logos.net;

import java.util.ArrayList;
import java.util.List;

import dev.logos.LogosMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One spell visual to play, sent in a {@link Batch}. The server only says <em>what</em> happened (kind, element,
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
        int look, float spin, int particle) {
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
        /**
         * A ray or a link, at once: from (x,y,z) to (ax,ay,az); {@code variant} is the
         * {@link dev.logos.runtime.Vfx.RayEnd} ordinal, {@code look} the caster whose cast point it leaves from, or -1.
         */
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
        TRAIL_FROM,
        /**
         * A field ({@code field}), re-sent every few ticks under {@code id} while it lasts: centre (x,y,z),
         * radius {@code size}, (ax,ay,az) = (ticks it has lasted, its whole life in ticks, ticks between
         * pulses); {@code look} is the network id of the caster it follows ({@code :follow}), or -1.
         */
        FIELD_HOLD,
        /** That field pulsed ({@code on-tick}): centre (x,y,z), radius {@code size}. */
        FIELD_PULSE,
        /** A body entered that field ({@code on-enter}): (x,y,z) is its centre, {@code look} its network id. */
        FIELD_ENTER,
        /** That field ceased to exist, at (x,y,z). */
        FIELD_END,
        /**
         * One head of a vein ({@code vein}), re-sent every tick under {@code id} while it crawls: {@code variant} is which
         * head (a ring has many), (x,y,z) where it stands on the ground, (ax,ay,az) its heading along it, {@code size} its
         * width, {@code spin} its height and {@code look} the face of the blocks it is on (a {@code Direction}'s 3D data
         * value; up, the top of the ground, unless it climbs) and {@code particle} how many ticks what it crawls over stays
         * (0, or none before {@code :linger}: gone as it goes).
         */
        VEIN_HOLD,
        /** That head crawls no further, at (x,y,z); {@code size} is 1 if it struck something or a wall, else 0. */
        VEIN_END,
        /** Vein {@code id} has gone, what it left behind too: drop every head of it now. */
        VEIN_FADE;

        public static final Kind[] VALUES = values();

        /** An update of something lasting, repeated under one id: a newer one says all an older one did. */
        public boolean ongoing() {
            return this == TRAIL || this == BEAM_HOLD || this == FIELD_HOLD || this == VEIN_HOLD;
        }
    }

    public Kind kindEnum() {
        return Kind.VALUES[Math.floorMod(kind, Kind.VALUES.length)];
    }

    /**
     * Server → client: all one player is shown in one tick, in order, as one packet. Each entry writes only what differs
     * from the one before it, since the bodies of a spell share most of theirs.
     */
    public record Batch(List<FxPayload> effects) implements CustomPacketPayload {
        public static final Type<Batch> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "fx_batch"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Batch> CODEC = StreamCodec.of(Batch::write, Batch::read);

        private static final int SAME_KIND = 1, SAME_VARIANT = 2, SAME_SIZE = 4, NO_AUX = 8, SAME_GUISE = 16;
        /** What the first entry is compared with. */
        private static final FxPayload NOTHING = new FxPayload((byte) 0, (byte) 0, (byte) 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, -1);

        private static void write(RegistryFriendlyByteBuf buf, Batch batch) {
            buf.writeVarInt(batch.effects.size());
            FxPayload last = NOTHING;
            for (FxPayload p : batch.effects) {
                boolean sameKind = p.kind == last.kind && p.element == last.element, sameVariant = p.variant == last.variant;
                boolean sameSize = Float.compare(p.size, last.size) == 0, noAux = p.ax == 0 && p.ay == 0 && p.az == 0;
                boolean sameGuise = p.look == last.look && Float.compare(p.spin, last.spin) == 0 && p.particle == last.particle;
                buf.writeByte((sameKind ? SAME_KIND : 0) | (sameVariant ? SAME_VARIANT : 0) | (sameSize ? SAME_SIZE : 0) | (noAux ? NO_AUX : 0) | (sameGuise ? SAME_GUISE : 0));
                if (!sameKind) {
                    buf.writeByte(p.kind);
                    buf.writeByte(p.element);
                }
                if (!sameVariant) buf.writeByte(p.variant);
                buf.writeFloat(p.x);
                buf.writeFloat(p.y);
                buf.writeFloat(p.z);
                if (!noAux) {
                    buf.writeFloat(p.ax);
                    buf.writeFloat(p.ay);
                    buf.writeFloat(p.az);
                }
                if (!sameSize) buf.writeFloat(p.size);
                // Ids run in sequence within a spell: the step (zigzag, it can go down) stays one byte however large they get.
                int step = p.id - last.id;
                buf.writeVarInt(step << 1 ^ step >> 31);
                if (!sameGuise) {
                    // +1 so "none" (-1) costs a single byte.
                    buf.writeVarInt(p.look + 1);
                    buf.writeFloat(p.spin);
                    buf.writeVarInt(p.particle + 1);
                }
                last = p;
            }
        }

        private static Batch read(RegistryFriendlyByteBuf buf) {
            int count = buf.readVarInt();
            List<FxPayload> effects = new ArrayList<>(Math.min(count, buf.readableBytes()));
            FxPayload last = NOTHING;
            for (int i = 0; i < count; i++) {
                int flags = buf.readByte();
                byte kind = last.kind, element = last.element;
                if ((flags & SAME_KIND) == 0) {
                    kind = buf.readByte();
                    element = buf.readByte();
                }
                byte variant = (flags & SAME_VARIANT) != 0 ? last.variant : buf.readByte();
                float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
                float ax = 0, ay = 0, az = 0;
                if ((flags & NO_AUX) == 0) {
                    ax = buf.readFloat();
                    ay = buf.readFloat();
                    az = buf.readFloat();
                }
                float size = (flags & SAME_SIZE) != 0 ? last.size : buf.readFloat();
                int step = buf.readVarInt();
                int id = last.id + (step >>> 1 ^ -(step & 1));
                int look = last.look, particle = last.particle;
                float spin = last.spin;
                if ((flags & SAME_GUISE) == 0) {
                    look = buf.readVarInt() - 1;
                    spin = buf.readFloat();
                    particle = buf.readVarInt() - 1;
                }
                last = new FxPayload(kind, element, variant, x, y, z, ax, ay, az, size, id, look, spin, particle);
                effects.add(last);
            }
            return new Batch(effects);
        }

        @Override
        public Type<Batch> type() {
            return TYPE;
        }
    }
}

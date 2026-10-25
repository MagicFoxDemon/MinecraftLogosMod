package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.sage.SageAgent;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server → client: progress on question {@code id}, or its answer. While working, {@code key} is a
 * {@code logos.sage.status.*} suffix and {@code arg} its argument; once {@code done}, {@code key} is
 * an error key (empty when the sage answered), {@code arg} the detail, and {@code text} and {@code source} the answer.
 */
public record SageReplyPayload(int id, boolean done, String key, String arg, String text, String source) implements CustomPacketPayload {
    public static final int MAX_TEXT = 16_000, MAX_ARG = 1_024;

    public static final Type<SageReplyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "sage_reply"));
    public static final StreamCodec<FriendlyByteBuf, SageReplyPayload> CODEC = CustomPacketPayload.codec(SageReplyPayload::write,
            b -> new SageReplyPayload(b.readVarInt(), b.readBoolean(), b.readUtf(64), b.readUtf(MAX_ARG), b.readUtf(MAX_TEXT), b.readUtf(SageAgent.MAX_SOURCE)));

    public static SageReplyPayload status(int id, String key, String arg) {
        return new SageReplyPayload(id, false, key, cut(arg, MAX_ARG), "", "");
    }

    public static SageReplyPayload of(int id, SageAgent.Outcome o) {
        return new SageReplyPayload(id, true, o.error(), cut(o.detail(), MAX_ARG), cut(o.text(), MAX_TEXT), cut(o.source(), SageAgent.MAX_SOURCE));
    }

    private static String cut(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
    }

    public SageAgent.Outcome outcome() {
        return new SageAgent.Outcome(text, source, key, arg);
    }

    private void write(FriendlyByteBuf b) {
        b.writeVarInt(id);
        b.writeBoolean(done);
        b.writeUtf(key, 64);
        b.writeUtf(arg, MAX_ARG);
        b.writeUtf(text, MAX_TEXT);
        b.writeUtf(source, SageAgent.MAX_SOURCE);
    }

    @Override
    public Type<SageReplyPayload> type() {
        return TYPE;
    }
}

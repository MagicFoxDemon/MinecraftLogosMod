package dev.logos.net;

import dev.logos.LogosMod;
import dev.logos.runtime.Incarnation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: an incarnated player's use button as their form's skill, at the entity they aim at (or -1). */
public record FormSkillPayload(int target) implements CustomPacketPayload {
    public static final Type<FormSkillPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "form_skill"));
    public static final StreamCodec<ByteBuf, FormSkillPayload> CODEC = ByteBufCodecs.VAR_INT.map(FormSkillPayload::new, FormSkillPayload::target);

    @Override
    public Type<FormSkillPayload> type() {
        return TYPE;
    }

    public static void handle(FormSkillPayload p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer sp) Incarnation.skill(sp, p.target < 0 ? null : sp.level().getEntity(p.target));
    }
}

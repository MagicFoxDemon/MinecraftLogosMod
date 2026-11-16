package dev.logos.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registering the mod's payloads, and sending them to clients in one place so that the channel check is never left out. */
public final class Net {
    private Net() {}

    /** Registers every payload of the mod; {@code LogosMod}'s constructor adds this to the mod bus. */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(InscribePayload.TYPE, InscribePayload.CODEC, InscribePayload::handle);
        registrar.playToServer(EmitterEditPayload.TYPE, EmitterEditPayload.CODEC, EmitterEditPayload::handle);
        registrar.playToClient(FxPayload.Batch.TYPE, FxPayload.Batch.CODEC);
        registrar.playToClient(LifeformCorePayload.TYPE, LifeformCorePayload.CODEC);
        registrar.playToServer(SelectSpellPayload.TYPE, SelectSpellPayload.CODEC, SelectSpellPayload::handle);
        registrar.playToServer(StaffFillPayload.TYPE, StaffFillPayload.CODEC, StaffFillPayload::handle);
        registrar.playToServer(WrapSwingPayload.TYPE, WrapSwingPayload.CODEC, WrapSwingPayload::handle);
        registrar.playToServer(VoiceCastPayload.TYPE, VoiceCastPayload.CODEC, VoiceCastPayload::handle);
        registrar.playToServer(ColossusControlPayload.TYPE, ColossusControlPayload.CODEC, ColossusControlPayload::handle);
        registrar.playToServer(DragonControlPayload.TYPE, DragonControlPayload.CODEC, DragonControlPayload::handle);
        registrar.playToServer(PossessControlPayload.TYPE, PossessControlPayload.CODEC, PossessControlPayload::handle);
        registrar.playToServer(RevertPayload.TYPE, RevertPayload.CODEC, RevertPayload::handle);
        registrar.playToServer(FormSkillPayload.TYPE, FormSkillPayload.CODEC, FormSkillPayload::handle);
        registrar.playToClient(AnchorsPayload.TYPE, AnchorsPayload.CODEC);
        registrar.playToClient(ChargePayload.TYPE, ChargePayload.CODEC);
        registrar.playToClient(ManaHudPayload.TYPE, ManaHudPayload.CODEC);
        registrar.playToClient(AreaWeatherPayload.TYPE, AreaWeatherPayload.CODEC);
        registrar.playToClient(AreaWeatherPayload.Moved.TYPE, AreaWeatherPayload.Moved.CODEC);
        registrar.playToClient(ManaFieldPayload.TYPE, ManaFieldPayload.CODEC);
        registrar.playToClient(PillarBurstPayload.TYPE, PillarBurstPayload.CODEC);
        registrar.playToClient(ManaTornadoPayload.TYPE, ManaTornadoPayload.CODEC);
        registrar.playToClient(CastPosePayload.TYPE, CastPosePayload.CODEC);
        registrar.playToServer(AnchorEditPayload.TYPE, AnchorEditPayload.CODEC, AnchorEditPayload::handle);
        registrar.playToServer(AlchemyCardPayload.TYPE, AlchemyCardPayload.CODEC, AlchemyCardPayload::handle);
        registrar.playToServer(SageAskPayload.TYPE, SageAskPayload.CODEC, SageAskPayload::handle);
        registrar.playToServer(SageCancelPayload.TYPE, SageCancelPayload.CODEC, SageCancelPayload::handle);
        registrar.playToServer(SageAdminPayload.TYPE, SageAdminPayload.CODEC, SageAdminPayload::handle);
        registrar.playToClient(SageReplyPayload.TYPE, SageReplyPayload.CODEC);
        registrar.playToClient(SageStatusPayload.TYPE, SageStatusPayload.CODEC);
        registrar.playToClient(SageAdminStatePayload.TYPE, SageAdminStatePayload.CODEC);
    }

    /** To the players of {@code level} within {@code radius} of {@code at} whose connection knows the channel (fake players and modless clients do not). */
    public static void sendNear(ServerLevel level, Vec3 at, double radius, CustomPacketPayload payload) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at) <= radius * radius && p.connection.hasChannel(payload.type())) PacketDistributor.sendToPlayer(p, payload);
        }
    }
}

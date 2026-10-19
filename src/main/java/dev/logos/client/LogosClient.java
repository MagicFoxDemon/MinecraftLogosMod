package dev.logos.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.logos.LogosMod;
import dev.logos.graph.Dimensions;
import dev.logos.net.FxPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** Client-side registrations: effect packets, the spell wheel key, dimension list for the editor. */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class LogosClient {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Identifier.fromNamespaceAndPath(LogosMod.MODID, "logos"));
    public static final KeyMapping SPELL_WHEEL = new KeyMapping("key.logos.spell_wheel", InputConstants.KEY_R, CATEGORY);

    private LogosClient() {}

    @SubscribeEvent
    static void onRegisterPayloads(RegisterClientPayloadHandlersEvent event) {
        event.register(FxPayload.TYPE, (payload, ctx) -> LogosFx.handle(payload));
        event.register(dev.logos.net.AnchorsPayload.TYPE, (payload, ctx) -> ClientAnchors.receive(payload));
        event.register(dev.logos.net.ChargePayload.TYPE, (payload, ctx) -> ClientCharges.receive(payload));
        event.register(dev.logos.net.AreaWeatherPayload.TYPE, (payload, ctx) -> dev.logos.runtime.AreaWeather.receive(payload));
        event.register(dev.logos.net.ManaFieldPayload.TYPE, (payload, ctx) -> dev.logos.runtime.ManaField.receive(ctx.player().level(), payload));
        event.register(dev.logos.net.LifeformCorePayload.TYPE, (payload, ctx) -> dev.logos.runtime.Lifeforms.opening(ctx.player(), payload.entityId()));
    }

    @SubscribeEvent
    static void onRegisterParticles(net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(LogosMod.MANA_MOTE.get(), ManaMote.Provider::new);
    }

    @SubscribeEvent
    static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(SPELL_WHEEL);
        event.register(ColossusInput.MODE);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        ClientAnchors.install();
        // The editor's ◐ dimension dropdown lists whatever dimensions the connected server has.
        var fallback = Dimensions.known;
        Dimensions.known = () -> {
            var conn = Minecraft.getInstance().getConnection();
            if (conn == null) return fallback.get();
            return conn.levels().stream().map(k -> k.identifier().toString()).sorted().toList();
        };
    }

    @SubscribeEvent
    static void onRegisterRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        // Constructs are drawn with the other spell visuals (LogosFx), not by an entity renderer.
        event.registerEntityRenderer(LogosMod.CONSTRUCT.get(), net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(LogosMod.FREE_MANA.get(), FreeManaRenderer::new);
        event.registerEntityRenderer(LogosMod.MANA_LIFEFORM.get(), ManaLifeformRenderer::new);
        event.registerEntityRenderer(LogosMod.ALCHEMICAL_COLOSSUS.get(), ColossusRenderer::new);
        event.registerEntityRenderer(LogosMod.MANA_LEECH.get(), ManaLeechRenderer::new);
        event.registerBlockEntityRenderer(LogosMod.ALCHEMY_TABLE_BE.get(), AlchemyTableRenderer::new);
    }

    @SubscribeEvent
    static void onUse(net.neoforged.neoforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        ConstructPlacement.onUse(event);
        if (!event.isCanceled()) ColossusInput.onInteraction(event);
    }

    @SubscribeEvent
    static void onSubmitGeometry(SubmitCustomGeometryEvent event) {
        LogosFx.submit(event);
    }

    @SubscribeEvent
    static void onClientTickStart(ClientTickEvent.Pre event) {
        ConstructRiding.tick();
        ColossusInput.tick();
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        LogosFx.tick();
        WardSpheres.tick();
        while (SPELL_WHEEL.consumeClick()) SpellWheelScreen.tryOpen();
    }
}

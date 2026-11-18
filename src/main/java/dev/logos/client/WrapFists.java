package dev.logos.client;

import java.util.IdentityHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.client.port.BufferSubmitNodeCollector;
import dev.logos.runtime.ManaWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;

/**
 * A mana wrap on an empty main hand, drawn on the fist: the last five pixels of the arm's cube, handed to
 * {@link SurfaceAuras} as the surface to wrap. In third person on every creature drawn with a humanoid model (players,
 * zombies, skeletons, piglins, armour stands with arms), in first person on one's own arm. Creatures without arms get
 * the extra damage but show nothing.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
final class WrapFists {
    /** How long the fist is, in model pixels from the end of the arm. */
    private static final float FIST = 5;
    private static final Map<ModelPart.Cube, ItemSurface> SURFACES = new IdentityHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private WrapFists() {}

    @EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        @SuppressWarnings({"unchecked", "rawtypes"})
        static void onLayers(EntityRenderersEvent.AddLayers event) {
            for (var type : event.getEntityTypes()) {
                if (event.getRenderer(type) instanceof LivingEntityRenderer r && r.getModel() instanceof HumanoidModel) r.addLayer(new Layer(r));
            }
            for (var skin : event.getSkins()) {
                if (event.getSkin(skin) instanceof LivingEntityRenderer r) r.addLayer(new Layer(r));
            }
        }
    }

    /** One's own empty main hand in first person, posed as PlayerRenderer.renderHand poses it. */
    @SubscribeEvent
    static void onArm(RenderArmEvent event) {
        AbstractClientPlayer player = event.getPlayer();
        ManaWrap w = ManaWrap.of(player);
        if (w == null || event.getArm() != player.getMainArm()) return;
        if (!(Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player) instanceof LivingEntityRenderer<?, ?> renderer)
                || !(renderer.getModel() instanceof HumanoidModel<?> model)) return;
        boolean right = event.getArm() == HumanoidArm.RIGHT;
        ModelPart arm = right ? model.rightArm : model.leftArm;
        ItemSurface fist = surface(arm);
        if (fist == null) return;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        arm.resetPose();
        arm.zRot = right ? .1f : -.1f;
        arm.translateAndRotate(pose);
        SurfaceAuras.submitFist(w.element(), player.getId(), right ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND,
                fist, pose, BufferSubmitNodeCollector.immediate(event.getMultiBufferSource()));
        pose.popPose();
    }

    /** The fist at the end of an arm, in the arm's model space (blocks); null for an arm with no cube of its own. */
    private static @Nullable ItemSurface surface(ModelPart arm) {
        if (arm.isEmpty()) return null;
        ModelPart.Cube c = arm.getRandomCube(RANDOM);
        return SURFACES.computeIfAbsent(c, k -> {
            if (SURFACES.size() > 64) SURFACES.clear();
            return ItemSurface.box(k.minX / 16, Math.max(k.minY, k.maxY - FIST) / 16, k.minZ / 16, k.maxX / 16, k.maxY / 16, k.maxZ / 16);
        });
    }

    /** Draws the wrap on an empty main hand, after the creature's model. */
    private static final class Layer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {
        Layer(RenderLayerParent<T, M> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffers, int light, T entity, float limbSwing, float limbSwingAmount,
                float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            ManaWrap w = ManaWrap.of(entity);
            if (w == null || entity.isInvisible() || !entity.getMainHandItem().isEmpty()) return;
            HumanoidArm main = entity.getMainArm();
            ModelPart arm = main == HumanoidArm.RIGHT ? getParentModel().rightArm : getParentModel().leftArm;
            ItemSurface fist = arm.visible ? surface(arm) : null;
            if (fist == null) return;
            poseStack.pushPose();
            getParentModel().translateToHand(main, poseStack);
            SurfaceAuras.submitFist(w.element(), entity.getId(),
                    main == HumanoidArm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    fist, poseStack, BufferSubmitNodeCollector.immediate(buffers));
            poseStack.popPose();
        }
    }
}

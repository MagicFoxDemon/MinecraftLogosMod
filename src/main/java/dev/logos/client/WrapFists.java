package dev.logos.client;

import java.util.IdentityHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.google.common.reflect.TypeToken;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderArmEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

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
    /** On a living entity's render state: who it is and the element of its wrap, when it wears one. */
    private static final ContextKey<Fist> KEY = new ContextKey<>(Identifier.fromNamespaceAndPath(LogosMod.MODID, "wrap_fist"));
    private static final Map<ModelPart.Cube, ItemSurface> SURFACES = new IdentityHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private record Fist(int owner, Element element) {}

    private WrapFists() {}

    @SubscribeEvent
    static void onRenderState(RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier(new TypeToken<LivingEntityRenderer<LivingEntity, LivingEntityRenderState, ?>>() {}, (entity, state) -> {
            ManaWrap w = ManaWrap.of(entity);
            state.setRenderData(KEY, w == null ? null : new Fist(entity.getId(), w.element()));
        });
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void onLayers(EntityRenderersEvent.AddLayers event) {
        for (var type : event.getEntityTypes()) {
            if (event.getRenderer(type) instanceof LivingEntityRenderer r && r.getModel() instanceof HumanoidModel) r.addLayer(new Layer(r));
        }
        for (var skin : event.getSkins()) {
            LivingEntityRenderer r = event.getPlayerRenderer(skin);
            if (r != null) r.addLayer(new Layer(r));
        }
    }

    /** One's own empty main hand in first person, posed as AvatarRenderer.renderHand poses it. */
    @SubscribeEvent
    static void onArm(RenderArmEvent event) {
        AbstractClientPlayer player = event.getPlayer();
        ManaWrap w = ManaWrap.of(player);
        if (w == null || event.getArm() != player.getMainArm()) return;
        var model = Minecraft.getInstance().getEntityRenderDispatcher().getPlayerRenderer(player).getModel();
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
                fist, pose, event.getSubmitNodeCollector());
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
    private static final class Layer<S extends HumanoidRenderState, M extends HumanoidModel<S>> extends RenderLayer<S, M> {
        Layer(RenderLayerParent<S, M> parent) {
            super(parent);
        }

        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, S state, float yRot, float xRot) {
            Fist f = state.getRenderData(KEY);
            if (f == null || state.isInvisible || !state.getMainHandItemState().isEmpty()) return;
            ModelPart arm = getParentModel().getArm(state.mainArm);
            ItemSurface fist = arm.visible ? surface(arm) : null;
            if (fist == null) return;
            poseStack.pushPose();
            getParentModel().translateToHand(state, state.mainArm, poseStack);
            SurfaceAuras.submitFist(f.element(), f.owner(),
                    state.mainArm == HumanoidArm.LEFT ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                    fist, poseStack, collector);
            poseStack.popPose();
        }
    }
}

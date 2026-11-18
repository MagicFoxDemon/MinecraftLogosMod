package dev.logos.client.port;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * A {@link SubmitNodeCollector} over 1.21.1's buffer sources. Submissions are kept and drawn in order, lowest
 * {@link #order} first and, within one, as they came, when {@link #flush} draws them into a buffer source; so a
 * collector can be filled during a frame and drawn at its end, as 26.1 draws what is submitted.
 */
public final class BufferSubmitNodeCollector implements SubmitNodeCollector {
    private record Submission(int order, PoseStack.Pose pose, @Nullable RenderType type, @Nullable CustomGeometryRenderer renderer,
            @Nullable Drawing drawing) {
        void draw(MultiBufferSource buffers) {
            if (drawing != null) drawing.draw(at(pose), buffers);
            else renderer.render(pose, buffers.getBuffer(type));
        }
    }

    private static PoseStack at(PoseStack.Pose pose) {
        PoseStack stack = new PoseStack();
        stack.last().pose().set(pose.pose());
        stack.last().normal().set(pose.normal());
        return stack;
    }

    private final List<Submission> submissions = new ArrayList<>();
    private final List<Runnable> nameTags = new ArrayList<>();
    private @Nullable MultiBufferSource nameTagBuffers;

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        return new OrderedSubmitNodeCollector() {
            @Override
            public void submitCustomGeometry(PoseStack poseStack, RenderType type, CustomGeometryRenderer renderer) {
                submissions.add(new Submission(order, poseStack.last().copy(), type, renderer, null));
            }

            @Override
            public void submitDrawing(PoseStack poseStack, Drawing drawing) {
                submissions.add(new Submission(order, poseStack.last().copy(), null, null, drawing));
            }

            @Override
            public void submitNameTag(PoseStack poseStack, @Nullable Vec3 attachment, int offset, Component name, boolean seeThrough,
                    int light, double distanceSq, CameraRenderState camera) {
                BufferSubmitNodeCollector.this.submitNameTag(poseStack, attachment, offset, name, seeThrough, light, distanceSq, camera);
            }
        };
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType type, CustomGeometryRenderer renderer) {
        submissions.add(new Submission(0, poseStack.last().copy(), type, renderer, null));
    }

    @Override
    public void submitDrawing(PoseStack poseStack, Drawing drawing) {
        submissions.add(new Submission(0, poseStack.last().copy(), null, null, drawing));
    }

    /** A name tag over {@code attachment}, facing the camera, as an entity's is drawn. */
    @Override
    public void submitNameTag(PoseStack poseStack, @Nullable Vec3 attachment, int offset, Component name, boolean seeThrough,
            int light, double distanceSq, CameraRenderState camera) {
        Matrix4f at = new Matrix4f(poseStack.last().pose());
        Vec3 offsetBy = attachment == null ? Vec3.ZERO : attachment;
        nameTags.add(() -> {
            Minecraft mc = Minecraft.getInstance();
            Font font = mc.font;
            PoseStack tag = new PoseStack();
            tag.mulPose(at);
            tag.translate(offsetBy.x, offsetBy.y + 0.5, offsetBy.z);
            tag.mulPose(camera.orientation);
            tag.scale(0.025f, -0.025f, 0.025f);
            float x = -font.width(name) / 2f;
            int background = (int) (mc.options.getBackgroundOpacity(0.25f) * 255) << 24;
            font.drawInBatch(name, x, offset, 0x20FFFFFF, false, tag.last().pose(), nameTagBuffers,
                    seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, background, light);
            if (seeThrough) font.drawInBatch(name, x, offset, 0xFFFFFFFF, false, tag.last().pose(), nameTagBuffers, Font.DisplayMode.NORMAL, 0, light);
        });
    }

    /**
     * A collector that draws each submission into {@code buffers} as it comes, for code that 1.21.1 hands a buffer
     * source (entity layers, first-person arms): what 26.1 submits there is drawn with that entity anyway.
     */
    public static SubmitNodeCollector immediate(MultiBufferSource buffers) {
        return new SubmitNodeCollector() {
            @Override
            public OrderedSubmitNodeCollector order(int order) {
                return this;
            }

            @Override
            public void submitCustomGeometry(PoseStack poseStack, RenderType type, CustomGeometryRenderer renderer) {
                renderer.render(poseStack.last(), buffers.getBuffer(type));
            }

            @Override
            public void submitDrawing(PoseStack poseStack, Drawing drawing) {
                drawing.draw(at(poseStack.last()), buffers);
            }

            @Override
            public void submitNameTag(PoseStack poseStack, @Nullable Vec3 attachment, int offset, Component name, boolean seeThrough,
                    int light, double distanceSq, CameraRenderState camera) {
                BufferSubmitNodeCollector tags = new BufferSubmitNodeCollector();
                tags.submitNameTag(poseStack, attachment, offset, name, seeThrough, light, distanceSq, camera);
                tags.flush(buffers);
            }
        };
    }

    public boolean isEmpty() {
        return submissions.isEmpty() && nameTags.isEmpty();
    }

    /** Draws what was submitted into {@code buffers}, then forgets it. The caller ends the batches. */
    public void flush(MultiBufferSource buffers) {
        submissions.sort(Comparator.comparingInt(Submission::order));
        for (Submission s : submissions) s.draw(buffers);
        submissions.clear();
        nameTagBuffers = buffers;
        for (Runnable tag : nameTags) tag.run();
        nameTags.clear();
        nameTagBuffers = null;
    }
}

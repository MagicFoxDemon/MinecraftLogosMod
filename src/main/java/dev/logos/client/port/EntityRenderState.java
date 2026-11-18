package dev.logos.client.port;

/**
 * Stands in for 26.1's {@code net.minecraft.client.renderer.entity.state.EntityRenderState}, as far as the mod's
 * poses read it. 1.21.1 draws entities straight from the entity; {@link HumanoidRenderState#of} fills one from it
 * when a pose needs it.
 */
public class EntityRenderState {
    /** Ticks the entity has lived, partial tick included. */
    public float ageInTicks;
    public float partialTick;
    public boolean isInvisible;
}

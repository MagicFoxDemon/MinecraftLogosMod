package dev.logos.port;

import java.util.function.Predicate;

import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Stands in for 26.1's {@code net.neoforged.neoforge.transfer.fluid.FluidResource}: a fluid with its components,
 * without an amount. Two resources are equal when their fluids and components are.
 */
public final class FluidResource implements Resource {
    public static final FluidResource EMPTY = new FluidResource(FluidStack.EMPTY);
    public static final Codec<FluidResource> CODEC = FluidStack.CODEC.xmap(FluidResource::of, r -> r.toStack(FluidType.BUCKET_VOLUME));

    /** A stack of one unit, never handed out: copies leave here. */
    private final FluidStack stack;

    private FluidResource(FluidStack stack) {
        this.stack = stack;
    }

    public static FluidResource of(FluidStack stack) {
        return stack.isEmpty() ? EMPTY : new FluidResource(stack.copyWithAmount(1));
    }

    public static FluidResource of(Fluid fluid) {
        return of(new FluidStack(fluid, 1));
    }

    public static FluidResource of(Fluid fluid, DataComponentPatch patch) {
        return of(new FluidStack(fluid.builtInRegistryHolder(), 1, patch));
    }

    public static FluidResource of(Holder<Fluid> fluid) {
        return of(new FluidStack(fluid, 1));
    }

    public Fluid value() {
        return stack.getFluid();
    }

    public Fluid getFluid() {
        return stack.getFluid();
    }

    public Holder<Fluid> typeHolder() {
        return stack.getFluidHolder();
    }

    public FluidType getFluidType() {
        return stack.getFluidType();
    }

    @Override
    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public boolean is(FluidType fluidType) {
        return stack.getFluidType() == fluidType;
    }

    public boolean matches(FluidStack other) {
        return FluidStack.isSameFluidSameComponents(stack, other);
    }

    public boolean test(Predicate<FluidStack> predicate) {
        return predicate.test(stack);
    }

    public DataComponentPatch getComponentsPatch() {
        return stack.getComponentsPatch();
    }

    public FluidStack toStack(int amount) {
        return isEmpty() ? FluidStack.EMPTY : stack.copyWithAmount(amount);
    }

    public Component getHoverName() {
        return stack.getHoverName();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FluidResource other && FluidStack.isSameFluidSameComponents(stack, other.stack);
    }

    @Override
    public int hashCode() {
        return FluidStack.hashFluidAndComponents(stack);
    }

    @Override
    public String toString() {
        return isEmpty() ? "FluidResource[EMPTY]" : "FluidResource[" + stack.getFluid() + " " + stack.getComponentsPatch() + "]";
    }
}

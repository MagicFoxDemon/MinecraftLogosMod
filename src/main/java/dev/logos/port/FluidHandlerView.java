package dev.logos.port;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** A {@link ResourceHandler} of fluids as 1.21.1's {@link IFluidHandler}, for buckets, other mods' pipes and LDLib2's fluid slots. A simulated move is a transaction left uncommitted. */
public final class FluidHandlerView implements IFluidHandler {
    private final ResourceHandler<FluidResource> handler;

    public FluidHandlerView(ResourceHandler<FluidResource> handler) {
        this.handler = handler;
    }

    @Override
    public int getTanks() {
        return handler.size();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return handler.getResource(tank).toStack(handler.getAmountAsInt(tank));
    }

    @Override
    public int getTankCapacity(int tank) {
        return handler.getCapacityAsInt(tank, FluidResource.EMPTY);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return !stack.isEmpty() && handler.isValid(tank, FluidResource.of(stack));
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return 0;
        try (Transaction tx = Transaction.openRoot()) {
            int filled = handler.insert(FluidResource.of(resource), resource.getAmount(), tx);
            if (action.execute()) tx.commit();
            return filled;
        }
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return FluidStack.EMPTY;
        FluidResource kind = FluidResource.of(resource);
        try (Transaction tx = Transaction.openRoot()) {
            int drained = handler.extract(kind, resource.getAmount(), tx);
            if (action.execute()) tx.commit();
            return kind.toStack(drained);
        }
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        for (int i = 0; i < handler.size(); i++) {
            FluidResource kind = handler.getResource(i);
            if (kind.isEmpty() || handler.getAmountAsInt(i) == 0) continue;
            return drain(kind.toStack(maxDrain), action);
        }
        return FluidStack.EMPTY;
    }
}

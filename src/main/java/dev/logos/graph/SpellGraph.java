package dev.logos.graph;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.Graph;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.GraphLogger;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.graph.GraphNodeRegistry;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.node.Node;
import com.lowdragmc.lowdraglib2.nodegraphtookit.api.type.TypeHandle;

import dev.logos.LogosMod;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * A spell drawn as a node graph. Every edit recompiles it through the same compiler as written
 * spells, and type errors, missing context, proofs and budgets show up in the graph log.
 */
public class SpellGraph extends Graph {
    public static final GraphNodeRegistry NODES = GraphNodeRegistry.create(
            Identifier.fromNamespaceAndPath(LogosMod.MODID, "spell_graph"), SpellGraph.class);

    private GraphCompiler.Result last;
    private final List<Runnable> listeners = new ArrayList<>();

    public SpellGraph() {
        LTypes.init();
    }

    @Override
    public List<Class<? extends Node>> getSupportNodes() {
        return NODES.getNodeClasses();
    }

    @Override
    public @Nullable List<TypeHandle> getSupportTypes() {
        return List.of(LTypes.ENTITY, LTypes.ENTITIES, LTypes.POS, LTypes.BLOCKS, LTypes.DIR, LTypes.NUM, LTypes.BOOL, LTypes.ELEMENT, LTypes.MOTION, LTypes.SHAPE, LTypes.STATUS);
    }

    /** Constants live inline on input ports; no free-floating constant or variable nodes. */
    @Override
    public @Nullable List<TypeHandle> getLibrarySupportTypes() {
        return List.of();
    }

    @Override
    public @Nullable List<TypeHandle> getVariableSupportTypes() {
        return List.of();
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    /** The latest compilation (recompiled on demand if the graph has not reported a change yet). */
    public GraphCompiler.Result result() {
        if (last == null) last = GraphCompiler.compile(this);
        return last;
    }

    @Override
    public void onGraphChanged(GraphLogger logger) {
        last = GraphCompiler.compile(this);
        if (last.error() != null) {
            logger.error(Component.literal(last.error()), last.errorNode() == null ? null : last.errorNode().getNodeModel());
        } else {
            var s = last.spell();
            logger.info(Component.literal("✓ 《" + s.name() + "》成立 · 注入 " + dev.logos.lang.Node.fmt(s.mana())
                    + " · 稳定度 " + dev.logos.lang.Node.fmt(s.stability())));
            for (String note : s.notes()) logger.info(Component.literal("∴ " + note));
        }
        listeners.forEach(Runnable::run);
    }

}

package dev.logos.client;

import java.util.Locale;
import java.util.stream.Collectors;

import dev.logos.graph.ContextSources;
import dev.logos.graph.LogosNode;
import net.minecraft.network.chat.Component;

/** Readable, localized explanations of context uses; no runtime coordinates are implied. */
final class ContextWireDetails {
    private ContextWireDetails() {}

    static String describe(ContextSources.Analysis analysis) {
        String name = Component.translatableWithFallback("logos.node.ctx." + analysis.context(), analysis.context()).getString();
        StringBuilder text = new StringBuilder(tr("title", name));
        text.append("\n").append(tr("static"));
        if (analysis.uses().isEmpty()) text.append("\n\n").append(tr("unused"));
        if (analysis.uses().size() > 1) text.append("\n\n").append(tr("multiple"));
        for (var use : analysis.uses()) {
            text.append("\n\n").append(tr("use", use.consumer().title()));
            text.append("\n").append(tr("meaning." + use.binding().meaning().name().toLowerCase(Locale.ROOT)));
            text.append("\n").append(tr("source", use.binding().source().title()));
            String route = use.route().stream().map(port -> {
                LogosNode node = LogosNode.of(port);
                return (node == null ? "" : node.title() + " · ") + port.getDisplayName().getString();
            }).collect(Collectors.joining(" → "));
            text.append("\n").append(tr("route", route));
        }
        if (analysis.recursive()) text.append("\n\n").append(tr("recursive"));
        if (analysis.incomplete()) text.append("\n\n").append(tr("incomplete"));
        return text.toString();
    }

    private static String tr(String key, Object... args) {
        return Component.translatable("logos.editor.context." + key, args).getString();
    }
}

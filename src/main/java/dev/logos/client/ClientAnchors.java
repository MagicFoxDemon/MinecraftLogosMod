package dev.logos.client;

import java.util.List;

import dev.logos.graph.AnchorNames;
import dev.logos.net.AnchorsPayload;
import net.minecraft.client.Minecraft;

/** The local player's anchors as last synced by the server. */
public final class ClientAnchors {
    private static List<AnchorsPayload.Entry> anchors = List.of();

    private ClientAnchors() {}

    public static List<AnchorsPayload.Entry> all() {
        return anchors;
    }

    static void install() {
        // The editor's ⚓ dropdown lists these names.
        AnchorNames.known = () -> anchors.stream().map(AnchorsPayload.Entry::name).toList();
    }

    static void receive(AnchorsPayload p) {
        anchors = List.copyOf(p.anchors());
        if (Minecraft.getInstance().screen instanceof AnchorScreens.List list) list.refresh();
    }
}

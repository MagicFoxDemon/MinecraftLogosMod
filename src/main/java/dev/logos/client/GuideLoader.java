package dev.logos.client;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.logos.LogosMod;
import dev.logos.guide.GuideBook;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;

/**
 * Reads 逻各斯手记 from {@code assets/<namespace>/guide/<language>/} in every resource pack, so an addon adds chapters
 * and a resource pack rewrites pages. Each page comes in the player's language if written in it, else English, else
 * Chinese, the language the book is first written in. Loaded again with the resources, so a new language reloads it.
 */
@EventBusSubscriber(modid = LogosMod.MODID, value = Dist.CLIENT)
public final class GuideLoader {
    private static final String ROOT = "guide";
    private static GuideBook book = GuideBook.EMPTY;

    private GuideLoader() {}

    public static GuideBook book() {
        return book;
    }

    @SubscribeEvent
    static void onReload(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(LogosMod.MODID, ROOT), (ResourceManagerReloadListener) GuideLoader::load);
    }

    static void load(ResourceManager resources) {
        List<String> languages = java.util.stream.Stream.of(Minecraft.getInstance().getLanguageManager().getSelected(), "en_us", "zh_cn")
                .distinct().toList();
        Map<String, String> files = new LinkedHashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        for (var found : resources.listResources(ROOT, id -> id.getPath().endsWith(".md")).entrySet()) {
            Identifier id = found.getKey();
            // guide/<language>/<chapter>/<name>.md
            String rest = id.getPath().substring(ROOT.length() + 1);
            int slash = rest.indexOf('/');
            if (slash < 0) continue;
            int r = languages.indexOf(rest.substring(0, slash));
            String key = id.getNamespace() + ":" + rest.substring(slash + 1, rest.length() - ".md".length());
            if (r < 0 || rank.getOrDefault(key, Integer.MAX_VALUE) <= r) continue;
            try (Reader reader = found.getValue().openAsReader()) {
                StringBuilder text = new StringBuilder();
                char[] buffer = new char[4096];
                for (int n; (n = reader.read(buffer)) > 0; ) text.append(buffer, 0, n);
                files.put(key, text.toString());
                rank.put(key, r);
            } catch (IOException e) {
                LogosMod.LOGGER.warn("Could not read guide page {}", id, e);
            }
        }
        book = GuideBook.build(files);
    }
}

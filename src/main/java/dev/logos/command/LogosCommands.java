package dev.logos.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.logos.LogosMod;
import dev.logos.item.Books;
import dev.logos.item.WandItem;
import dev.logos.item.SpellBag;
import dev.logos.item.SpellPages;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;
import java.util.ArrayList;
import dev.logos.lang.Category;
import dev.logos.lang.Compiler;
import dev.logos.lang.Examples;
import dev.logos.lang.LogosError;
import dev.logos.lang.Motion;
import dev.logos.lang.Node;
import dev.logos.lang.Report;
import dev.logos.lang.Rune;
import dev.logos.lang.Runes;
import dev.logos.lang.Spell;
import dev.logos.lang.Unlocks;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Limits;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.RuneKnowledge;
import dev.logos.runtime.SpellRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;

/** {@code /logos …}: the developer console for playtesting the spell language. */
public final class LogosCommands {
    private LogosCommands() {}

    public static Path spellDir() {
        return FMLPaths.GAMEDIR.get().resolve("logos_spells");
    }

    /** Writes the example spells as editable .logos files (never overwriting the player's edits). */
    public static void exportExamples() {
        try {
            Files.createDirectories(spellDir());
            for (Map.Entry<String, String> e : Examples.ALL.entrySet()) {
                Path p = spellDir().resolve(e.getKey() + ".logos");
                if (!Files.exists(p)) Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LogosMod.LOGGER.warn("Could not export example spells", e);
        }
    }

    private static List<String> spellFiles() {
        try (Stream<Path> s = Files.list(spellDir())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".logos")).map(n -> n.substring(0, n.length() - 6)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("logos")
                // Tools for building and testing spells (cast anything, hand out pages and kits, read and write spell files on the server,
                // set mana, debug readouts) are for game masters; what only shows a player their own magic is for everyone.
                .then(Commands.literal("cast").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("source", StringArgumentType.greedyString())
                        .executes(c -> cast(c, StringArgumentType.getString(c, "source")))))
                .then(Commands.literal("check").then(Commands.argument("source", StringArgumentType.greedyString())
                        .executes(c -> check(c, StringArgumentType.getString(c, "source")))))
                .then(Commands.literal("book").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("example", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Examples.ALL.keySet(), b))
                        .executes(c -> book(c, StringArgumentType.getString(c, "example")))))
                .then(Commands.literal("load").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("file", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(spellFiles(), b))
                        .executes(c -> load(c, StringArgumentType.getString(c, "file")))))
                .then(Commands.literal("source").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> source(c, null))
                        .then(Commands.argument("file", StringArgumentType.word()).executes(c -> source(c, StringArgumentType.getString(c, "file")))))
                .then(Commands.literal("examples").executes(LogosCommands::examples))
                .then(Commands.literal("kit").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(LogosCommands::kit))
                .then(Commands.literal("anchors").executes(LogosCommands::anchors))
                .then(Commands.literal("mind").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(LogosCommands::mind))
                .then(Commands.literal("stats").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(LogosCommands::stats))
                // Until alchemy can build one: raise an alchemical colossus before you.
                .then(Commands.literal("colossus").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> colossus(c, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> colossus(c, StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("runes").executes(c -> runes(c, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Runes.all().stream().map(r -> r.name).toList(), b))
                                .executes(c -> runes(c, StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("mana").executes(LogosCommands::manaInfo)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0, Integer.MAX_VALUE)).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            PlayerMagic m = PlayerMagic.of(p);
                            m.mana = DoubleArgumentType.getDouble(c, "amount");
                            // Raise the permanent part as far as it takes for the whole cap to hold that much.
                            m.baseMaxMana += Math.max(0, m.mana - PlayerMagic.maxMana(p));
                            return manaInfo(c);
                        })))
                .then(Commands.literal("stability").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("capacity", IntegerArgumentType.integer(1, 10000)).executes(c -> {
                    PlayerMagic.of(c.getSource().getPlayerOrException()).stability = IntegerArgumentType.getInteger(c, "capacity");
                    return manaInfo(c);
                })))
                .then(Commands.literal("trace").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
                    PlayerMagic.of(c.getSource().getPlayerOrException()).trace = BoolArgumentType.getBool(c, "on");
                    c.getSource().sendSuccess(() -> Component.translatable(BoolArgumentType.getBool(c, "on") ? "logos.command.trace.on" : "logos.command.trace.off"), false);
                    return 1;
                })))
                .then(Commands.literal("passives").executes(LogosCommands::passives))
                // 符文解锁: anyone sees what they have learned; teaching and taking away is for game masters.
                .then(Commands.literal("unlocks").executes(c -> unlocks(c, c.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player()).requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(c -> unlocks(c, EntityArgument.getPlayer(c, "player")))))
                .then(Commands.literal("unlock").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", EntityArgument.players()).then(unlockKey(true))))
                .then(Commands.literal("lock").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("targets", EntityArgument.players()).then(unlockKey(false)))));
    }

    /** A key of {@link Unlocks} (rune:freeze, element:fire, motion:arc) or {@code all}, to teach the targets or take from them. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> unlockKey(boolean learn) {
        return Commands.argument("key", StringArgumentType.greedyString())
                .suggests((c, b) -> {
                    List<String> keys = new ArrayList<>(Unlocks.all());
                    keys.addFirst("all");
                    return SharedSuggestionProvider.suggest(keys, b);
                })
                .executes(c -> unlock(c, EntityArgument.getPlayers(c, "targets"), StringArgumentType.getString(c, "key").strip(), learn));
    }

    private static int unlock(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players, String key, boolean learn) {
        boolean all = key.equals("all");
        if (!all && !Unlocks.lockable(key)) {
            c.getSource().sendFailure(Component.translatable("logos.command.unlock.unknown", key));
            return 0;
        }
        int changed = 0;
        for (ServerPlayer p : players) {
            for (String k : all ? Unlocks.all() : List.of(key)) {
                if (learn ? RuneKnowledge.learn(p, k) : RuneKnowledge.forget(p, k)) changed++;
            }
        }
        int n = changed;
        c.getSource().sendSuccess(() -> Component.translatable(learn ? "logos.command.unlock.learned" : "logos.command.unlock.forgot", n, players.size()), true);
        return n;
    }

    /** What {@code p} has learned, family by family; creative play and a server with runeUnlocks off write everything anyway. */
    private static int unlocks(CommandContext<CommandSourceStack> c, ServerPlayer p) {
        CommandSourceStack s = c.getSource();
        Component note = !dev.logos.LogosConfig.runeUnlocks() ? Component.translatable("logos.command.unlocks.off")
                : p.hasInfiniteMaterials() ? Component.translatable("logos.command.unlocks.creative") : Component.empty();
        s.sendSuccess(() -> Component.translatable("logos.command.unlocks.head", p.getName(), note).withStyle(ChatFormatting.GOLD), false);
        for (Category cat : Category.values()) {
            List<String> words = new ArrayList<>();
            int total = 0;
            for (Rune r : Runes.all()) {
                if (r.category != cat) continue;
                total++;
                if (RuneKnowledge.learned(p, Unlocks.rune(r.name))) words.add(r.name);
            }
            if (total > 0) listed(s, Component.literal("[" + cat.zh + "] "), cat.color, words, total);
        }
        List<String> elements = new ArrayList<>(), motions = new ArrayList<>();
        for (dev.logos.lang.Element e : dev.logos.lang.Element.values()) if (RuneKnowledge.learned(p, Unlocks.element(e))) elements.add(e.name().toLowerCase(java.util.Locale.ROOT));
        for (Motion m : Motion.values()) if (RuneKnowledge.learned(p, Unlocks.motion(m))) motions.add(m.name().toLowerCase(java.util.Locale.ROOT));
        listed(s, Component.translatable("logos.command.unlocks.elements"), ChatFormatting.GOLD, elements, dev.logos.lang.Element.values().length);
        listed(s, Component.translatable("logos.command.unlocks.motions"), ChatFormatting.AQUA, motions, Motion.values().length);
        return 1;
    }

    private static void listed(CommandSourceStack s, Component head, ChatFormatting color, List<String> words, int total) {
        s.sendSuccess(() -> head.copy().append(words.size() + "/" + total + "  ").withStyle(color)
                .append(Component.literal(String.join("  ", words)).withStyle(ChatFormatting.WHITE)), false);
    }

    private static int cast(CommandContext<CommandSourceStack> c, String src) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        try {
            Spell s = Casting.compile(src);
            if (s.isPassive()) {
                c.getSource().sendFailure(Component.translatable("logos.command.cast.passive", Spell.triggerName(s.trigger())));
                return 0;
            }
            return Casting.cast(p, s, null, null) != null ? 1 : 0;
        } catch (LogosError e) {
            Casting.message(p, e);
            return 0;
        }
    }

    private static int check(CommandContext<CommandSourceStack> c, String src) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        try {
            Report.analyze(Compiler.compile(src), PlayerMagic.of(p).stability, p::sendSystemMessage);
            return 1;
        } catch (LogosError e) {
            Casting.message(p, e);
            return 0;
        }
    }

    private static int book(CommandContext<CommandSourceStack> c, String id) throws CommandSyntaxException {
        String src = Examples.ALL.get(id);
        if (src == null) {
            c.getSource().sendFailure(Component.translatable("logos.command.book.unknown", id));
            return 0;
        }
        give(c.getSource().getPlayerOrException(), Books.create(id, src));
        return 1;
    }

    private static int load(CommandContext<CommandSourceStack> c, String file) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Path path = spellDir().resolve(file + ".logos").normalize();
        if (!path.startsWith(spellDir()) || !Files.exists(path)) {
            c.getSource().sendFailure(Component.translatable("logos.command.load.missing", path.toString()));
            return 0;
        }
        String src;
        try {
            src = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            c.getSource().sendFailure(Component.translatable("logos.command.load.failed", String.valueOf(e.getMessage())));
            return 0;
        }
        try {
            Spell s = Casting.compile(src);
            give(p, SpellPages.fitting(src));
            c.getSource().sendSuccess(() -> Component.translatable("logos.command.book.given", s.name()), false);
            return 1;
        } catch (LogosError e) {
            Casting.message(p, e);
            return 0;
        }
    }

    /** Debug: the source text written on the page (or ember) in hand, shown click-to-copy and optionally saved for {@code /logos load}. */
    private static int source(CommandContext<CommandSourceStack> c, @org.jspecify.annotations.Nullable String file) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        String src = null;
        for (InteractionHand hand : InteractionHand.values()) {
            src = p.getItemInHand(hand).get(LogosMod.SPELL_SOURCE.get());
            if (src != null) break;
        }
        if (src == null) {
            c.getSource().sendFailure(Component.translatable("logos.command.source.none"));
            return 0;
        }
        String text = src;
        c.getSource().sendSuccess(() -> Component.translatable("logos.command.source.head").withStyle(ChatFormatting.GOLD), false);
        c.getSource().sendSuccess(() -> Component.literal(text).withStyle(st -> st.withColor(ChatFormatting.WHITE)
                .withClickEvent(new ClickEvent.CopyToClipboard(text))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.copy.click")))), false);
        if (file == null) return 1;
        Path path = spellDir().resolve(file + ".logos").normalize();
        if (!path.startsWith(spellDir())) {
            c.getSource().sendFailure(Component.translatable("logos.command.source.bad_name", file));
            return 0;
        }
        try {
            Files.createDirectories(spellDir());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            c.getSource().sendFailure(Component.translatable("logos.command.source.failed", String.valueOf(e.getMessage())));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("logos.command.source.saved", path.toString(), file).withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int examples(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.translatable("logos.command.examples.head", spellDir().toString())
                .withStyle(ChatFormatting.GOLD), false);
        for (Map.Entry<String, String> e : Examples.ALL.entrySet()) {
            String desc = e.getValue().lines().findFirst().orElse("").replaceFirst("^;\\s*", "");
            c.getSource().sendSuccess(() -> Component.literal("  " + e.getKey()).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("  " + desc).withStyle(ChatFormatting.GRAY)), false);
        }
        return 1;
    }

    private static int colossus(CommandContext<CommandSourceStack> c, @org.jspecify.annotations.Nullable String name) throws CommandSyntaxException {
        var p = c.getSource().getPlayerOrException();
        var at = p.position().add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(3));
        Component why = dev.logos.runtime.Colossi.summon(p, p.level(), at, name);
        if (why != null) {
            c.getSource().sendFailure(why);
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("logos.command.colossus.raised", Component.keybind("key.logos.colossus_mode")), false);
        return 1;
    }

    /** Debug: what each of the player's mana lifeforms is doing and how it scores its choices. */
    private static int mind(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p = c.getSource().getPlayerOrException();
        int n = 0;
        for (var m : dev.logos.runtime.Familiars.of(p)) {
            if (m instanceof dev.logos.runtime.AlchemicalColossus col) {
                n++;
                Component mind = col.isDormant() || col.mind() == null ? Component.translatable("logos.colossus.dormant") : col.mind().describe();
                c.getSource().sendSuccess(() -> col.status().copy().append("\n  ").append(mind), false);
                continue;
            }
            if (!(m instanceof dev.logos.runtime.ManaLifeform lf)) continue;
            n++;
            Component mind = lf.isDormant() || lf.mind() == null ? Component.translatable("logos.lifeform.dormant") : lf.mind().describe();
            c.getSource().sendSuccess(() -> Component.translatable("logos.command.mind.lifeform", lf.getName(), lf.role().title(), lf.temperament()).append("\n  ").append(mind), false);
        }
        if (n == 0) c.getSource().sendFailure(Component.translatable("logos.command.mind.none"));
        return n;
    }

    /** Debug: how much the running spells ask of the server: what waits, what lives, and the last tick's interpreter steps. */
    private static int stats(CommandContext<CommandSourceStack> c) {
        Component line = Component.translatable("logos.command.stats", SpellRuntime.pendingTasks(), Limits.MAX_GLOBAL_TASKS,
                SpellRuntime.liveForms(), Limits.MAX_GLOBAL_OBJECTS, SpellRuntime.stepsLastTick(), dev.logos.LogosConfig.maxOpsPerTickGlobal());
        c.getSource().sendSuccess(() -> line.copy().withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int anchors(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        var all = dev.logos.runtime.Anchors.of(p).all();
        if (all.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.translatable("logos.command.anchors.none"), false);
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable("logos.command.anchors.head", all.size(), dev.logos.LogosConfig.maxAnchors()), false);
        for (var a : all) {
            String line = String.format("  ^%s  %.0f, %.0f, %.0f  %s", a.name(), a.pos().x, a.pos().y, a.pos().z, a.dimension().identifier());
            c.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return all.size();
    }

    private static int kit(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        // A master wand carrying the first examples, a codex holding the rest, a table and blank pages.
        List<ItemStack> pages = new ArrayList<>();
        for (String src : Examples.ALL.values()) pages.add(SpellPages.fitting(src));
        ItemStack wand = new ItemStack(LogosMod.MASTER_WAND.get());
        int onWand = Math.min(LogosMod.MASTER_WAND.get().slots(), pages.size());
        wand.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(pages.subList(0, onWand)));
        give(p, wand);
        // As many codices as the remaining examples need (27 pages each).
        for (int from = onWand; from < pages.size(); from += dev.logos.item.CodexItem.SIZE) {
            ItemStack codex = new ItemStack(LogosMod.SPELL_CODEX.get());
            codex.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(pages.subList(from, Math.min(pages.size(), from + dev.logos.item.CodexItem.SIZE))));
            give(p, codex);
        }
        give(p, new ItemStack(LogosMod.INSCRIPTION_TABLE_ITEM.get()));
        give(p, new ItemStack(LogosMod.COPY_TABLE_ITEM.get()));
        give(p, new ItemStack(LogosMod.ALCHEMY_TABLE_ITEM.get()));
        give(p, new ItemStack(LogosMod.SPELL_PAGE.get(), 8));
        give(p, new ItemStack(LogosMod.ANCHOR_NAIL.get(), 16));
        give(p, new ItemStack(Items.WRITABLE_BOOK));
        c.getSource().sendSuccess(() -> Component.translatable("logos.command.kit.given", onWand, pages.size() - onWand, Component.keybind("key.logos.spell_wheel"))
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int runes(CommandContext<CommandSourceStack> c, String name) {
        CommandSourceStack s = c.getSource();
        if (name != null) {
            Rune r = Runes.get(name);
            if (r == null) {
                s.sendFailure(Component.translatable("logos.command.runes.unknown", name));
                return 0;
            }
            s.sendSuccess(() -> Component.literal("[" + r.category.zh + "] ").withStyle(r.category.color)
                    .append(Component.literal(r.signature()).withStyle(ChatFormatting.WHITE)), false);
            s.sendSuccess(() -> Component.translatable("logos.command.runes.doc", r.doc, Node.fmt(r.stability)).withStyle(ChatFormatting.GRAY), false);
            for (Rune.EventSpec e : r.events.values()) {
                s.sendSuccess(() -> Component.translatable(e.bindsTarget() ? "logos.command.runes.event_target" : "logos.command.runes.event", e.name(), e.doc())
                        .withStyle(ChatFormatting.AQUA), false);
            }
            return 1;
        }
        for (Category cat : Category.values()) {
            List<String> names = new ArrayList<>();
            for (Rune r : Runes.all()) if (r.category == cat) names.add(r.name);
            if (cat == Category.MOTION) for (Motion m : Motion.values()) names.add(m.name().toLowerCase() + "(" + m.zh + ")");
            if (cat == Category.BINDING) names.addAll(0, Compiler.CONTEXT.keySet());
            if (cat == Category.VALUE) names.addAll(0, Compiler.MAGNITUDES.keySet());
            if (names.isEmpty()) continue;
            s.sendSuccess(() -> Component.literal("[" + cat.zh + "] ").withStyle(cat.color)
                    .append(Component.literal(String.join("  ", names)).withStyle(ChatFormatting.WHITE)), false);
        }
        s.sendSuccess(() -> Component.translatable("logos.command.runes.literals").withStyle(ChatFormatting.GRAY), false);
        s.sendSuccess(() -> Component.translatable("logos.command.runes.more").withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    /** The pool, then where its cap and refill come from: the permanent base, each growth term, and the rest. */
    private static int manaInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        PlayerMagic m = PlayerMagic.of(p);
        double cap = PlayerMagic.maxMana(p);
        c.getSource().sendSuccess(() -> Component.translatable("logos.command.mana.head", Node.fmt(m.mana), Node.fmt(cap), m.stability)
                .withStyle(ChatFormatting.AQUA), false);
        var attr = p.getAttribute(dev.logos.runtime.SpellModifiers.MAX_MANA);
        double rest = cap - m.baseMaxMana;
        MutableComponent sb = Component.translatable("logos.command.mana.base", Node.fmt(m.baseMaxMana));
        for (var t : dev.logos.runtime.ManaGrowth.Term.values()) {
            var mod = attr == null ? null : attr.getModifier(t.id);
            if (mod == null) continue;
            sb.append(Component.translatable("logos.command.mana.term", Component.translatable("logos.mana_growth." + t.id.getPath().replace("growth_", "")), Node.fmt(mod.amount())));
            rest -= mod.amount();
        }
        if (Math.abs(rest) >= 0.5) sb.append(Component.translatable(rest > 0 ? "logos.command.mana.rest" : "logos.command.mana.rest_less", Node.fmt(Math.abs(rest))));
        c.getSource().sendSuccess(() -> sb.withStyle(ChatFormatting.GRAY), false);
        c.getSource().sendSuccess(() -> Component.translatable("logos.command.mana.growth", Node.fmt(dev.logos.runtime.SpellModifiers.regenerationPerTick(p) * 20),
                Node.fmt(m.lifetimeXp), Node.fmt(m.manaSpent)).withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int passives(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var list = SpellBag.passives(c.getSource().getPlayerOrException());
        if (list.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.translatable("logos.command.passives.none"), false);
            return 1;
        }
        for (var e : list) {
            Spell s = e.spell();
            int cap = e.tier().capacity();
            Component inject = s.mana() > cap ? Component.translatable("logos.command.passives.over", Node.fmt(s.mana()), cap) : Component.literal(Node.fmt(s.mana()));
            c.getSource().sendSuccess(() -> Component.translatable("logos.command.passives.entry", s.name(), Spell.triggerName(s.trigger()), inject)
                    .withStyle(ChatFormatting.AQUA), false);
        }
        return 1;
    }

    private static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }
}

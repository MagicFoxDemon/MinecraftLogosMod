package dev.logos.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
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
                .then(Commands.literal("cast").then(Commands.argument("source", StringArgumentType.greedyString())
                        .executes(c -> cast(c, StringArgumentType.getString(c, "source")))))
                .then(Commands.literal("check").then(Commands.argument("source", StringArgumentType.greedyString())
                        .executes(c -> check(c, StringArgumentType.getString(c, "source")))))
                .then(Commands.literal("book").then(Commands.argument("example", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Examples.ALL.keySet(), b))
                        .executes(c -> book(c, StringArgumentType.getString(c, "example")))))
                .then(Commands.literal("load").then(Commands.argument("file", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(spellFiles(), b))
                        .executes(c -> load(c, StringArgumentType.getString(c, "file")))))
                .then(Commands.literal("examples").executes(LogosCommands::examples))
                .then(Commands.literal("kit").executes(LogosCommands::kit))
                .then(Commands.literal("anchors").executes(LogosCommands::anchors))
                .then(Commands.literal("mind").executes(LogosCommands::mind))
                // Until alchemy can build one: raise an alchemical colossus before you.
                .then(Commands.literal("colossus").executes(c -> colossus(c, null))
                        .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> colossus(c, StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("runes").executes(c -> runes(c, null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Runes.all().stream().map(r -> r.name).toList(), b))
                                .executes(c -> runes(c, StringArgumentType.getString(c, "name")))))
                .then(Commands.literal("mana").executes(LogosCommands::manaInfo)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0, Integer.MAX_VALUE)).executes(c -> {
                            PlayerMagic m = PlayerMagic.of(c.getSource().getPlayerOrException());
                            m.mana = DoubleArgumentType.getDouble(c, "amount");
                            m.maxMana = Math.max(m.maxMana, m.mana);
                            return manaInfo(c);
                        })))
                .then(Commands.literal("stability").then(Commands.argument("capacity", IntegerArgumentType.integer(1, 10000)).executes(c -> {
                    PlayerMagic.of(c.getSource().getPlayerOrException()).stability = IntegerArgumentType.getInteger(c, "capacity");
                    return manaInfo(c);
                })))
                .then(Commands.literal("trace").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
                    PlayerMagic.of(c.getSource().getPlayerOrException()).trace = BoolArgumentType.getBool(c, "on");
                    c.getSource().sendSuccess(() -> Component.literal("法术轨迹报告: " + BoolArgumentType.getBool(c, "on")), false);
                    return 1;
                })))
                .then(Commands.literal("passives").executes(LogosCommands::passives)));
    }

    private static int cast(CommandContext<CommandSourceStack> c, String src) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        try {
            Spell s = Casting.compile(src);
            if (s.isPassive()) {
                c.getSource().sendFailure(Component.literal("被动术式不能直接施放：把它写进术式载页并挂在法杖上，就会在" + Spell.triggerZh(s.trigger()) + "自动触发"));
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
            c.getSource().sendFailure(Component.literal("没有名为 " + id + " 的示例。/logos examples 查看全部"));
            return 0;
        }
        give(c.getSource().getPlayerOrException(), Books.create(id, src));
        return 1;
    }

    private static int load(CommandContext<CommandSourceStack> c, String file) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        Path path = spellDir().resolve(file + ".logos").normalize();
        if (!path.startsWith(spellDir()) || !Files.exists(path)) {
            c.getSource().sendFailure(Component.literal("找不到 " + path));
            return 0;
        }
        String src;
        try {
            src = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            c.getSource().sendFailure(Component.literal("读取失败: " + e.getMessage()));
            return 0;
        }
        try {
            Spell s = Casting.compile(src);
            give(p, SpellPages.fitting(src));
            c.getSource().sendSuccess(() -> Component.literal("已给予术式载页《" + s.name() + "》"), false);
            return 1;
        } catch (LogosError e) {
            Casting.message(p, e);
            return 0;
        }
    }

    private static int examples(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal("示例术式（/logos book <名字>，或编辑 " + spellDir() + " 下的文件后 /logos load <名字>）:")
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
        String why = dev.logos.runtime.Colossi.summon(p, p.level(), at, name);
        if (why != null) {
            c.getSource().sendFailure(net.minecraft.network.chat.Component.literal(why));
            return 0;
        }
        c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("⛨ 炼金术巨像已启动：右键乘上肩膀，潜行右键打开炼金道具槽，乘坐时按 V 切换驾驶"), false);
        return 1;
    }

    /** Debug: what each of the player's mana lifeforms is doing and how it scores its choices. */
    private static int mind(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p = c.getSource().getPlayerOrException();
        int n = 0;
        for (var m : dev.logos.runtime.Familiars.of(p)) {
            if (m instanceof dev.logos.runtime.AlchemicalColossus col) {
                n++;
                String mind = col.isDormant() || col.mind() == null ? "停机" : col.mind().describe();
                c.getSource().sendSuccess(() -> col.status().copy().append(net.minecraft.network.chat.Component.literal("\n  " + mind)), false);
                continue;
            }
            if (!(m instanceof dev.logos.runtime.ManaLifeform lf)) continue;
            n++;
            String mind = lf.isDormant() || lf.mind() == null ? "沉寂" : lf.mind().describe();
            c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal("✦ " + lf.getName().getString() + "（" + lf.role().zh + "，" + lf.temperamentZh() + "）\n  " + mind), false);
        }
        if (n == 0) c.getSource().sendFailure(net.minecraft.network.chat.Component.literal("附近没有你的魔力生命体或巨像"));
        return n;
    }

    private static int anchors(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        var all = dev.logos.runtime.Anchors.of(p).all();
        if (all.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("⚓ 还没有锚点：拿着锚钉右键方块立下，或在术式里用 (anchor ^名字 位置)"), false);
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.literal("⚓ 锚点 " + all.size() + " / " + dev.logos.LogosConfig.maxAnchors()), false);
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
        give(p, new ItemStack(LogosMod.SPELL_PAGE.get(), 8));
        give(p, new ItemStack(LogosMod.ANCHOR_NAIL.get(), 16));
        give(p, new ItemStack(Items.WRITABLE_BOOK));
        c.getSource().sendSuccess(() -> Component.literal("已给予：大师法杖（挂着 " + onWand + " 篇示例术式）、法式典籍（其余 " + (pages.size() - onWand)
                + " 篇，放在副手即可使用）、法式编纂台、术式拓印台、8 张空白术式载页、16 枚锚钉。按 R 打开术式轮盘选择术式。")
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int runes(CommandContext<CommandSourceStack> c, String name) {
        CommandSourceStack s = c.getSource();
        if (name != null) {
            Rune r = Runes.get(name);
            if (r == null) {
                s.sendFailure(Component.literal("没有符文 " + name));
                return 0;
            }
            s.sendSuccess(() -> Component.literal("[" + r.category.zh + "] ").withStyle(r.category.color)
                    .append(Component.literal(r.signature()).withStyle(ChatFormatting.WHITE)), false);
            s.sendSuccess(() -> Component.literal("  " + r.doc + " · 稳定度 " + Node.fmt(r.stability)).withStyle(ChatFormatting.GRAY), false);
            for (Rune.EventSpec e : r.events.values()) {
                s.sendSuccess(() -> Component.literal("  (" + e.name() + " …) " + e.doc() + (e.bindsTarget() ? "，提供 target" : ""))
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
        s.sendSuccess(() -> Component.literal("[元素] physical fire frost lightning void  [状态] burning frozen #任意印记").withStyle(ChatFormatting.GRAY), false);
        s.sendSuccess(() -> Component.literal("/logos runes <名字> 查看插槽与说明").withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    private static int manaInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        PlayerMagic m = PlayerMagic.of(c.getSource().getPlayerOrException());
        c.getSource().sendSuccess(() -> Component.literal("魔力 " + Node.fmt(m.mana) + " / " + Node.fmt(m.maxMana) + " · 稳定度上限 " + m.stability)
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int passives(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var list = SpellBag.passives(c.getSource().getPlayerOrException());
        if (list.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal("主手和副手的法杖、法式典籍里都没有被动术式。把带 :trigger hurt/attack/kill 的术式载页放进去，拿在任一只手上即可"), false);
            return 1;
        }
        for (var e : list) {
            Spell s = e.spell();
            int cap = e.tier().capacity();
            String inject = s.mana() > cap ? Node.fmt(s.mana()) + "（载页承载 " + cap + "）" : Node.fmt(s.mana());
            c.getSource().sendSuccess(() -> Component.literal("⚑ 《" + s.name() + "》 " + Spell.triggerZh(s.trigger()) + " · 注入 " + inject)
                    .withStyle(ChatFormatting.AQUA), false);
        }
        return 1;
    }

    private static void give(ServerPlayer p, ItemStack stack) {
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }
}

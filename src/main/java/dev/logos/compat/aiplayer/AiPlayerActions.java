package dev.logos.compat.aiplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lowdragmc.lowdraglib2.Platform;

import dev.logos.LogosMod;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.client.ClientCharges;
import dev.logos.client.ClientHooks;
import dev.logos.client.ManaHudLayer;
import dev.logos.client.SageClient;
import dev.logos.graph.GraphBuilder;
import dev.logos.graph.SpellGraph;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellBag;
import dev.logos.item.SpellPages;
import dev.logos.item.StaffItem;
import dev.logos.item.WandItem;
import dev.logos.lang.Examples;
import dev.logos.lang.LogosError;
import dev.logos.lang.Spell;
import dev.logos.net.InscribePayload;
import dev.logos.net.SelectSpellPayload;
import dev.logos.runtime.Casting;
import dev.logos.sage.SageAgent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * What the AI Player mod may do with Logos things without a model: the handler class that
 * {@code assets/logos/ai_usage/*.md} name. AI Player loads it by name only when it is installed and
 * an action is used, so Logos does not depend on it; the only types they share are Gson's and the
 * JDK's. {@code game} runs any function of that mod's api (walking, right-clicking, reading the
 * inventory) and answers as it does, with {@code ok} and {@code result} or {@code error} and {@code message}.
 * Actions run off the game thread, on the thread that plays, and hop onto it to read the world.
 */
public final class AiPlayerActions {
    private static final String TABLE = LogosMod.MODID + ":inscription_table";
    private static final int TABLE_RADIUS = 48;
    private static final long GAME_WAIT_S = 15, SAGE_WAIT_S = 150, PLACE_WAIT_MS = 3_000, INSCRIBE_WAIT_MS = 6_000, TAKE_WAIT_MS = 3_000;
    /** The source of the page these actions wrote last, so that the next action can go on with it without naming it. */
    private static volatile @Nullable String lastWritten;
    private static final double IN_REACH_SQR = 3.5 * 3.5;
    private static final long REFUSAL_SHOWS_MS = 1_200, SNEAK_SETTLE_MS = 200, WINDOW_WAIT_MS = 3_000, SYNC_WAIT_MS = 2_500, CHARGE_START_MS = 1_500, CHARGE_MAX_MS = 60_000, RELEASE_WAIT_MS = 1_500;

    /** A failure as the AI reads it: a kind, what happened and what to do next. */
    static final class Failure extends RuntimeException {
        final String kind;
        final @Nullable String hint;

        Failure(String kind, String message, @Nullable String hint) {
            super(message);
            this.kind = kind;
            this.hint = hint;
        }

        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("error", kind);
            out.addProperty("message", getMessage());
            if (hint != null) out.addProperty("hint", hint);
            return out;
        }
    }

    private AiPlayerActions() {}

    public static JsonElement invoke(String action, JsonObject args, BiFunction<String, JsonObject, JsonObject> game) {
        try {
            return switch (action) {
                case "examples" -> examples();
                case "check" -> check(need(args, "source"));
                case "read_pages" -> readPages();
                case "ask_sage" -> askSage(need(args, "request"), optional(args, "source"), capacityFor(args, 0));
                case "inscribe" -> inscribe(game, args, sourceOf(args));
                case "write_spell" -> writeSpell(game, args);
                case "hang_on_wand" -> hangOnWand(game, args);
                case "cast" -> cast(game, args);
                case "has_spell" -> hasSpell(args);
                default -> throw new Failure("bad_argument", "no action '" + action + "'",
                        "the manuals list examples, check, ask_sage, inscribe, write_spell, read_pages, hang_on_wand, cast and has_spell");
            };
        } catch (Failure f) {
            return f.json();
        } catch (LogosError e) {
            return new Failure("compile_error", e.describe(), "fix the source; check compiles it without writing anything").json();
        }
    }

    // ---- reading ----

    private static JsonElement examples() {
        JsonArray out = new JsonArray();
        for (Map.Entry<String, String> e : Examples.ALL.entrySet()) {
            JsonObject row;
            try {
                Spell spell = Casting.compile(e.getValue());
                row = SpellText.summary(spell);
                row.addProperty("name", Component.translatableWithFallback("logos.example." + e.getKey(), spell.name()).getString());
            } catch (LogosError ex) {
                row = new JsonObject();
                row.addProperty("broken", ex.describe());
            }
            row.addProperty("id", e.getKey());
            row.addProperty("note", SpellText.note(e.getValue()));
            out.add(row);
        }
        JsonObject result = new JsonObject();
        result.add("examples", out);
        result.addProperty("note", "inscribe with example set to an id copies it onto a blank page of the grade page_item or better");
        return result;
    }

    private static JsonElement check(String source) {
        JsonObject out = SpellText.summary(Casting.compile(source));
        out.addProperty("ok", true);
        return out;
    }

    private static JsonElement readPages() {
        return client(() -> {
            Inventory inventory = Minecraft.getInstance().player.getInventory();
            JsonArray pages = new JsonArray();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (!SpellPages.isPage(stack)) continue;
                PageTier tier = SpellPages.tier(stack);
                JsonObject row = new JsonObject();
                row.addProperty("slot", i);
                row.addProperty("item", itemId(stack));
                row.addProperty("count", stack.getCount());
                row.addProperty("grade", tier == null ? "" : tier.id);
                row.addProperty("capacity", tier == null ? 0 : tier.capacity());
                Spell spell = SpellPages.spell(stack);
                if (spell == null) {
                    row.addProperty("blank", SpellPages.isBlank(stack));
                } else {
                    JsonObject summary = SpellText.summary(spell);
                    summary.entrySet().forEach(e -> row.add(e.getKey(), e.getValue()));
                    row.addProperty("name", SpellPages.name(stack, spell).getString());
                    row.addProperty("source", spell.source());
                }
                pages.add(row);
            }
            JsonObject out = new JsonObject();
            out.add("pages", pages);
            JsonArray wands = new JsonArray();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (isWand(stack)) wands.add(wandRow(i, stack));
            }
            out.add("wands", wands);
            String hud = ManaHudLayer.line();
            if (hud != null) out.addProperty("mana_hud", hud);
            return out;
        });
    }

    // ---- the sage ----

    /**
     * Asks the book sprite the way the editor's panel does: the server's sage when it offers one, else
     * the player's own endpoint. One question, no history; the answer is the source it handed in.
     */
    private static JsonObject askSage(String request, @Nullable String current, int capacity) {
        CompletableFuture<SageAgent.Outcome> answer = new CompletableFuture<>();
        client(() -> {
            SageClient.ask(List.of(), request, current == null ? "" : current, capacity, false, (key, arg) -> {}, answer::complete);
            return null;
        });
        SageAgent.Outcome out;
        try {
            out = answer.get(SAGE_WAIT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new Failure("sage_timeout", "the book sprite gave no answer within " + SAGE_WAIT_S + " seconds", "try once more, or copy an example");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted", "stopped while waiting for the book sprite", null);
        } catch (ExecutionException e) {
            throw new Failure("sage_failed", String.valueOf(e.getCause()), null);
        }
        if (!out.ok()) {
            String message = Component.translatable(out.error(), out.detail()).getString();
            boolean off = out.error().endsWith(".unconfigured") || out.error().endsWith(".server_off");
            throw new Failure(off ? "sage_unavailable" : "sage_failed", message, off
                    ? "no sage endpoint is set up here; copy an example with inscribe, or write the source yourself and check it"
                    : "try again with a fuller request, or copy an example");
        }
        if (out.source().isBlank()) {
            throw new Failure("sage_only_talked", out.text(), "it did not hand in a spell; ask again with a fuller request, or copy an example");
        }
        JsonObject result = SpellText.summary(Casting.compile(out.source()));
        result.addProperty("source", out.source());
        result.addProperty("sage_said", out.text());
        return result;
    }

    // ---- the table ----

    private static JsonElement writeSpell(BiFunction<String, JsonObject, JsonObject> game, JsonObject args) {
        JsonObject written = askSage(need(args, "request"), null, capacityFor(args, 0));
        JsonObject out = inscribe(game, args, written.get("source").getAsString());
        out.add("sage_said", written.get("sage_said"));
        return out;
    }

    /** Lays a blank page on the table, has the server write the spell onto it, and takes the page back. */
    private static JsonObject inscribe(BiFunction<String, JsonObject, JsonObject> game, JsonObject args, String source) {
        Spell spell = Casting.compile(source);
        ItemStack blank = blankOrFetched(game, optional(args, "page"), spell.mana());
        String blankId = itemId(blank);
        PageTier tier = SpellPages.tier(blank);
        closeEditorIfOpen(game);
        Table found = tableAt(game, args);
        BlockPos table = found.pos();
        // Within reach already, there is nothing to walk.
        if (!client(() -> Minecraft.getInstance().player.distanceToSqr(table.getCenter()) <= IN_REACH_SQR)) {
            call(game, "goto", obj("x", table.getX(), "z", table.getZ(), "y", table.getY(), "range", 2, "timeout_s", 120));
        }
        if (!client(() -> tablePage(table).isEmpty())) {
            takeBack(game, table);
        }
        call(game, "useOn", obj("x", table.getX(), "y", table.getY(), "z", table.getZ(), "item", blankId));
        closeEditorIfOpen(game);
        if (!waitFor(() -> SpellPages.isBlank(tablePage(table)), PLACE_WAIT_MS)) {
            reflexCheck(game);
            throw new Failure("page_not_placed", "the blank page did not land on the table",
                    "stand within reach of the table with the page in hand and try again");
        }
        CompoundTag graph = client(() -> graphTag(spell));
        // The server takes a spell only from someone who really opened the editor, so this goes the way a person does.
        openEditor(game, table);
        client(() -> {
            ClientHooks.sendToServer(new InscribePayload(table, source, graph, ""));
            return null;
        });
        BooleanSupplier onThePage = () -> source.equals(tablePage(table).get(LogosMod.SPELL_SOURCE.get()));
        boolean written = waitFor(onThePage, REFUSAL_SHOWS_MS);
        // The server says above the hotbar why it refused, such as a claim on the table, and those words last only a few seconds.
        String refused = written ? null : actionBar(game);
        if (!written && refused == null) written = waitFor(onThePage, INSCRIBE_WAIT_MS);
        closeEditorIfOpen(game);
        if (!written) {
            takeBack(game, table);
            throw new Failure("not_inscribed", refused != null ? refused : "the server did not write the spell onto the page",
                    "the compiler's message is in chat; check the source and try again within 8 blocks of the table");
        }
        takeBack(game, table);
        // Put down only for this: it goes back into the pack, as a crafting table does.
        if (found.ours()) {
            call(game, "dig", obj("x", table.getX(), "y", table.getY(), "z", table.getZ()));
            quietly(game, "pickupItems", obj("radius", 6, "item", TABLE));
        }
        ItemStack page = client(() -> carried(stack -> SpellPages.isInscribed(stack) && source.equals(stack.get(LogosMod.SPELL_SOURCE.get()))));
        if (page == null) {
            throw new Failure("page_lost", "the inscribed page is not in the inventory", "look for it on the ground near the table");
        }
        lastWritten = source;
        JsonObject out = SpellText.summary(spell);
        out.addProperty("ok", true);
        out.addProperty("page", itemId(page));
        out.addProperty("page_name", page.getHoverName().getString());
        out.addProperty("grade", tier == null ? "" : tier.id);
        out.addProperty("capacity", tier == null ? 0 : tier.capacity());
        if (tier != null && spell.mana() > tier.capacity()) {
            out.addProperty("warning", "the spell injects " + spell.mana() + " but this page holds " + tier.capacity() + "; a cast gets the page's capacity");
        }
        return out;
    }

    /** Sneak + right-click takes the page lying on the table; it only works holding a page or with both hands empty. */
    private static void takeBack(BiFunction<String, JsonObject, JsonObject> game, BlockPos table) {
        ItemStack anyPage = client(() -> carried(SpellPages::isPage));
        if (anyPage != null) {
            call(game, "equip", obj("item", itemId(anyPage)));
        } else {
            Integer empty = client(AiPlayerActions::emptyHotbarSlot);
            if (empty == null) {
                throw new Failure("hands_full", "taking the page back needs a page in hand or an empty hotbar slot", "drop or store something first");
            }
            call(game, "setQuickBarSlot", obj("slot", empty));
            if (client(() -> !Minecraft.getInstance().player.getOffhandItem().isEmpty())) {
                call(game, "unequip", obj("dest", "off-hand"));
            }
        }
        holdSneak(game);
        try {
            call(game, "activateBlock", obj("x", table.getX(), "y", table.getY(), "z", table.getZ()));
        } finally {
            quietly(game, "setControlState", obj("control", "sneak", "state", false));
        }
        closeEditorIfOpen(game);
        if (!waitFor(() -> tablePage(table).isEmpty(), TAKE_WAIT_MS)) {
            reflexCheck(game);
            throw new Failure("page_stuck", "the page stayed on the table", "sneak and right-click the table holding a page or with empty hands");
        }
    }

    /** A plain right-click on a table with a page on it: the client shows the editor and the server notes that it was opened. */
    private static void openEditor(BiFunction<String, JsonObject, JsonObject> game, BlockPos table) {
        call(game, "activateBlock", obj("x", table.getX(), "y", table.getY(), "z", table.getZ()));
        if (!waitFor(() -> Minecraft.getInstance().screen != null, WINDOW_WAIT_MS)) {
            reflexCheck(game);
            String why = actionBar(game);
            throw new Failure("editor_not_open", why != null ? why : "the table's editor did not open", "something may protect this table: a claim, spawn protection or adventure mode");
        }
    }

    /** A right-click that missed its mark opens the table's editor; the AI never uses it, so it goes away again. */
    private static void closeEditorIfOpen(BiFunction<String, JsonObject, JsonObject> game) {
        if (client(() -> Minecraft.getInstance().screen != null)) {
            call(game, "closeWindow", new JsonObject());
        }
    }

    /** A table to write at, and whether it was put down just for this. */
    private record Table(BlockPos pos, boolean ours) {}

    /**
     * The table given by x, y, z, or the nearest one around, or one put down from the pack when none
     * stands near. It must be an inscription table the client can see.
     */
    private static Table tableAt(BiFunction<String, JsonObject, JsonObject> game, JsonObject args) {
        BlockPos pos = null;
        boolean ours = false;
        if (args.has("x") && args.has("y") && args.has("z")) {
            BlockPos given = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
            // Coordinates can be out of date, as when they come from memory; then the nearest table will do just as well.
            if (client(() -> Minecraft.getInstance().level.getBlockEntity(given) instanceof InscriptionTableBlockEntity)) pos = given;
        }
        if (pos == null) {
            JsonArray wanted = new JsonArray();
            wanted.add(TABLE);
            JsonObject found = call(game, "findBlocks", obj("blocks", wanted, "radius", TABLE_RADIUS, "count", 1));
            JsonArray blocks = found.getAsJsonArray("blocks");
            JsonArray at;
            if (blocks != null && !blocks.isEmpty()) {
                at = blocks.get(0).getAsJsonObject().getAsJsonArray("pos");
            } else if (client(() -> carried(stack -> itemId(stack).equals(TABLE)) != null)) {
                at = call(game, "placeNearby", obj("item", TABLE)).getAsJsonArray("pos");
                ours = true;
            } else {
                throw new Failure("no_table", "no inscription table within " + TABLE_RADIUS + " blocks, and none is carried",
                        "obtain " + TABLE + " first; this action then puts it down and takes it back by itself");
            }
            pos = new BlockPos(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt());
        }
        BlockPos table = pos;
        if (!waitFor(() -> Minecraft.getInstance().level.getBlockEntity(table) instanceof InscriptionTableBlockEntity, PLACE_WAIT_MS)) {
            throw new Failure("not_a_table", "there is no inscription table at " + pos.toShortString(), "findBlocks with " + TABLE + " shows where one is");
        }
        return new Table(pos, ours);
    }

    /** The blank page named, or the lowest carried grade that holds the spell's mana, or the highest carried. */
    private static ItemStack chooseBlank(@Nullable String wanted, double mana) {
        ItemStack page = client(() -> {
            if (wanted != null) {
                ItemStack named = carried(stack -> SpellPages.isBlank(stack) && itemId(stack).equals(wanted.contains(":") ? wanted : LogosMod.MODID + ":" + wanted));
                if (named == null) throw new Failure("no_blank_page", "no blank page " + wanted + " is carried", "obtain it, or leave page out to use any carried grade");
                return named;
            }
            List<PageTier> grades = new ArrayList<>();
            List<ItemStack> blanks = new ArrayList<>();
            Inventory inventory = Minecraft.getInstance().player.getInventory();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack stack = inventory.getItem(i);
                if (SpellPages.isBlank(stack) && SpellPages.tier(stack) != null) {
                    grades.add(SpellPages.tier(stack));
                    blanks.add(stack);
                }
            }
            PageTier fit = SpellText.fit(grades, mana);
            return fit == null ? null : blanks.get(grades.indexOf(fit));
        });
        if (page == null) {
            throw new Failure("no_blank_page", "no blank spell page is carried",
                    "obtain " + LogosMod.MODID + ":" + PageTier.fitting(mana).blankId() + " first, or take one from a chest");
        }
        return page;
    }

    /** A carried blank page, or, when there is none, one fetched: the page this spell needs is paper and gold at the lowest grade. */
    private static ItemStack blankOrFetched(BiFunction<String, JsonObject, JsonObject> game, @Nullable String wanted, double mana) {
        try {
            return chooseBlank(wanted, mana);
        } catch (Failure none) {
            String item = wanted != null ? (wanted.contains(":") ? wanted : LogosMod.MODID + ":" + wanted) : LogosMod.MODID + ":" + PageTier.fitting(mana).blankId();
            JsonObject got = game.apply("obtain", obj("item", item, "count", 1));
            if (got == null || !got.has("ok") || !got.get("ok").getAsBoolean()) {
                String why = got != null && got.has("message") ? got.get("message").getAsString() : "obtain failed";
                throw new Failure("no_blank_page", none.getMessage() + ", and fetching " + item + " failed: " + why, none.hint);
            }
            return chooseBlank(wanted, mana);
        }
    }

    /** The capacity of the page the spell will go on, so the sage writes within it; {@code fallback} when none is carried. */
    private static int capacityFor(JsonObject args, int fallback) {
        try {
            PageTier tier = SpellPages.tier(chooseBlank(optional(args, "page"), 0));
            return tier == null ? fallback : tier.capacity();
        } catch (Failure none) {
            return fallback;
        }
    }

    /** The drawing the editor would show for this spell, so a page an AI wrote opens like any other; empty if it cannot be built. */
    private static CompoundTag graphTag(Spell spell) {
        try {
            SpellGraph graph = GraphBuilder.build(spell);
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, Platform.getFrozenRegistry());
            graph.graphModel.serialize(out);
            return out.buildResult();
        } catch (RuntimeException e) {
            LogosMod.LOGGER.warn("Could not draw the graph of an AI-written spell; the page keeps its source only: {}", e.toString());
            return new CompoundTag();
        }
    }

    // ---- the wand ----

    /**
     * A condition for a plan: whether the spell is written on a carried page or a wand, or, with on_wand, on a wand.
     * With no spell named it means the page written last, whose name a plan cannot know when the sprite chose it.
     * This runs on the game thread in the middle of a check, so it must not wait.
     */
    private static JsonObject hasSpell(JsonObject args) {
        String wanted = optional(args, "spell");
        boolean onWandOnly = args.has("on_wand") && args.get("on_wand").getAsBoolean();
        String source = lastWritten;
        return client(() -> {
            java.util.function.Predicate<ItemStack> isIt = page -> SpellPages.isInscribed(page)
                    && (wanted != null ? named(page, wanted) : source != null && source.equals(page.get(LogosMod.SPELL_SOURCE.get())));
            boolean onWand = carried(stack -> isWand(stack) && PageContainer.pages(stack).stream().anyMatch(isIt)) != null;
            boolean onPage = carried(isIt) != null;
            String what = wanted != null ? "'" + wanted + "'" : "the page written last";
            JsonObject out = new JsonObject();
            out.addProperty("met", onWand || (onPage && !onWandOnly));
            out.addProperty("on_wand", onWand);
            out.addProperty("on_carried_page", onPage);
            if (!onWand && !(onPage && !onWandOnly)) {
                out.addProperty("why", onPage ? what + " is on a carried page but not on a wand" : "no carried page or wand has " + what);
            }
            return out;
        });
    }

    /**
     * The spell a request for no spell in particular means: the page written last by these actions, if it is still
     * carried, else the only written page carried. Null when that does not settle it.
     */
    private static @Nullable String nameOfTheObviousPage() {
        Inventory inventory = Minecraft.getInstance().player.getInventory();
        ItemStack last = null, only = null;
        int written = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!SpellPages.isInscribed(stack)) continue;
            written++;
            only = stack;
            if (lastWritten != null && lastWritten.equals(stack.get(LogosMod.SPELL_SOURCE.get()))) last = stack;
        }
        ItemStack page = last != null ? last : written == 1 ? only : null;
        Spell spell = page == null ? null : SpellPages.spell(page);
        return spell == null ? null : spell.name();
    }

    /** Client thread. The name of the spell these actions wrote last, if its page is still carried or on a carried wand. */
    private static @Nullable String nameOfLastWritten() {
        String source = lastWritten;
        if (source == null) return null;
        ItemStack loose = carried(stack -> SpellPages.isInscribed(stack) && source.equals(stack.get(LogosMod.SPELL_SOURCE.get())));
        ItemStack wand = carried(stack -> isWand(stack) && pageWithSource(stack, source) != null);
        Spell spell = loose != null ? SpellPages.spell(loose) : wand != null ? SpellPages.spell(pageWithSource(wand, source)) : null;
        return spell == null ? null : spell.name();
    }

    /** Puts a written page into a free slot of a carried wand, through the wand's own window. */
    private static JsonObject hangOnWand(BiFunction<String, JsonObject, JsonObject> game, JsonObject args) {
        String named = optional(args, "spell");
        String wanted = named != null ? named : client(AiPlayerActions::nameOfTheObviousPage);
        if (wanted == null) {
            throw new Failure("no_page", "which page is not clear: no page was written just now and there is not exactly one written page carried", "give spell; read_pages lists them");
        }
        String replace = optional(args, "replace");
        String chosen = optional(args, "wand");
        String wandId = client(() -> {
            ItemStack wand = carried(stack -> isWand(stack) && (chosen == null || itemId(stack).equals(chosen.contains(":") ? chosen : LogosMod.MODID + ":" + chosen)));
            return wand == null ? null : itemId(wand);
        });
        if (wandId == null) throw noWand();
        JsonObject already = client(() -> {
            ItemStack wand = carried(stack -> isWand(stack) && pageNamed(stack, wanted) != null);
            return wand == null ? null : wandResult(wand, pageNamed(wand, wanted));
        });
        if (already != null) {
            already.addProperty("note", "it was already on this wand");
            return already;
        }
        String source = client(() -> {
            ItemStack page = carried(stack -> SpellPages.isInscribed(stack) && named(stack, wanted));
            return page == null ? null : page.get(LogosMod.SPELL_SOURCE.get());
        });
        if (source == null) {
            throw new Failure("no_page", "no written page of the spell '" + wanted + "' is carried", "read_pages lists the pages; inscribe at an inscription table writes one");
        }
        call(game, "equip", obj("item", wandId));
        openWandWindow(game);
        try {
            int[] move = client(() -> wandMove(source, replace));
            if (move[2] >= 0) call(game, "putAway", obj("slot", move[2]));
            call(game, "moveSlotItem", obj("from", move[0], "to", move[1]));
        } finally {
            quietly(game, "closeWindow", new JsonObject());
        }
        if (!waitFor(() -> carried(stack -> isWand(stack) && pageWithSource(stack, source) != null) != null, SYNC_WAIT_MS)) {
            throw new Failure("not_hung", "the page did not stay on the wand", "read_pages shows where it is");
        }
        return client(() -> {
            ItemStack wand = carried(stack -> isWand(stack) && pageWithSource(stack, source) != null);
            return wandResult(wand, pageWithSource(wand, source));
        });
    }

    /** Client thread. From which slot of the open wand window to which the page goes, and the slot to empty first, or -1. */
    private static int[] wandMove(String source, @Nullable String replace) {
        AbstractContainerMenu menu = Minecraft.getInstance().player.containerMenu;
        int from = -1, to = -1, out = -1;
        List<String> on = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            if (slot.container instanceof Inventory) {
                if (from < 0 && SpellPages.isInscribed(stack) && source.equals(stack.get(LogosMod.SPELL_SOURCE.get()))) from = i;
                continue;
            }
            if (stack.isEmpty()) {
                if (to < 0) to = i;
                continue;
            }
            Spell spell = SpellPages.spell(stack);
            if (spell != null) on.add(SpellPages.name(stack, spell).getString());
            if (out < 0 && replace != null && named(stack, replace)) out = i;
        }
        if (from < 0) throw new Failure("no_page", "the page is not among the window's slots", null);
        if (to >= 0) return new int[] {from, to, -1};
        if (out >= 0) return new int[] {from, out, out};
        throw new Failure("wand_full", "every slot of this wand is taken: " + String.join(", ", on), "give replace, the name of a spell to take off, or use another wand");
    }

    /** Sneak + right-click opens the slots of the wand in hand; sneak must have reached the server before the click. */
    private static void openWandWindow(BiFunction<String, JsonObject, JsonObject> game) {
        closeEditorIfOpen(game);
        holdSneak(game);
        try {
            call(game, "activateItem", new JsonObject());
            if (!waitFor(() -> Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>, WINDOW_WAIT_MS)) {
                reflexCheck(game);
                throw new Failure("window_not_open", "the wand's slots did not open", "look at open air and try again");
            }
        } finally {
            quietly(game, "deactivateItem", new JsonObject());
            quietly(game, "setControlState", obj("control", "sneak", "state", false));
        }
    }

    /**
     * Takes up the wand with the spell, hanging its page first if it is only carried, selects it, aims,
     * holds right-click until the server says the charge is full and lets go, which casts.
     */
    private static JsonObject cast(BiFunction<String, JsonObject, JsonObject> game, JsonObject args) {
        closeEditorIfOpen(game);
        String named = optional(args, "spell");
        String wanted = named != null ? named : client(AiPlayerActions::nameOfLastWritten);
        if (wanted != null && !client(() -> carried(stack -> isWand(stack) && pageNamed(stack, wanted) != null) != null)) {
            hangOnWand(game, args);
        }
        String wandId = client(() -> {
            ItemStack held = Minecraft.getInstance().player.getMainHandItem();
            if (wanted == null && isWand(held)) return itemId(held);
            ItemStack wand = carried(stack -> isWand(stack) && (wanted == null ? !PageContainer.pages(stack).isEmpty() : pageNamed(stack, wanted) != null));
            return wand == null ? null : itemId(wand);
        });
        if (wandId == null) throw noWand();
        call(game, "equip", obj("item", wandId));
        waitFor(() -> isWand(Minecraft.getInstance().player.getMainHandItem()), SYNC_WAIT_MS);
        // Where the spell lies among the wand's entries, and whether the wheel has to be turned to it.
        Spell spell = client(() -> {
            var player = Minecraft.getInstance().player;
            ItemStack wand = player.getMainHandItem();
            List<SpellBag.Entry> entries = SpellBag.entries(player, wand);
            if (entries.isEmpty()) throw new Failure("no_spell", "this wand carries no written page", "hang_on_wand puts one on it");
            int at = Math.floorMod(wand.getOrDefault(LogosMod.SELECTED.get(), 0), entries.size());
            if (wanted != null) {
                at = -1;
                for (int i = 0; i < entries.size() && at < 0; i++) if (named(entries.get(i).page(), wanted)) at = i;
                if (at < 0) throw new Failure("no_spell", "the wand in hand does not carry '" + wanted + "'", "read_pages shows what each wand carries");
            }
            if (at != wand.getOrDefault(LogosMod.SELECTED.get(), 0) || wand.has(LogosMod.VOICE_SELECTED.get())) ClientHooks.sendToServer(new SelectSpellPayload(at));
            return entries.get(at).spell();
        });
        if (wanted != null && !waitFor(() -> {
            var player = Minecraft.getInstance().player;
            SpellBag.Entry now = SpellBag.selected(player, player.getMainHandItem());
            return now != null && named(now.page(), wanted);
        }, SYNC_WAIT_MS)) {
            throw new Failure("not_selected", "the wand did not turn to '" + wanted + "'", "try again");
        }
        JsonObject out = SpellText.summary(spell);
        if (spell.isPassive()) {
            out.addProperty("ok", true);
            out.addProperty("passive", true);
            out.addProperty("note", "it needs no casting: it fires by itself, on " + spell.trigger() + ", while this wand is held");
            return out;
        }
        aim(game, args);
        long started = System.currentTimeMillis();
        call(game, "activateItem", new JsonObject());
        try {
            if (!waitFor(() -> ClientCharges.of(Minecraft.getInstance().player) != null, CHARGE_START_MS)) {
                reflexCheck(game);
                closeEditorIfOpen(game);
                throw new Failure("cast_refused", refusal(game), "that is what the game says above the hotbar: usually too little mana, which comes back with time; or look away from a block that takes clicks");
            }
            while (true) {
                if (args.has("entity")) aim(game, args);
                ClientCharges.State charge = client(() -> ClientCharges.of(Minecraft.getInstance().player));
                if (charge == null) {
                    reflexCheck(game);
                    throw new Failure("cast_interrupted", "the charge ended before it was full", refusal(game));
                }
                if (charge.full) break;
                if (System.currentTimeMillis() - started > CHARGE_MAX_MS) {
                    throw new Failure("cast_timeout", "the charge was not full after " + CHARGE_MAX_MS / 1000 + " seconds", refusal(game));
                }
                pause(100);
            }
            aim(game, args);
        } finally {
            quietly(game, "deactivateItem", new JsonObject());
        }
        boolean released = waitFor(() -> ClientCharges.of(Minecraft.getInstance().player) == null, RELEASE_WAIT_MS);
        out.addProperty("ok", released);
        out.addProperty("seconds", Math.round((System.currentTimeMillis() - started) / 100.0) / 10.0);
        if (!released) out.addProperty("warning", "the charge did not end when let go");
        String hud = client(ManaHudLayer::line);
        if (hud != null) out.addProperty("mana_hud", hud);
        return out;
    }

    /** Turns toward the entity or point the action names, if any. */
    private static void aim(BiFunction<String, JsonObject, JsonObject> game, JsonObject args) {
        Vec3 at = null;
        if (args.has("entity")) {
            int id = args.get("entity").getAsInt();
            at = client(() -> {
                Entity entity = Minecraft.getInstance().level.getEntity(id);
                return entity == null ? null : entity.getBoundingBox().getCenter();
            });
            if (at == null) throw new Failure("no_target", "there is no entity " + id + " any more", "entities lists what is around");
        } else if (args.has("x") && args.has("y") && args.has("z")) {
            at = new Vec3(args.get("x").getAsDouble(), args.get("y").getAsDouble(), args.get("z").getAsDouble());
        }
        if (at != null) call(game, "lookAt", obj("x", at.x, "y", at.y, "z", at.z));
    }

    private static String refusal(BiFunction<String, JsonObject, JsonObject> game) {
        String bar = actionBar(game);
        return bar == null ? "the wand did not start charging" : bar;
    }

    /** The line above the hotbar, where Logos says why it refused something. */
    private static @Nullable String actionBar(BiFunction<String, JsonObject, JsonObject> game) {
        JsonObject out = game.apply("status", new JsonObject());
        if (out == null || !out.has("result") || !out.get("result").isJsonObject()) return null;
        JsonObject status = out.getAsJsonObject("result");
        return status.has("action_bar") ? status.get("action_bar").getAsString() : null;
    }

    private static Failure noWand() {
        return new Failure("no_wand", "no wand with a written page, or none at all, is carried",
                "obtain a crafted one such as " + LogosMod.MODID + ":oak_wand, or take one from a chest; then hang_on_wand");
    }

    /** A wand that takes the charge-and-release route; a staff is charged by a different press. */
    private static boolean isWand(ItemStack stack) {
        return stack.getItem() instanceof WandItem && !(stack.getItem() instanceof StaffItem);
    }

    /** Whether a written page is the spell meant: by its own name, by its name as shown, or by its example id. */
    private static boolean named(ItemStack page, String wanted) {
        Spell spell = SpellPages.spell(page);
        if (spell == null) return false;
        return wanted.equalsIgnoreCase(spell.name()) || wanted.equalsIgnoreCase(SpellPages.name(page, spell).getString())
                || wanted.equalsIgnoreCase(Examples.idOf(page.get(LogosMod.SPELL_SOURCE.get())));
    }

    private static @Nullable ItemStack pageNamed(ItemStack wand, String wanted) {
        for (ItemStack page : PageContainer.pages(wand)) if (SpellPages.isInscribed(page) && named(page, wanted)) return page;
        return null;
    }

    private static @Nullable ItemStack pageWithSource(ItemStack wand, String source) {
        for (ItemStack page : PageContainer.pages(wand)) if (source.equals(page.get(LogosMod.SPELL_SOURCE.get()))) return page;
        return null;
    }

    private static JsonObject wandRow(int slot, ItemStack wand) {
        JsonObject row = new JsonObject();
        row.addProperty("slot", slot);
        row.addProperty("item", itemId(wand));
        row.addProperty("slots", ((WandItem) wand.getItem()).slots());
        List<String> names = new ArrayList<>();
        for (ItemStack page : PageContainer.pages(wand)) {
            Spell spell = SpellPages.isInscribed(page) ? SpellPages.spell(page) : null;
            if (spell != null) names.add(SpellPages.name(page, spell).getString());
        }
        JsonArray spells = new JsonArray();
        names.forEach(spells::add);
        row.add("spells", spells);
        if (!names.isEmpty()) row.addProperty("selected", names.get(Math.floorMod(wand.getOrDefault(LogosMod.SELECTED.get(), 0), names.size())));
        return row;
    }

    private static JsonObject wandResult(ItemStack wand, ItemStack page) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("wand", itemId(wand));
        Spell spell = SpellPages.spell(page);
        if (spell != null) {
            out.addProperty("spell", SpellPages.name(page, spell).getString());
            out.addProperty("trigger", spell.trigger());
        }
        out.addProperty("slots", ((WandItem) wand.getItem()).slots());
        out.addProperty("pages_on_it", PageContainer.pages(wand).size());
        return out;
    }

    // ---- helpers ----

    /** Holds sneak until it has had time to reach the server, so the next click counts as a sneaking one. */
    private static void holdSneak(BiFunction<String, JsonObject, JsonObject> game) {
        call(game, "setControlState", obj("control", "sneak", "state", true));
        waitFor(() -> Minecraft.getInstance().player.isShiftKeyDown(), SYNC_WAIT_MS);
        pause(SNEAK_SETTLE_MS);
    }

    /**
     * A wait that ran out may mean the body reacted to danger meanwhile and walked off. Then that is the failure to
     * report: the AI's skills start an action over after a reflex, which they would not do for the failure the wait saw.
     */
    private static void reflexCheck(BiFunction<String, JsonObject, JsonObject> game) {
        JsonObject out = game.apply("deactivateItem", new JsonObject());
        if (out != null && out.has("error") && "interrupted_by_reflex".equals(out.get("error").getAsString())) {
            throw new Failure("interrupted_by_reflex", out.has("message") ? out.get("message").getAsString() : "the body reacted to danger", "carry on once it is over");
        }
    }

    /** Lets go of something on the way out: a failure here must not hide the one that brought us here. */
    private static void quietly(BiFunction<String, JsonObject, JsonObject> game, String fn, JsonObject args) {
        game.apply(fn, args);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted", "stopped", null);
        }
    }

    private static String sourceOf(JsonObject args) {
        String example = optional(args, "example");
        if (example != null) {
            String source = Examples.ALL.get(example);
            if (source == null) throw new Failure("not_found", "no example '" + example + "'", "examples lists their ids");
            return source;
        }
        return need(args, "source");
    }

    /** Client thread. The table's page, or empty when there is none or the block is not a table. */
    private static ItemStack tablePage(BlockPos table) {
        return Minecraft.getInstance().level.getBlockEntity(table) instanceof InscriptionTableBlockEntity be ? be.page() : ItemStack.EMPTY;
    }

    /** Client thread. The first carried stack that matches, or null. */
    private static @Nullable ItemStack carried(java.util.function.Predicate<ItemStack> which) {
        Inventory inventory = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && which.test(stack)) return stack;
        }
        return null;
    }

    /** Client thread. A hotbar slot with nothing in it, the selected one first, or null. */
    private static @Nullable Integer emptyHotbarSlot() {
        Inventory inventory = Minecraft.getInstance().player.getInventory();
        if (inventory.getSelectedItem().isEmpty()) return inventory.getSelectedSlot();
        for (int i = 0; i < 9; i++) if (inventory.getItem(i).isEmpty()) return i;
        return null;
    }

    private static String itemId(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** One of the AI's functions; a failure there fails the action with the same words. */
    private static JsonObject call(BiFunction<String, JsonObject, JsonObject> game, String fn, JsonObject args) {
        JsonObject out = game.apply(fn, args);
        if (out == null || !out.has("ok") || !out.get("ok").getAsBoolean()) {
            String kind = out != null && out.has("error") ? out.get("error").getAsString() : "failed";
            String message = out != null && out.has("message") ? out.get("message").getAsString() : fn + " failed";
            throw new Failure(kind, fn + ": " + message, out != null && out.has("hint") ? out.get("hint").getAsString() : null);
        }
        return out.has("result") && out.get("result").isJsonObject() ? out.getAsJsonObject("result") : new JsonObject();
    }

    /** Runs on the game thread and waits for the answer. */
    private static <T> T client(Supplier<T> task) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) return task.get();
        try {
            return mc.submit(task).get(GAME_WAIT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("interrupted", "stopped", null);
        } catch (TimeoutException e) {
            throw new Failure("timeout", "the game did not answer within " + GAME_WAIT_S + " seconds", null);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Failure f) throw f;
            if (e.getCause() instanceof LogosError l) throw l;
            throw new Failure("game_error", String.valueOf(e.getCause()), null);
        }
    }

    /** Polls a client-thread condition until it holds or the time is up. */
    private static boolean waitFor(BooleanSupplier onClient, long millis) {
        long end = System.currentTimeMillis() + millis;
        while (true) {
            if (client(onClient::getAsBoolean)) return true;
            if (System.currentTimeMillis() >= end) return false;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Failure("interrupted", "stopped", null);
            }
        }
    }

    private static String need(JsonObject args, String key) {
        String value = optional(args, key);
        if (value == null) throw new Failure("bad_argument", "this action needs '" + key + "'", null);
        return value;
    }

    private static @Nullable String optional(JsonObject args, String key) {
        return args != null && args.has(key) && args.get(key).isJsonPrimitive() && !args.get(key).getAsString().isBlank() ? args.get(key).getAsString() : null;
    }

    private static JsonObject obj(Object... keysAndValues) {
        JsonObject out = new JsonObject();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            String key = (String) keysAndValues[i];
            Object value = keysAndValues[i + 1];
            switch (value) {
                case null -> {}
                case JsonElement json -> out.add(key, json);
                case Number n -> out.addProperty(key, n);
                case Boolean b -> out.addProperty(key, b);
                default -> out.addProperty(key, value.toString());
            }
        }
        return out;
    }
}

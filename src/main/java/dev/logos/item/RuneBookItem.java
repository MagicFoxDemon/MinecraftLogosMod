package dev.logos.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

import dev.logos.lang.Category;
import dev.logos.lang.Element;
import dev.logos.lang.Rune;
import dev.logos.lang.Runes;
import dev.logos.lang.Unlocks;
import dev.logos.runtime.RuneKnowledge;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;

/**
 * 符文之书: read, it teaches its reader words of the language they have not learned yet (符文解锁). Most books teach
 * one, drawn at random from what the reader still lacks of the book's kind: a family of runes, the motions or the
 * elements. An element's own book teaches that element, 万理之书 one word of any kind, 启悟之书 everything. Life is
 * never taught: in Logos alone only a command gives it. A book with nothing left to teach its reader is kept.
 */
public final class RuneBookItem extends Item {
    /** Every book: its registry name, what it teaches, the colour its cover is tinted in its item model, and its rarity. */
    public enum Kind {
        FORM("form_book", runes(Category.FORM), Rarity.RARE),
        TARGETING("targeting_book", runes(Category.BINDING), Rarity.UNCOMMON),
        CHANGE("change_book", runes(Category.EFFECT), Rarity.UNCOMMON),
        LAW("law_book", runes(Category.LOGIC), Rarity.UNCOMMON),
        NUMBER("number_book", runes(Category.VALUE), Rarity.UNCOMMON),
        MOTION("motion_book", key -> key.startsWith("motion:"), Rarity.UNCOMMON),
        ELEMENT("element_book", key -> key.startsWith("element:"), Rarity.RARE),
        FIRE("fire_book", Element.FIRE),
        FROST("frost_book", Element.FROST),
        LIGHTNING("lightning_book", Element.LIGHTNING),
        VOID("void_book", Element.VOID),
        ARCANA("arcana_book", Element.ARCANE),
        PRINCIPLE("principle_book", key -> true, Rarity.EPIC),
        ENLIGHTENMENT("enlightenment_book", key -> true, Rarity.EPIC);

        public final String id;
        /** The words this book can teach, before taking out what is never drawn and what the reader knows. */
        private final Predicate<String> teaches;
        public final Rarity rarity;

        Kind(String id, Predicate<String> teaches, Rarity rarity) {
            this.id = id;
            this.teaches = teaches;
            this.rarity = rarity;
        }

        Kind(String id, Element element) {
            this(id, key -> key.equals(Unlocks.element(element)), Rarity.RARE);
        }

        /** Whether this book teaches everything left at once (启悟之书) rather than one word. */
        public boolean everything() {
            return this == ENLIGHTENMENT;
        }

        /** Every word this book can teach anyone, in {@link Unlocks#all} order. */
        public List<String> pool() {
            List<String> out = new ArrayList<>();
            for (String key : Unlocks.all()) if (teaches.test(key) && !Unlocks.NOT_RANDOM.contains(key)) out.add(key);
            return out;
        }

        /** What this book still has to teach {@code p}. */
        public List<String> left(Player p) {
            List<String> out = new ArrayList<>();
            for (String key : pool()) if (!RuneKnowledge.learned(p, key)) out.add(key);
            return out;
        }

        private static Predicate<String> runes(Category family) {
            return key -> {
                if (!key.startsWith("rune:")) return false;
                Rune r = Runes.get(key.substring("rune:".length()));
                return r != null && r.category == family;
            };
        }
    }

    private final Kind kind;

    public RuneBookItem(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
    }

    public static Properties properties(Properties p, Kind kind) {
        p = p.stacksTo(16).rarity(kind.rarity);
        return kind.everything() ? p.component(net.minecraft.core.component.DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true) : p;
    }

    public Kind kind() {
        return kind;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        List<String> left = kind.left(sp);
        if (left.isEmpty()) {
            sp.sendOverlayMessage(Component.translatable("logos.rune_book.nothing_left").withStyle(ChatFormatting.GRAY));
            return InteractionResult.FAIL;
        }
        List<String> taught = kind.everything() ? left : List.of(left.get(sp.getRandom().nextInt(left.size())));
        for (String key : taught) RuneKnowledge.learn(sp, key);
        player.getItemInHand(hand).consume(1, sp);
        sp.awardStat(Stats.ITEM_USED.get(this));
        level.playSound(null, sp.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.PLAYERS, 1f, 1f);
        if (level instanceof ServerLevel server) server.sendParticles(ParticleTypes.ENCHANT, sp.getX(), sp.getY() + 1.2, sp.getZ(), 40, 0.4, 0.6, 0.4, 0.8);
        if (taught.size() == 1) {
            sp.sendSystemMessage(Component.translatable("logos.rune_book.learned", name(taught.getFirst())).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else {
            sp.sendSystemMessage(Component.translatable("logos.rune_book.learned_all", taught.size()).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /** A word as players read it: a rune's name and word, or an element or motion with its sign. */
    public static MutableComponent name(String key) {
        String word = key.substring(key.indexOf(':') + 1);
        if (key.startsWith("rune:")) {
            Rune r = Runes.get(word);
            MutableComponent name = Component.translatableWithFallback("logos.rune." + word, word);
            return r == null ? name : Component.literal("〔" + r.category.zh + "〕").withStyle(r.category.color).append(name.withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" " + word).withStyle(ChatFormatting.GRAY));
        }
        String enumName = word.toUpperCase(Locale.ROOT);
        return Component.translatable((key.startsWith("element:") ? "dev.logos.lang.Element." : "dev.logos.lang.Motion.") + enumName).withStyle(ChatFormatting.WHITE);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        builder.accept(Component.translatable("logos.rune_book." + kind.id + ".teaches").withStyle(ChatFormatting.GRAY));
        if (kind == Kind.ELEMENT || kind == Kind.PRINCIPLE || kind == Kind.ENLIGHTENMENT) {
            builder.accept(Component.translatable("logos.rune_book.no_life").withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}

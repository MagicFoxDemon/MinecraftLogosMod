package dev.logos.item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.logos.LogosMod;
import dev.logos.lang.Node;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * 分级杖饰: a wand charm of a {@link Family} at a grade (1 up). Hung on the wand in the main hand, it gives its
 * bearer the family's attribute modifiers at its grade's amounts; of several charms of one family on that
 * wand only the highest grade counts, so stacking copies gains nothing. A new kind (more damage, less cost,
 * faster charging…) is one more family over the spell attributes, with no code of its own.
 */
public class TieredCharmItem extends WandCharmItem {
    /**
     * One modifier a family gives: the attribute (looked up late, as attributes register after items are declared), how its
     * amount applies, and the language key that reads it out with the signed amount as its one argument.
     */
    public record Bonus(Supplier<? extends Holder<Attribute>> attribute, AttributeModifier.Operation operation, String key) {}

    /** A kind of charm: its id (its name is {@code logos.charm.family.<id>}) and the modifiers every grade of it gives, in the same order as each grade's amounts. */
    public record Family(String id, List<Bonus> bonuses) {
        public Component name() {
            return Component.translatable("logos.charm.family." + id);
        }

        /** The modifier id of {@code bonus}: one per family and bonus, whatever the grade. */
        Identifier modifier(int bonus) {
            return Identifier.fromNamespaceAndPath(LogosMod.MODID, "charm_" + id + "_" + bonus);
        }
    }

    private static final List<Family> FAMILIES = new ArrayList<>();

    /** Declares a family; {@link #apply} keeps its modifiers in step from then on. */
    public static Family family(String id, Bonus... bonuses) {
        Family f = new Family(id, List.of(bonuses));
        FAMILIES.add(f);
        return f;
    }

    private final Family family;
    private final int grade;
    private final double[] amounts;

    public TieredCharmItem(Properties properties, Family family, int grade, double... amounts) {
        super(properties, describe(family, grade, amounts));
        if (amounts.length != family.bonuses().size()) throw new IllegalArgumentException(family.id() + " takes " + family.bonuses().size() + " amounts");
        this.family = family;
        this.grade = grade;
        this.amounts = amounts.clone();
    }

    public Family family() {
        return family;
    }

    public int grade() {
        return grade;
    }

    private static Component describe(Family family, int grade, double[] amounts) {
        MutableComponent bonuses = Component.empty();
        for (int i = 0; i < amounts.length; i++) {
            if (i > 0) bonuses.append(Component.translatable("logos.charm.list_separator"));
            bonuses.append(Component.translatable(family.bonuses().get(i).key(), (amounts[i] >= 0 ? "+" : "") + Node.fmt(amounts[i])));
        }
        return Component.translatable("logos.charm.tiered.effect", bonuses);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, builder, flag);
        builder.accept(Component.translatable("logos.charm.tiered.highest_only", family.name()).withStyle(ChatFormatting.DARK_GRAY));
    }

    /** The best grade of each family hung on {@code wand}. */
    public static Map<Family, TieredCharmItem> best(ItemStack wand) {
        Map<Family, TieredCharmItem> best = new HashMap<>();
        if (!(wand.getItem() instanceof WandItem)) return best;
        for (ItemStack s : PageContainer.pages(wand)) {
            if (s.getItem() instanceof TieredCharmItem c) best.merge(c.family, c, (a, b) -> a.grade >= b.grade ? a : b);
        }
        return best;
    }

    /**
     * Puts on {@code p} the modifiers of the charms on its main-hand wand and takes off those of families no
     * longer there; called every tick, it writes an attribute only when its amount changes.
     */
    public static void apply(Player p) {
        Map<Family, TieredCharmItem> best = best(p.getMainHandItem());
        for (Family f : FAMILIES) {
            TieredCharmItem c = best.get(f);
            for (int i = 0; i < f.bonuses().size(); i++) {
                AttributeInstance attr = p.getAttribute(f.bonuses().get(i).attribute().get());
                if (attr == null) continue;
                Identifier id = f.modifier(i);
                AttributeModifier old = attr.getModifier(id);
                if (c == null) {
                    if (old != null) attr.removeModifier(id);
                } else if (old == null || old.amount() != c.amounts[i]) {
                    attr.addOrUpdateTransientModifier(new AttributeModifier(id, c.amounts[i], f.bonuses().get(i).operation()));
                }
            }
        }
    }
}

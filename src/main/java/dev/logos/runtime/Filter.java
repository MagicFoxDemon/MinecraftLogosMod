package dev.logos.runtime;

import java.util.List;

import dev.logos.lang.TargetKind;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A ⊙ filter value: which entities (or blocks) a selector or form admits. Written as an id or
 * {@code #tag} string, a {@code (kind …)}, or a combination of those.
 */
public interface Filter {
    boolean test(SpellInstance inst, Entity e);

    boolean testBlock(BlockState state);

    /** Whether it admits the spell's own caster where a selector may take them in: {@code (kind players)} then counts them too. */
    default boolean testSelf(SpellInstance inst, Entity caster) {
        return test(inst, caster);
    }

    Filter ANY = new Filter() {
        @Override
        public boolean test(SpellInstance inst, Entity e) {
            return true;
        }

        @Override
        public boolean testBlock(BlockState state) {
            return true;
        }
    };

    /** {@code "minecraft:zombie"} / {@code "#minecraft:undead"}: an id or tag; blank admits everything. */
    static Filter idOrTag(String s) {
        if (s == null || s.isBlank()) return ANY;
        return new Filter() {
            @Override
            public boolean test(SpellInstance inst, Entity e) {
                // A dropped item also answers to the item it carries, so "#minecraft:logs" finds log drops.
                return Targeting.entityMatches(e, s) || (e instanceof ItemEntity ie && Targeting.itemMatches(ie, s));
            }

            @Override
            public boolean testBlock(BlockState state) {
                return Targeting.blockMatches(state, s);
            }
        };
    }

    static Filter of(TargetKind k) {
        return new Filter() {
            @Override
            public boolean test(SpellInstance inst, Entity e) {
                return switch (k) {
                    case ALL -> true;
                    case ENEMIES -> Targeting.isEnemy(inst, e);
                    case HOSTILE -> e instanceof net.minecraft.world.entity.monster.Enemy && Targeting.isEnemy(inst, e);
                    case PLAYERS -> e instanceof Player && e != inst.caster;
                    case LIVING -> e instanceof LivingEntity;
                    case ITEMS -> e instanceof ItemEntity;
                    case PROJECTILES -> e instanceof Projectile;
                    case NOT_CASTER -> e != inst.caster;
                    case NONE -> false;
                };
            }

            @Override
            public boolean testSelf(SpellInstance inst, Entity caster) {
                return k == TargetKind.PLAYERS ? caster instanceof Player : test(inst, caster);
            }

            @Override
            public boolean testBlock(BlockState state) {
                return k == TargetKind.ALL;
            }
        };
    }

    static Filter anyOf(List<Filter> fs) {
        return new Filter() {
            @Override
            public boolean test(SpellInstance inst, Entity e) {
                return fs.stream().anyMatch(f -> f.test(inst, e));
            }

            @Override
            public boolean testSelf(SpellInstance inst, Entity caster) {
                return fs.stream().anyMatch(f -> f.testSelf(inst, caster));
            }

            @Override
            public boolean testBlock(BlockState state) {
                return fs.stream().anyMatch(f -> f.testBlock(state));
            }
        };
    }

    static Filter allOf(List<Filter> fs) {
        return new Filter() {
            @Override
            public boolean test(SpellInstance inst, Entity e) {
                return fs.stream().allMatch(f -> f.test(inst, e));
            }

            @Override
            public boolean testSelf(SpellInstance inst, Entity caster) {
                return fs.stream().allMatch(f -> f.testSelf(inst, caster));
            }

            @Override
            public boolean testBlock(BlockState state) {
                return fs.stream().allMatch(f -> f.testBlock(state));
            }
        };
    }

    static Filter except(Filter base, Filter without) {
        return new Filter() {
            @Override
            public boolean test(SpellInstance inst, Entity e) {
                return base.test(inst, e) && !without.test(inst, e);
            }

            @Override
            public boolean testSelf(SpellInstance inst, Entity caster) {
                return base.testSelf(inst, caster) && !without.testSelf(inst, caster);
            }

            @Override
            public boolean testBlock(BlockState state) {
                return base.testBlock(state) && !without.testBlock(state);
            }
        };
    }
}

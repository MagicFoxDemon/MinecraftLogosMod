package dev.logos.runtime;

/**
 * A companion from another mod: one of each character per player, and never timed out. To Logos it is a kept familiar:
 * its master's spells spare it, what it kills its master killed, and {@code gather}, {@code stance}
 * and {@code sic} reach it. But its own mod keeps it: Logos never aims it, walks it after its master,
 * calls it back, fades it or sends it away. It reads its orders itself ({@link Familiars#orderedTarget},
 * {@link Familiars#orderPoint}, {@link Familiars#onErrand}) and picks its own targets.
 * <p>
 * Bind it with {@link Familiars#bindCompanion}: its master's companion of the same entity type, if any, is sent home,
 * and different ones may be out together up to the familiar limit ({@link Familiars#companionRoom}).
 */
public interface LastingCompanion extends KeptFamiliar {
    /**
     * Go home: its master called this character again or one companion too many, or a spell dismissed it. Its mod decides what home
     * is (back into a token, to its own place); the entity should leave the world one way or another.
     */
    void sendHome();

    /** A companion never runs dry the way a lifeform does; override if it can be knocked out. */
    @Override
    default boolean isDormant() {
        return false;
    }
}

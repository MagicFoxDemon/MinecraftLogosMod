package dev.logos.client;

/**
 * The physical vein's two looks (see VEIN_VFX.md): passing over the ground, a sword's qi skimming along it and scoring
 * it ({@link PhysicalSlashVeinVisual}); and what the ground keeps when it lingers, needle-sharp spikes
 * ({@link PhysicalLingeringVeinVisual}).
 */
final class PhysicalVeins {
    private PhysicalVeins() {}

    /** The look of a head of a physical vein: of one that leaves what it crawled over behind if it {@code lingers}. */
    static VeinVisual create(boolean lingers) {
        return lingers ? new PhysicalLingeringVeinVisual() : new PhysicalSlashVeinVisual();
    }
}

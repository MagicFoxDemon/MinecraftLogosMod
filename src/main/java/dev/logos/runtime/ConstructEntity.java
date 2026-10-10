package dev.logos.runtime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import dev.logos.lang.ConstructShape;
import dev.logos.lang.Element;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The world presence of a {@code construct}: one entity per shape, tracked and synced to clients
 * like any other, so clients know its exact geometry (players move client-side and must collide
 * there too). It is never saved; the spell that holds it ({@link ConstructObj}) owns its life.
 * Collision itself is added to the game's entity collisions by {@code dev.logos.mixin.EntityMixin}.
 */
public final class ConstructEntity extends Entity {
    private static final EntityDataAccessor<Byte> SHAPE = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<org.joml.Vector3fc> SIZE = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Float> RADIUS = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<org.joml.Vector3fc> FACING = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Byte> FLAGS = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> ELEMENT = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Optional<BlockState>> LOOK = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_STATE);
    private static final EntityDataAccessor<Integer> CASTER = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.INT);
    /** Where the shape was built; it is drawn and collides translated by (position − origin). */
    private static final EntityDataAccessor<org.joml.Vector3fc> ORIGIN = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.VECTOR3);
    /** Follow mode: the hold distance (x) for walls and shields, else the offset from the caster's feet. */
    private static final EntityDataAccessor<org.joml.Vector3fc> FOLLOW = SynchedEntityData.defineId(ConstructEntity.class, EntityDataSerializers.VECTOR3);

    private static final int SNAP = 1, ONE_WAY = 2, SOLID_CASTER = 4, SOLID_OTHERS = 8, FOLLOWS = 16;

    /** Every construct currently in a level, on either side; collision queries walk this short list. */
    private static final List<ConstructEntity> LIVE = new CopyOnWriteArrayList<>();

    /** Server only: the spell body that owns this construct and judges what it stops. */
    @Nullable ConstructObj owner;

    /** Server only: the spell body that owns this construct. */
    public @Nullable ConstructObj owner() {
        return owner;
    }
    /** The shape as built at {@link #origin()} (or at the follow pose), and that shape at the current position. */
    private @Nullable ConstructGeometry base, geometry;
    private @Nullable Vec3 geometryOffset;
    /** Follow mode: where the caster holds it this tick. Both sides compute it from the caster's own pose. */
    private @Nullable Vec3 followCenter, followFacing;

    public ConstructEntity(EntityType<? extends ConstructEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /** Server: fixes the shape before the entity is added to the world. */
    public void setup(ConstructShape shape, Vec3 at, Vec3 facing, double w, double h, double d, double r, boolean snap, boolean oneWay,
            Element element, @Nullable BlockState look, int casterId, @Nullable Entity followed) {
        entityData.set(SHAPE, (byte) shape.ordinal());
        entityData.set(SIZE, new Vector3f((float) w, (float) h, (float) d));
        entityData.set(RADIUS, (float) r);
        entityData.set(FACING, new Vector3f((float) facing.x, (float) facing.y, (float) facing.z));
        entityData.set(FLAGS, (byte) ((snap ? SNAP : 0) | (oneWay ? ONE_WAY : 0) | SOLID_CASTER | SOLID_OTHERS | (followed != null ? FOLLOWS : 0)));
        entityData.set(ELEMENT, (byte) element.ordinal());
        entityData.set(LOOK, Optional.ofNullable(look));
        entityData.set(CASTER, casterId);
        entityData.set(ORIGIN, new Vector3f((float) at.x, (float) at.y, (float) at.z));
        if (followed != null) {
            Vec3 hold = rotatesWithGaze() ? new Vec3(at.distanceTo(followed.getEyePosition()), 0, 0) : at.subtract(followed.position());
            entityData.set(FOLLOW, new Vector3f((float) hold.x, (float) hold.y, (float) hold.z));
        }
        base = geometry = null;
        setPos(at);
        updateFollow();
    }

    private ConstructShape shape() {
        return ConstructShape.values()[Math.floorMod(entityData.get(SHAPE), ConstructShape.values().length)];
    }

    public boolean follows() {
        return (entityData.get(FLAGS) & FOLLOWS) != 0;
    }

    /** Walls and shields held up turn with the caster's gaze; floors, boxes and domes just come along. */
    private boolean rotatesWithGaze() {
        return shape() == ConstructShape.WALL || shape() == ConstructShape.DISK;
    }

    private Vec3 origin() {
        org.joml.Vector3fc o = entityData.get(ORIGIN);
        return new Vec3(o.x(), o.y(), o.z());
    }

    /** Follow mode: re-reads where the caster holds it. Runs on both sides, so the caster's own client needs no server round trip. */
    void updateFollow() {
        if (!follows()) return;
        Entity caster = level().getEntity(casterId());
        if (caster == null) return;
        org.joml.Vector3fc hold = entityData.get(FOLLOW);
        if (rotatesWithGaze()) {
            Vec3 gaze = caster.getLookAngle();
            Vec3 c = caster.getEyePosition().add(gaze.scale(hold.x()));
            if (followCenter == null || followCenter.distanceToSqr(c) > 1e-8 || followFacing.dot(gaze) < 0.99999) {
                followCenter = c;
                followFacing = gaze;
                base = geometry = null;
            }
        } else {
            followCenter = caster.position().add(hold.x(), hold.y(), hold.z());
        }
        setPos(followCenter);
    }

    /** Server: who the construct stops, as last judged from its ⊙ filter (players move client-side, so clients need it). */
    void setSolidFor(boolean caster, boolean others) {
        byte f = entityData.get(FLAGS);
        f = (byte) ((f & ~(SOLID_CASTER | SOLID_OTHERS)) | (caster ? SOLID_CASTER : 0) | (others ? SOLID_OTHERS : 0));
        if (f != entityData.get(FLAGS)) entityData.set(FLAGS, f);
    }

    public ConstructGeometry geometry() {
        boolean turning = follows() && rotatesWithGaze() && followCenter != null;
        if (base == null) {
            org.joml.Vector3fc size = entityData.get(SIZE);
            org.joml.Vector3fc f = entityData.get(FACING);
            Vec3 at = turning ? followCenter : origin();
            Vec3 facing = turning ? followFacing : new Vec3(f.x(), f.y(), f.z());
            base = ConstructGeometry.of(shape(), at, facing, size.x(), size.y(), size.z(), entityData.get(RADIUS), (entityData.get(FLAGS) & SNAP) != 0);
        }
        if (turning) return base;
        // Moving only translates: the cells shift, they are not sampled again.
        Vec3 offset = (follows() && followCenter != null ? followCenter : position()).subtract(origin());
        if (geometry == null || !offset.equals(geometryOffset)) {
            geometry = base.translated(offset);
            geometryOffset = offset;
        }
        return geometry;
    }

    /** Whether {@code e} stands on top of {@code g}. */
    public static boolean standsOn(Entity e, ConstructGeometry g) {
        AABB box = e.getBoundingBox();
        return g.intersects(new AABB(box.minX, box.minY - 0.08, box.minZ, box.maxX, box.minY + 0.01, box.maxZ));
    }

    public boolean oneWay() {
        return (entityData.get(FLAGS) & ONE_WAY) != 0;
    }

    public Element element() {
        return Element.values()[Math.floorMod(entityData.get(ELEMENT), Element.values().length)];
    }

    public Optional<BlockState> look() {
        return entityData.get(LOOK);
    }

    public int casterId() {
        return entityData.get(CASTER);
    }

    /** Whether {@code e} collides with this construct right now. */
    public boolean solidFor(Entity e) {
        // Projectiles are stopped by ConstructObj.intercept instead, which also reports on-block.
        if (e == this || e instanceof ConstructEntity || e instanceof net.minecraft.world.entity.projectile.Projectile || isRemoved()) return false;
        ConstructGeometry g = geometry();
        Vec3 c = e.getBoundingBox().getCenter();
        if (oneWay() && !g.inFront(c)) return false;
        // Never trap what the shape appeared on top of: it may step out.
        if (g.intersects(e.getBoundingBox().deflate(1e-3))) return false;
        byte flags = entityData.get(FLAGS);
        if (e instanceof Player) return (flags & (e.getId() == casterId() ? SOLID_CASTER : SOLID_OTHERS)) != 0;
        if (owner != null) return owner.admits(e);
        return (flags & SOLID_OTHERS) != 0;
    }

    /** Constructs in {@code level} whose bounds touch {@code area}. */
    public static List<ConstructEntity> near(Level level, AABB area) {
        if (LIVE.isEmpty()) return List.of();
        return LIVE.stream().filter(c -> c.level() == level && !c.isRemoved() && c.geometry().bounds.intersects(area)).toList();
    }

    public static boolean any() {
        return !LIVE.isEmpty();
    }

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        LIVE.add(this);
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        LIVE.remove(this);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == SHAPE || key == SIZE || key == RADIUS || key == FACING || key == ORIGIN || key == FLAGS || key == FOLLOW) {
            base = geometry = null;
            if (entityData != null && level() != null) setBoundingBox(geometry().bounds);
        }
    }

    @Override
    protected AABB makeBoundingBox(Vec3 pos) {
        // Called from the base constructor, before the synced data exists.
        if (entityData == null) return super.makeBoundingBox(pos);
        return geometry().bounds;
    }

    @Override
    public void tick() {
        super.tick();
        updateFollow();
        // A construct whose spell is gone (or that was never owned on this server) does not linger.
        if (!level().isClientSide() && (owner == null || owner.isEnded())) discard();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 128 * 128;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder b) {
        b.define(SHAPE, (byte) ConstructShape.WALL.ordinal());
        b.define(SIZE, new Vector3f(1, 1, 1));
        b.define(RADIUS, 1f);
        b.define(FACING, new Vector3f(0, 0, 1));
        b.define(FLAGS, (byte) (SOLID_CASTER | SOLID_OTHERS));
        b.define(ELEMENT, (byte) Element.ARCANE.ordinal());
        b.define(LOOK, Optional.empty());
        b.define(CASTER, -1);
        b.define(ORIGIN, new Vector3f());
        b.define(FOLLOW, new Vector3f());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return true;
    }
}

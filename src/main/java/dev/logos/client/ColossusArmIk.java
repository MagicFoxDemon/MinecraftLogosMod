package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.renderer.base.BoneSnapshots;

import net.minecraft.util.Mth;

/**
 * Two-bone IK for the colossus's right arm, so its palm closes on what it grabs, and its fist lands on
 * what it strikes, whatever their size.
 * Solved in the model's own space after the animation has posed the bones, by replaying GeckoLib's
 * bone transforms (translate, to the pivot, rotate Z·Y·X, scale, back): the torso bows only as far as
 * it must for the point to come within the arm's length, the elbow is bent until the shoulder-to-palm
 * distance matches, and the upper arm is turned (the least turn from its animated pose) until the
 * palm lies on the point. The result is blended over the animation by a weight.
 */
final class ColossusArmIk {
    static final String TORSO = "torso", UPPER = "right_upper_arm", FORE = "right_forearm", PALM = "attachment_muzzle_right";
    /** The torso may bow up to this much further forward to reach; this model bows forward on negative X. */
    private static final float TORSO_MAX = 55 * Mth.DEG_TO_RAD, TORSO_BOW = -1;

    private ColossusArmIk() {}

    /** Poses the arm toward {@code target} (in model space) with {@code weight} 0–1 over the animation. */
    static void solve(BakedGeoModel model, BoneSnapshots snapshots, Vector3f target, float weight) {
        GeoBone torso = model.getBone(TORSO).orElse(null), upper = model.getBone(UPPER).orElse(null),
                fore = model.getBone(FORE).orElse(null), palm = model.getBone(PALM).orElse(null);
        if (torso == null || upper == null || fore == null || palm == null || weight <= 0) return;
        BoneSnapshot torsoSnap = snapshots.get(torso), upperSnap = snapshots.get(upper), foreSnap = snapshots.get(fore);
        snapshots.get(palm);
        snapshots.get("right_hand");
        float torsoAnim = torsoSnap.getRotX(), foreAnim = foreSnap.getRotX();

        // The torso: the least extra bow that brings the point within the arm's length (or the most it has).
        float armLength = maxReach(upper, fore, palm, snapshots);
        float bow = 0;
        for (int i = 0; i <= 11; i++) {
            bow = TORSO_MAX * i / 11;
            torsoSnap.setRotX(torsoAnim + TORSO_BOW * bow);
            if (pivot(upper, snapshots).distance(target) <= armLength * 0.98f) break;
        }
        torsoSnap.setRotX(torsoAnim + TORSO_BOW * bow * weight);

        Vector3f shoulder = pivot(upper, snapshots);
        float want = shoulder.distance(target);
        // The elbow: of the bends that give the wanted shoulder-to-palm length, the one nearest the animation's.
        float bestBend = foreAnim, bestErr = Float.MAX_VALUE;
        for (int i = -150; i <= 150; i += 2) {
            float bend = foreAnim + i * Mth.DEG_TO_RAD;
            foreSnap.setRotX(bend);
            float err = Math.abs(pivot(palm, snapshots).distance(shoulder) - want) + Math.abs(i) * 0.0005f;
            if (err < bestErr) {
                bestErr = err;
                bestBend = bend;
            }
        }
        foreSnap.setRotX(Mth.lerp(weight, foreAnim, bestBend));

        // The upper arm: the least turn (about the shoulder, in its parent's frame) carrying the palm onto the point.
        Vector3f toPalm = pivot(palm, snapshots).sub(shoulder), toTarget = new Vector3f(target).sub(shoulder);
        if (toPalm.lengthSquared() < 1e-8f || toTarget.lengthSquared() < 1e-8f) return;
        Quaternionf turn = new Quaternionf().rotationTo(toPalm.normalize(), toTarget.normalize());
        Matrix3f parent = new Matrix3f();
        chain(upper.parent(), snapshots).get3x3(parent);
        Quaternionf parentRot = parent.getNormalizedRotation(new Quaternionf());
        Quaternionf animRot = new Quaternionf().rotationZYX(rotZ(upper, upperSnap), rotY(upper, upperSnap), rotX(upper, upperSnap));
        // In the parent's frame: R' = P⁻¹ · turn · P · R.
        Quaternionf solved = new Quaternionf(parentRot).conjugate().mul(turn).mul(parentRot).mul(animRot);
        Quaternionf blended = new Quaternionf(animRot).slerp(solved, weight);
        Vector3f euler = blended.getEulerAnglesZYX(new Vector3f());
        upperSnap.setRotX(euler.x - upper.baseRotX());
        upperSnap.setRotY(euler.y - upper.baseRotY());
        upperSnap.setRotZ(euler.z - upper.baseRotZ());
    }

    /** The farthest the palm can be from the shoulder, the elbow straightened as far as it goes. */
    private static float maxReach(GeoBone upper, GeoBone fore, GeoBone palm, BoneSnapshots snapshots) {
        BoneSnapshot foreSnap = snapshots.get(fore);
        float anim = foreSnap.getRotX(), best = 0;
        Vector3f shoulder = pivot(upper, snapshots);
        for (int i = -150; i <= 150; i += 5) {
            foreSnap.setRotX(anim + i * Mth.DEG_TO_RAD);
            best = Math.max(best, pivot(palm, snapshots).distance(shoulder));
        }
        foreSnap.setRotX(anim);
        return best;
    }

    /** Where {@code bone}'s pivot is in model space (blocks), its parents posed as their snapshots say. */
    static Vector3f pivot(GeoBone bone, BoneSnapshots snapshots) {
        Matrix4f m = chain(bone.parent(), snapshots);
        BoneSnapshot s = snapshots.get(bone);
        if (s != null && s.hasTranslation()) m.translate(-s.getTranslateX() / 16f, s.getTranslateY() / 16f, s.getTranslateZ() / 16f);
        return m.transformPosition(new Vector3f(bone.pivotX() / 16f, bone.pivotY() / 16f, bone.pivotZ() / 16f));
    }

    /** The model-space transform of everything from the root down to and including {@code bone}. */
    static Matrix4f chain(@Nullable GeoBone bone, BoneSnapshots snapshots) {
        List<GeoBone> line = new ArrayList<>();
        for (GeoBone b = bone; b != null; b = b.parent()) line.add(b);
        Matrix4f m = new Matrix4f();
        for (int i = line.size() - 1; i >= 0; i--) {
            GeoBone b = line.get(i);
            BoneSnapshot s = snapshots.get(b);
            float px = b.pivotX() / 16f, py = b.pivotY() / 16f, pz = b.pivotZ() / 16f;
            if (s != null && s.hasTranslation()) m.translate(-s.getTranslateX() / 16f, s.getTranslateY() / 16f, s.getTranslateZ() / 16f);
            m.translate(px, py, pz);
            m.rotateZ(rotZ(b, s)).rotateY(rotY(b, s)).rotateX(rotX(b, s));
            if (s != null && s.hasScale()) m.scale(s.getScaleX(), s.getScaleY(), s.getScaleZ());
            m.translate(-px, -py, -pz);
        }
        return m;
    }

    private static float rotX(GeoBone b, @Nullable BoneSnapshot s) {
        return b.baseRotX() + (s == null ? 0 : s.getRotX());
    }

    private static float rotY(GeoBone b, @Nullable BoneSnapshot s) {
        return b.baseRotY() + (s == null ? 0 : s.getRotY());
    }

    private static float rotZ(GeoBone b, @Nullable BoneSnapshot s) {
        return b.baseRotZ() + (s == null ? 0 : s.getRotZ());
    }
}

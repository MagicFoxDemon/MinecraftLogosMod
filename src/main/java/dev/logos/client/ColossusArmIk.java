package dev.logos.client;

import java.util.ArrayList;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import javax.annotation.Nullable;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

import net.minecraft.util.Mth;

/**
 * Two-bone IK for the colossus's right arm, so its palm closes on what it grabs, and its fist lands on
 * what it strikes, whatever their size.
 * Solved in the model's own space after the animation has posed the bones, by replaying GeckoLib's
 * bone transforms (translate, to the pivot, rotate Z·Y·X, scale, back): the torso bows only as far as
 * it must for the point to come within the arm's length, the elbow is bent until the shoulder-to-palm
 * distance matches, and the upper arm is turned (the least turn from its animated pose) until the
 * palm lies on the point. The result is blended over the animation by a weight. GeckoLib 4 keeps a
 * bone's whole pose on the bone (its base rotation included), so that is what is read and written.
 */
final class ColossusArmIk {
    static final String TORSO = "torso", UPPER = "right_upper_arm", FORE = "right_forearm", PALM = "attachment_muzzle_right";
    /** The torso may bow up to this much further forward to reach; this model bows forward on negative X. */
    private static final float TORSO_MAX = 55 * Mth.DEG_TO_RAD, TORSO_BOW = -1;

    private ColossusArmIk() {}

    /** Poses the arm toward {@code target} (in model space) with {@code weight} 0–1 over the animation. */
    static void solve(BakedGeoModel model, Vector3f target, float weight) {
        GeoBone torso = model.getBone(TORSO).orElse(null), upper = model.getBone(UPPER).orElse(null),
                fore = model.getBone(FORE).orElse(null), palm = model.getBone(PALM).orElse(null);
        if (torso == null || upper == null || fore == null || palm == null || weight <= 0) return;
        float torsoAnim = torso.getRotX(), foreAnim = fore.getRotX();

        // The torso: the least extra bow that brings the point within the arm's length (or the most it has).
        float armLength = maxReach(upper, fore, palm);
        float bow = 0;
        for (int i = 0; i <= 11; i++) {
            bow = TORSO_MAX * i / 11;
            torso.setRotX(torsoAnim + TORSO_BOW * bow);
            if (pivot(upper).distance(target) <= armLength * 0.98f) break;
        }
        torso.setRotX(torsoAnim + TORSO_BOW * bow * weight);

        Vector3f shoulder = pivot(upper);
        float want = shoulder.distance(target);
        // The elbow: of the bends that give the wanted shoulder-to-palm length, the one nearest the animation's.
        float bestBend = foreAnim, bestErr = Float.MAX_VALUE;
        for (int i = -150; i <= 150; i += 2) {
            float bend = foreAnim + i * Mth.DEG_TO_RAD;
            fore.setRotX(bend);
            float err = Math.abs(pivot(palm).distance(shoulder) - want) + Math.abs(i) * 0.0005f;
            if (err < bestErr) {
                bestErr = err;
                bestBend = bend;
            }
        }
        fore.setRotX(Mth.lerp(weight, foreAnim, bestBend));

        // The upper arm: the least turn (about the shoulder, in its parent's frame) carrying the palm onto the point.
        Vector3f toPalm = pivot(palm).sub(shoulder), toTarget = new Vector3f(target).sub(shoulder);
        if (toPalm.lengthSquared() < 1e-8f || toTarget.lengthSquared() < 1e-8f) return;
        Quaternionf turn = new Quaternionf().rotationTo(toPalm.normalize(), toTarget.normalize());
        Matrix3f parent = new Matrix3f();
        chain(upper.getParent()).get3x3(parent);
        Quaternionf parentRot = parent.getNormalizedRotation(new Quaternionf());
        Quaternionf animRot = new Quaternionf().rotationZYX(upper.getRotZ(), upper.getRotY(), upper.getRotX());
        // In the parent's frame: R' = P⁻¹ · turn · P · R.
        Quaternionf solved = new Quaternionf(parentRot).conjugate().mul(turn).mul(parentRot).mul(animRot);
        Quaternionf blended = new Quaternionf(animRot).slerp(solved, weight);
        Vector3f euler = blended.getEulerAnglesZYX(new Vector3f());
        upper.setRotX(euler.x);
        upper.setRotY(euler.y);
        upper.setRotZ(euler.z);
    }

    /** The farthest the palm can be from the shoulder, the elbow straightened as far as it goes. */
    private static float maxReach(GeoBone upper, GeoBone fore, GeoBone palm) {
        float anim = fore.getRotX(), best = 0;
        Vector3f shoulder = pivot(upper);
        for (int i = -150; i <= 150; i += 5) {
            fore.setRotX(anim + i * Mth.DEG_TO_RAD);
            best = Math.max(best, pivot(palm).distance(shoulder));
        }
        fore.setRotX(anim);
        return best;
    }

    /** Where {@code bone}'s pivot is in model space (blocks), its parents posed as they are now. */
    static Vector3f pivot(GeoBone bone) {
        Matrix4f m = chain(bone.getParent());
        m.translate(-bone.getPosX() / 16f, bone.getPosY() / 16f, bone.getPosZ() / 16f);
        return m.transformPosition(new Vector3f(bone.getPivotX() / 16f, bone.getPivotY() / 16f, bone.getPivotZ() / 16f));
    }

    /** The model-space transform of everything from the root down to and including {@code bone}. */
    static Matrix4f chain(@Nullable GeoBone bone) {
        List<GeoBone> line = new ArrayList<>();
        for (GeoBone b = bone; b != null; b = b.getParent()) line.add(b);
        Matrix4f m = new Matrix4f();
        for (int i = line.size() - 1; i >= 0; i--) {
            GeoBone b = line.get(i);
            float px = b.getPivotX() / 16f, py = b.getPivotY() / 16f, pz = b.getPivotZ() / 16f;
            m.translate(-b.getPosX() / 16f, b.getPosY() / 16f, b.getPosZ() / 16f);
            m.translate(px, py, pz);
            m.rotateZ(b.getRotZ()).rotateY(b.getRotY()).rotateX(b.getRotX());
            m.scale(b.getScaleX(), b.getScaleY(), b.getScaleZ());
            m.translate(-px, -py, -pz);
        }
        return m;
    }
}

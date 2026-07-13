package dev.vpd.mirror;

import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.vivecraft.api.data.FBTMode;

/**
 * 1:1 port of the math used by Vivecraft's client renderer
 * (ModelUtils / VRPlayerModel / VRPlayerModel_WithArms(Legs)).
 *
 * "model" space is the Vivecraft/vanilla player model space: pixels, y down, x flipped,
 * origin at the render root (1.501 * 0.9375 * heightScale blocks above the player position).
 * "local" space is world-axis-aligned, relative to the player position.
 */
public final class VRMath {

    public static final float PI = (float) Math.PI;
    public static final float HALF_PI = PI / 2f;

    private VRMath() {
    }

    /** ModelUtils.worldToModel */
    public static Vector3f worldToModel(Vector3fc localPos, float bodyYaw, float worldScale, float heightScale, Vector3f dest) {
        dest.set(localPos).div(worldScale);
        float scale = 0.9375f * heightScale;
        dest.sub(0f, 1.501f * scale, 0f)
            .rotateY(-PI + bodyYaw)
            .mul(16f / scale);
        dest.set(-dest.x, -dest.y, dest.z);
        return dest;
    }

    /** ModelUtils.modelToWorld (applyScale = true, useWorldScale = true) */
    public static Vector3f modelToLocal(Vector3fc model, float bodyYaw, float worldScale, float heightScale, Vector3f dest) {
        float scale = 0.9375f * heightScale;
        dest.set(-model.x(), -model.y(), model.z())
            .mul(scale / 16f)
            .rotateY(PI - bodyYaw)
            .add(0f, 1.501f * scale, 0f)
            .mul(worldScale);
        return dest;
    }

    /** ModelUtils.worldToModelDirection (in place) */
    public static Vector3f worldDirToModel(Vector3f dir, float bodyYaw) {
        dir.rotateY(-PI + bodyYaw);
        dir.set(-dir.x, -dir.y, dir.z);
        return dir;
    }

    public static Quaternionf qFromMatrix(Matrix3f m, Quaternionf dest) {
        m.getNormalizedRotation(dest);
        return dest.normalize();
    }

    /**
     * ModelUtils.pointAtModel as a quaternion. Thanks to the display entity yaw being set to
     * bodyYaw + 180, the Vivecraft model-frame matrix can be used directly as the display
     * right_rotation (the axis-flip conjugations cancel out).
     */
    public static Quaternionf pointAtModelQ(Vector3fc dir, Vector3fc up, Quaternionf dest) {
        return pointAtModelQ(dir, up, 0f, dest);
    }

    /** pointAtModel with the laying rotateLocalX correction (vivecraft: tempM.rotateLocalX(-xRot)) */
    public static Quaternionf pointAtModelQ(Vector3fc dir, Vector3fc up, float preXRot, Quaternionf dest) {
        Matrix3f m = pointAtModelM(dir, up, new Matrix3f());
        if (preXRot != 0f) {
            m.rotateLocalX(preXRot);
        }
        return qFromMatrix(m, dest);
    }

    public static Matrix3f pointAtModelM(Vector3fc dir, Vector3fc up, Matrix3f dest) {
        return dest
            .setLookAlong(-dir.x(), -dir.y(), dir.z(), -up.x(), -up.y(), up.z())
            .transpose()
            .rotateX(HALF_PI);
    }

    /**
     * Transforms a Vivecraft ModelPart-local vector (pixels, y down) by a display-space part
     * rotation, returning the offset in model space: F * q * F * v with F = diag(-1,-1,1).
     */
    public static Vector3f modelPartTransform(Quaternionfc q, float x, float y, float z, Vector3f dest) {
        dest.set(-x, -y, z);
        q.transform(dest);
        dest.set(-dest.x, -dest.y, dest.z);
        return dest;
    }

    /** ModelUtils.toModelDir + rotateLocalX(xRot) as a quaternion */
    public static Quaternionf toModelDirQ(float bodyYaw, Quaternionfc rot, float xRot, Quaternionf dest) {
        Matrix3f m = new Matrix3f().set(rot);
        m.rotateLocalY(bodyYaw + PI);
        m.rotateX(HALF_PI);
        m.rotateLocalX(xRot);
        return qFromMatrix(m, dest);
    }

    /** minimal rotation mapping the model down axis (0,1,0) onto dir, in model space */
    public static Quaternionf modelAxisQ(Vector3fc dirIn, Quaternionf dest) {
        Vector3f d = new Vector3f(dirIn);
        if (d.length() <= 0.001f) {
            return dest.identity();
        }
        d.normalize();
        float dot = d.y;
        if (dot > 0.999f) {
            return dest.identity();
        }
        if (dot < -0.999f) {
            return dest.set(1f, 0f, 0f, 0f);
        }
        dest.set(d.z, 0f, -d.x, 1f + dot);
        return dest.normalize();
    }

    /** minimal rotation mapping the display down axis (0,-1,0) onto the model dir, in display space */
    public static Quaternionf displayAxisQ(Vector3fc dirModel, Quaternionf dest) {
        Vector3f d = new Vector3f(-dirModel.x(), -dirModel.y(), dirModel.z());
        if (d.length() <= 0.001f) {
            return dest.identity();
        }
        d.normalize();
        float dot = -d.y;
        if (dot > 0.999f) {
            return dest.identity();
        }
        if (dot < -0.999f) {
            return dest.set(1f, 0f, 0f, 0f);
        }
        dest.set(-d.z, 0f, d.x, 1f + dot);
        return dest.normalize();
    }

    /** ModelUtils.estimateJointDir; jointPosLocal may be null */
    public static Vector3f estimateJointDir(Vector3fc upper, Vector3fc lower, Quaternionfc lowerRot, float bodyYaw,
                                            boolean jointDown, Vector3fc jointPosLocal,
                                            float worldScale, float heightScale, Vector3f dest) {
        if (jointPosLocal != null) {
            Vector3f mid = new Vector3f(upper).add(lower).mul(0.5f);
            Vector3f jointModel = worldToModel(jointPosLocal, bodyYaw, worldScale, heightScale, new Vector3f());
            jointModel.sub(mid, dest);
        } else {
            // point the elbow away from the hand direction: hand-local up forward / down back
            dest.set(0f, jointDown ? -1f : 1f, jointDown ? 1f : -1f);
            lowerRot.transform(dest);
            worldDirToModel(dest, bodyYaw);
        }
        Vector3f armDir = lower.sub(upper, new Vector3f());
        float denom = armDir.dot(armDir);
        if (denom <= 0.001f) {
            return dest.set(0f, -1f, 0f);
        }
        float dot = armDir.dot(dest) / denom;
        armDir.mul(dot);
        dest.sub(armDir);
        if (dest.length() <= 0.001f) {
            return dest.set(0f, -1f, 0f);
        }
        return dest.normalize();
    }

    /** ModelUtils.estimateJoint */
    public static Vector3f estimateJoint(Vector3fc upper, Vector3fc lower, Vector3fc preferred, float limbLength, Vector3f dest) {
        dest.set(upper);
        float distance = dest.distance(lower);
        dest.add(lower).mul(0.5f);
        if (distance < limbLength) {
            float offsetDistance = (float) Math.sqrt((limbLength * limbLength - distance * distance) * 0.25f);
            dest.add(preferred.x() * offsetDistance, preferred.y() * offsetDistance, preferred.z() * offsetDistance);
        }
        return dest;
    }

    /** ClientVRPlayers.RotInfo.getBodyYawRad / MathUtils.bodyYawRad */
    public static float bodyYawRad(boolean seated, FBTMode fbtMode, boolean leftHanded, Vector3fc headDir,
                                   Vector3fc mainHandPos, Vector3fc offHandPos, Quaternionfc waistQuat) {
        if (seated) {
            return (float) Math.atan2(-headDir.x(), headDir.z());
        }
        if (fbtMode != FBTMode.ARMS_ONLY) {
            Vector3f dir = waistQuat.transform(0f, 0f, -1f, new Vector3f());
            dir.lerp(headDir, 0.5f);
            return (float) Math.atan2(-dir.x, dir.z);
        }
        if (offHandPos.distanceSquared(mainHandPos) <= 0f) {
            return (float) Math.atan2(-headDir.x(), headDir.z());
        }

        Vector3fc rightHand = leftHanded ? offHandPos : mainHandPos;
        Vector3fc leftHand = leftHanded ? mainHandPos : offHandPos;

        Vector3f dir = leftHand.add(rightHand, new Vector3f());
        Vector3f dirFlat = new Vector3f(dir.x, 0f, dir.z);
        Vector3f headFlat = new Vector3f(headDir.x(), 0f, headDir.z());
        if (dirFlat.length() > 0.001f) {
            dirFlat.normalize();
        }
        if (headFlat.length() > 0.001f) {
            headFlat.normalize();
        }
        float hDot = dirFlat.dot(headFlat);

        Vector3f armsForward = leftHand.sub(rightHand, new Vector3f());
        armsForward.rotateY(-HALF_PI);
        if (armsForward.dot(headDir) < 0f) {
            armsForward.mul(-1f);
        }

        if (hDot < 0f) {
            hDot = 0f;
        }
        armsForward.lerp(dir, hDot, dir);
        if (dir.length() > 0.001f) {
            dir.normalize();
        }
        dir.lerp(headDir, 0.5f, dir);
        return (float) Math.atan2(-dir.x, dir.z);
    }

    /** head pivot with the -0.2 / +0.1 neck offset (VRPlayerModel.animateVRModel) */
    public static Vector3f headPivot(Vector3fc headPos, Quaternionfc headQuat, float worldScale, float heightScale, Vector3f dest) {
        dest.set(0f, -0.2f, 0.1f);
        headQuat.transform(dest);
        dest.mul(heightScale * worldScale);
        return dest.add(headPos);
    }

    /** ModelUtils.getBendProgress */
    public static float bendProgress(boolean crouching, boolean passenger, Vector3fc headPivot, float worldScale, float heightScale) {
        float eyeHeight = 1.42f * worldScale;
        float heightOffset = headPivot.y() - eyeHeight * heightScale;
        if (heightOffset < -eyeHeight) {
            heightOffset = -eyeHeight;
        }
        if (heightOffset > 0f) {
            heightOffset = 0f;
        }
        float progress = heightOffset / -eyeHeight;
        if (crouching) {
            progress = Math.max(progress, 0.125f);
        }
        if (passenger) {
            progress = Math.min(progress, 0.5f);
        }
        return progress;
    }

    /**
     * Moves a model-space position by a part-local SPD pivot offset (display pixels),
     * rotated with the part rotation and converted to model space (x,y flipped, /0.9375).
     */
    public static Vector3f offsetModelPos(Vector3fc model, Quaternionfc q, Vector3fc localOffset, Vector3f dest) {
        Vector3f offE = q.transform(localOffset.x(), localOffset.y(), localOffset.z(), new Vector3f());
        dest.set(model);
        return dest.add(-offE.x / 0.9375f, -offE.y / 0.9375f, offE.z / 0.9375f);
    }
}

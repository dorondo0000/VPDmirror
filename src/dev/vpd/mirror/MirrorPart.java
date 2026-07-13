package dev.vpd.mirror;

import org.joml.Vector3f;

/**
 * The eleven Stable Player Display (split) parts.
 * markerOffset is the transformation translation Y that the SPD shader uses to identify the part.
 * localOffset is the difference between where the SPD cube sits relative to its display pivot and
 * where the Vivecraft/vanilla cube sits relative to its ModelPart pivot, in part-local display
 * pixels (x = +outward, y = +up along the limb). It must be rotated with the part rotation.
 */
public enum MirrorPart {
    HEAD("head", 0f),
    RIGHT_ARM("right_arm", -1024f),
    LEFT_ARM("left_arm", -2048f),
    TORSO("torso", -3072f),
    RIGHT_LEG("right_leg", -4096f),
    LEFT_LEG("left_leg", -5120f),
    RIGHT_FOREARM("right_forearm", -6144f),
    LEFT_FOREARM("left_forearm", -7168f),
    WAIST("waist", -8192f),
    LOWER_RIGHT_LEG("lower_right_leg", -9216f),
    LOWER_LEFT_LEG("lower_left_leg", -10240f);

    public final String model;
    public final float markerOffset;

    MirrorPart(String model, float markerOffset) {
        this.model = model;
        this.markerOffset = markerOffset;
    }

    public Vector3f localOffset(boolean slim, Vector3f dest) {
        return switch (this) {
            case RIGHT_ARM -> dest.set(slim ? -0.68875f : -0.6875f, 1.875f, 0f);
            case LEFT_ARM -> dest.set(slim ? 0.68875f : 0.6875f, 1.875f, 0f);
            case RIGHT_FOREARM -> dest.set(slim ? 0.85f : 0.375f, 6.5625f, 0f);
            case LEFT_FOREARM -> dest.set(slim ? -0.85f : -0.375f, 6.5625f, 0f);
            case RIGHT_LEG -> dest.set(0.125f, 0f, 0f);
            case LEFT_LEG -> dest.set(-0.125f, 0f, 0f);
            case LOWER_RIGHT_LEG -> dest.set(0.125f, 6.5625f, 0f);
            case LOWER_LEFT_LEG -> dest.set(-0.125f, 6.5625f, 0f);
            default -> dest.set(0f, 0f, 0f);
        };
    }
}

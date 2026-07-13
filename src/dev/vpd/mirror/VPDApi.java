package dev.vpd.mirror;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Quaternionfc;
import org.vivecraft.ViveMain;
import org.vivecraft.VivePlayer;
import org.vivecraft.api.VRAPI;
import org.vivecraft.api.data.FBTMode;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

/**
 * Public API of VPDMirror. All methods are server-thread only and null-safe.
 *
 * Note: the Vivecraft protocol does not transmit controller button presses, so the
 * closest available signals are {@link #isDrawingBow(Player)} and
 * {@link #getActiveBodyPart(Player)} (which body part last aimed/interacted).
 */
public final class VPDApi {

    private VPDApi() {
    }

    /** true if the player has the Vivecraft mod installed (VR or the NONVR companion mode). */
    public static boolean hasVivecraftMod(Player player) {
        return ViveMain.isVivePlayer(player);
    }

    /** true if the player is actually playing in VR. */
    public static boolean isVRPlayer(Player player) {
        return ViveMain.isVRPlayer(player);
    }

    /** true if the player is in seated VR mode. */
    public static boolean isSeated(Player player) {
        VRPose pose = getPose(player);
        return pose != null && pose.isSeated();
    }

    public static boolean isLeftHanded(Player player) {
        VRPose pose = getPose(player);
        return pose != null && pose.isLeftHanded();
    }

    /** the player's tracking mode, or null when not in VR. */
    public static FBTMode getFBTMode(Player player) {
        VRPose pose = getPose(player);
        return pose == null ? null : pose.getFBTMode();
    }

    /** true if the player has feet/waist trackers (full body tracking). */
    public static boolean hasLegTrackers(Player player) {
        FBTMode mode = getFBTMode(player);
        return mode != null && mode != FBTMode.ARMS_ONLY;
    }

    /** true if the player additionally has knee/elbow trackers. */
    public static boolean hasJointTrackers(Player player) {
        return getFBTMode(player) == FBTMode.WITH_JOINTS;
    }

    /** the player's current VR pose, or null when not in VR. */
    public static VRPose getPose(Player player) {
        return VRAPI.instance().getVRPose(player);
    }

    /** world position of a body part, or null when unavailable. */
    public static Vector getBodyPartPosition(Player player, VRBodyPart part) {
        VRBodyPartData data = getBodyPartData(player, part);
        return data == null ? null : data.getPos();
    }

    /** world rotation of a body part, or null when unavailable. */
    public static Quaternionfc getBodyPartRotation(Player player, VRBodyPart part) {
        VRBodyPartData data = getBodyPartData(player, part);
        return data == null ? null : data.getRotation();
    }

    public static VRBodyPartData getBodyPartData(Player player, VRBodyPart part) {
        VRPose pose = getPose(player);
        return pose == null ? null : pose.getBodyPartData(part);
    }

    /** true while the player is drawing a bow in VR. */
    public static boolean isDrawingBow(Player player) {
        VivePlayer vp = ViveMain.getVivePlayer(player);
        return vp != null && vp.isDrawing();
    }

    /** the body part the player is currently using to aim/interact, or null. */
    public static VRBodyPart getActiveBodyPart(Player player) {
        VivePlayer vp = ViveMain.getVivePlayer(player);
        return vp == null ? null : vp.activeBodyPart;
    }

    /** whether the automatic follow-mirror is enabled for this player (see /vpdtoggle). */
    public static boolean isMirrorEnabled(Player player) {
        VpdMirrorPlugin plugin = VpdMirrorPlugin.instance();
        return plugin != null && plugin.isAutoMirrorEnabled(player.getUniqueId());
    }

    public static void setMirrorEnabled(Player player, boolean enabled) {
        VpdMirrorPlugin plugin = VpdMirrorPlugin.instance();
        if (plugin != null) {
            plugin.setAutoMirrorEnabled(player.getUniqueId(), enabled);
        }
    }

    /** true if a mirror rig currently exists for this player. */
    public static boolean isMirrorActive(Player player) {
        VpdMirrorPlugin plugin = VpdMirrorPlugin.instance();
        return plugin != null && plugin.sessionOf(player.getUniqueId()) != null;
    }
}

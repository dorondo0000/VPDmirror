package dev.vpd.mirror;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.vivecraft.ViveMain;
import org.vivecraft.VivePlayer;
import org.vivecraft.api.VRAPI;
import org.vivecraft.api.data.FBTMode;
import org.vivecraft.api.data.VRBodyPart;
import org.vivecraft.api.data.VRBodyPartData;
import org.vivecraft.api.data.VRPose;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One mirror rig: eleven SPD item displays plus two held-item displays,
 * posed every tick like Vivecraft's split-limb player model
 * (VRPlayerModel.animateVRModel + VRPlayerModel_WithArms(Legs).setupAnim).
 */
public final class MirrorSession {

    public enum Mode { FOLLOW, ANCHOR, STATIC }

    private static final float PI = VRMath.PI;
    private static final float HALF_PI = VRMath.HALF_PI;
    private static final float WALK_POS_WRAP = 9431.80187f; // ~1000 walk-cycle periods
    /** extra -Y translation marker (16 windows) read back by the SPD shader for the damage flash */
    private static final float HURT_MARKER = -1024f * 16f;

    private static final class PartPose {
        final Vector3f pos = new Vector3f();
        final Quaternionf rot = new Quaternionf();
        boolean active;
    }

    private final VpdMirrorPlugin plugin;
    private final UUID targetId;
    private final Mode mode;
    private Location anchor;

    private final Map<MirrorPart, ItemDisplay> displays = new EnumMap<>(MirrorPart.class);
    private final Map<MirrorPart, PartPose> partPoses = new EnumMap<>(MirrorPart.class);
    private ItemDisplay root;
    private ItemDisplay mainItemDisplay;
    private ItemDisplay offItemDisplay;
    private TextDisplay nameTagDisplay;
    private ItemStack lastMainItem;
    private ItemStack lastOffItem;

    private boolean slim;
    private boolean tintApplied;
    private boolean snapInterpolation;
    private int hurtTicks;

    // walk / swim animation state (server-side reproduction of the client render state)
    private float walkPos;
    private float walkSpeed;
    private float swimAmount;
    private double lastX;
    private double lastZ;
    private boolean firstTick = true;

    public MirrorSession(VpdMirrorPlugin plugin, UUID targetId, Location anchor, Mode mode) {
        this.plugin = plugin;
        this.targetId = targetId;
        this.anchor = anchor;
        this.mode = mode;
        for (MirrorPart part : MirrorPart.values()) {
            partPoses.put(part, new PartPose());
        }
    }

    public UUID targetId() {
        return targetId;
    }

    public Mode mode() {
        return mode;
    }

    public boolean isLive() {
        return mode != Mode.STATIC;
    }

    public void markHurt() {
        markHurt(10);
    }

    public void markHurt(int ticks) {
        hurtTicks = ticks;
    }

    public Iterable<Display> allDisplays() {
        java.util.List<Display> all = new java.util.ArrayList<>(displays.values());
        if (root != null) {
            all.add(root);
        }
        if (mainItemDisplay != null) {
            all.add(mainItemDisplay);
        }
        if (offItemDisplay != null) {
            all.add(offItemDisplay);
        }
        if (nameTagDisplay != null) {
            all.add(nameTagDisplay);
        }
        return all;
    }

    public void spawn(Player target) {
        this.slim = plugin.detectSlim(target);
        String variant = slim ? "slim" : "default";
        float scale = clampHeight(heightScale(target));

        // all parts ride a single root display, so their positions can never drift apart;
        // poses are encoded purely in the transformation (entity yaw stays 0)
        Location rootLoc = anchor.clone();
        rootLoc.setYaw(0f);
        rootLoc.setPitch(0f);
        root = rootLoc.getWorld().spawn(rootLoc, ItemDisplay.class, e -> {
            e.addScoreboardTag(VpdMirrorPlugin.TAG_GLOBAL);
            e.addScoreboardTag(VpdMirrorPlugin.TAG_GLOBAL + "_" + targetId);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setGravity(false);
            e.setViewRange(plugin.viewRange());
            e.setTeleportDuration(plugin.interpolationTicks());
            if (plugin.shadow()) {
                // the invisible real player casts no shadow, the root provides it instead
                e.setShadowRadius(0.5f * scale);
                e.setShadowStrength(1f);
            }
        });

        for (MirrorPart part : MirrorPart.values()) {
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setItemModel(NamespacedKey.fromString("animated_java:blueprint/player_display/" + part.model));
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setStrings(List.of(variant));
            meta.setCustomModelDataComponent(cmd);
            item.setItemMeta(meta);

            ItemDisplay display = rootLoc.getWorld().spawn(rootLoc.clone(), ItemDisplay.class, e -> {
                applyDisplayDefaults(e);
                e.setTransformation(new Transformation(
                    new Vector3f(0f, part.markerOffset, 0f), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
                e.setItemStack(item);
            });
            root.addPassenger(display);
            displays.put(part, display);
        }

        if (plugin.showHeldItems() && mode != Mode.STATIC) {
            mainItemDisplay = spawnItemDisplay(rootLoc, scale);
            offItemDisplay = spawnItemDisplay(rootLoc, scale);
        }
        if (mode == Mode.FOLLOW && plugin.nameTag()) {
            nameTagDisplay = anchor.getWorld().spawn(anchor.clone().add(0, target.getHeight() + 0.5, 0),
                TextDisplay.class, e -> {
                    e.addScoreboardTag(VpdMirrorPlugin.TAG_GLOBAL);
                    e.addScoreboardTag(VpdMirrorPlugin.TAG_GLOBAL + "_" + targetId);
                    e.setPersistent(false);
                    e.setViewRange(plugin.viewRange());
                    e.setTeleportDuration(plugin.interpolationTicks());
                    e.setBillboard(Display.Billboard.CENTER);
                    e.setDefaultBackground(true);
                    e.setSeeThrough(true);
                    e.setShadowed(false);
                    e.setText(target.getName());
                });
        }
        lastMainItem = null;
        lastOffItem = null;
        tintApplied = false;

        poseDefault(target);
        // displays must never be visible to modded viewers, not even for a tick
        plugin.refreshVisibility(this);
    }

    private ItemDisplay spawnItemDisplay(Location rootLoc, float scale) {
        ItemDisplay display = rootLoc.getWorld().spawn(rootLoc.clone(), ItemDisplay.class, e -> {
            applyDisplayDefaults(e);
            float s = scale * 0.9375f;
            e.setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
        });
        root.addPassenger(display);
        return display;
    }

    private void applyDisplayDefaults(ItemDisplay e) {
        e.addScoreboardTag(VpdMirrorPlugin.TAG_GLOBAL);
        e.addScoreboardTag(VpdMirrorPlugin.TAG_GLOBAL + "_" + targetId);
        e.setPersistent(false);
        e.setInvulnerable(true);
        e.setGravity(false);
        e.setViewRange(plugin.viewRange());
        e.setTeleportDuration(plugin.interpolationTicks());
        e.setInterpolationDuration(plugin.interpolationTicks());
        e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
    }

    public void remove() {
        for (ItemDisplay display : displays.values()) {
            if (display != null && !display.isDead()) {
                display.remove();
            }
        }
        displays.clear();
        if (mainItemDisplay != null && !mainItemDisplay.isDead()) {
            mainItemDisplay.remove();
        }
        if (offItemDisplay != null && !offItemDisplay.isDead()) {
            offItemDisplay.remove();
        }
        if (nameTagDisplay != null && !nameTagDisplay.isDead()) {
            nameTagDisplay.remove();
        }
        if (root != null && !root.isDead()) {
            root.remove();
        }
        mainItemDisplay = null;
        offItemDisplay = null;
        nameTagDisplay = null;
        root = null;
    }

    private static float clampHeight(float h) {
        return Math.min(1.5f, Math.max(0.5f, h));
    }

    private float heightScale(Player target) {
        VivePlayer vp = ViveMain.getVivePlayer(target);
        return vp != null && vp.heightScale > 0.1f ? vp.heightScale : 1f;
    }

    private float worldScale(Player target) {
        VivePlayer vp = ViveMain.getVivePlayer(target);
        return vp != null && vp.worldScale > 0.1f ? vp.worldScale : 1f;
    }

    private static Vector3f local(VRBodyPartData data, Location base) {
        Vector pos = data.getPos();
        return new Vector3f(
            (float) (pos.getX() - base.getX()),
            (float) (pos.getY() - base.getY()),
            (float) (pos.getZ() - base.getZ()));
    }

    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    /** vanilla standing pose, used for the static rig and the first live frame */
    private void poseDefault(Player target) {
        float worldScale = worldScale(target);
        float heightScale = clampHeight(heightScale(target));
        float bodyYaw = (float) Math.toRadians(anchor.getYaw());
        float handX = 5f + (slim ? 0.5f : 1f);

        setPose(MirrorPart.HEAD, 0f, 0f, 0f);
        setPose(MirrorPart.TORSO, 0f, 0f, 0f);
        setPose(MirrorPart.WAIST, 0f, 6f, 0f);
        setPose(MirrorPart.RIGHT_ARM, -5f, 2f, 0f);
        setPose(MirrorPart.LEFT_ARM, 5f, 2f, 0f);
        setPose(MirrorPart.RIGHT_FOREARM, -handX, 12f, 0f);
        setPose(MirrorPart.LEFT_FOREARM, handX, 12f, 0f);
        setPose(MirrorPart.RIGHT_LEG, -1.9f, 12f, 0f);
        setPose(MirrorPart.LEFT_LEG, 1.9f, 12f, 0f);
        setPose(MirrorPart.LOWER_RIGHT_LEG, -1.9f, 24f, 0f);
        setPose(MirrorPart.LOWER_LEFT_LEG, 1.9f, 24f, 0f);
        poseAll(bodyYaw, worldScale, heightScale);
    }

    private PartPose setPose(MirrorPart part, float x, float y, float z) {
        PartPose pp = partPoses.get(part);
        pp.pos.set(x, y, z);
        pp.rot.identity();
        pp.active = true;
        return pp;
    }

    private PartPose setPose(MirrorPart part, Vector3fc pos, Quaternionfc rot) {
        PartPose pp = partPoses.get(part);
        pp.pos.set(pos);
        pp.rot.set(rot);
        pp.active = true;
        return pp;
    }

    /** Port of VRPlayerModel.animateVRModel + WithArms/WithArmsLegs setupAnim. */
    public void update(Player target) {
        VRPose pose = VRAPI.instance().getVRPose(target);
        if (pose == null) {
            return;
        }

        if (mode == Mode.FOLLOW) {
            Location loc = target.getLocation();
            loc.setPitch(0f);
            this.anchor = loc;
        }
        World rootWorld = root != null && !root.isDead() ? root.getWorld() : null;
        if (rootWorld == null || !rootWorld.equals(anchor.getWorld())) {
            remove();
            spawn(target);
        }
        if (mode == Mode.FOLLOW) {
            Location rootLoc = anchor.clone();
            rootLoc.setYaw(0f);
            rootLoc.setPitch(0f);
            // a plain teleport refuses to move a vehicle with passengers
            root.teleport(rootLoc, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        // re-mount anything that got dismounted (chunk reload etc.)
        for (MirrorPart part : MirrorPart.values()) {
            ItemDisplay display = displays.get(part);
            if (display != null && !display.isDead() && display.getVehicle() != root) {
                root.addPassenger(display);
            }
        }
        if (mainItemDisplay != null && !mainItemDisplay.isDead() && mainItemDisplay.getVehicle() != root) {
            root.addPassenger(mainItemDisplay);
        }
        if (offItemDisplay != null && !offItemDisplay.isDead() && offItemDisplay.getVehicle() != root) {
            root.addPassenger(offItemDisplay);
        }

        float worldScale = worldScale(target);
        float heightScale = clampHeight(heightScale(target));

        boolean seated = pose.isSeated();
        if (seated) {
            heightScale = 1f;
        }

        // shadow scales with the player size (metadata only dirties on change)
        if (plugin.shadow() && root != null && !root.isDead()) {
            root.setShadowRadius(0.5f * heightScale * worldScale);
        }
        boolean leftHanded = pose.isLeftHanded();
        FBTMode fbtMode = pose.getFBTMode();

        VRBodyPartData headData = pose.getBodyPartData(VRBodyPart.HEAD);
        VRBodyPartData mainData = pose.getBodyPartData(VRBodyPart.MAIN_HAND);
        VRBodyPartData offData = pose.getBodyPartData(VRBodyPart.OFF_HAND);
        if (headData == null || mainData == null || offData == null) {
            return;
        }

        Location base = target.getLocation();
        Vector3f headPos = local(headData, base);
        Vector3f mainHandPos = local(mainData, base);
        Vector3f offHandPos = local(offData, base);
        Quaternionfc headQuat = headData.getRotation();
        Quaternionfc mainHandQuat = mainData.getRotation();
        Quaternionfc offHandQuat = offData.getRotation();
        Vector3f headDir = headQuat.transform(0f, 0f, -1f, new Vector3f());

        VRBodyPartData waistData = pose.getBodyPartData(VRBodyPart.WAIST);
        Vector3f waistPos = null;
        Quaternionfc waistQuat = null;
        if (waistData != null) {
            waistPos = local(waistData, base);
            waistQuat = waistData.getRotation();
        }
        if (fbtMode != FBTMode.ARMS_ONLY && waistData == null) {
            return;
        }

        // ----- render state (what AvatarRendererMixin derives client side) -----
        boolean passenger = target.isInsideVehicle();
        boolean visuallySwimming = target.getPose() == org.bukkit.entity.Pose.SWIMMING;
        boolean fallFlying = target.getPose() == org.bukkit.entity.Pose.FALL_FLYING;
        boolean inWater = target.isInWater();
        boolean crouching = target.isSneaking() && !visuallySwimming;

        if (visuallySwimming) {
            swimAmount = Math.min(1f, swimAmount + 0.09f);
        } else {
            swimAmount = Math.max(0f, swimAmount - 0.09f);
        }
        boolean laying = swimAmount > 0f || fallFlying;
        float layAmount = fallFlying ? 1f : swimAmount;
        boolean swimming = (laying && inWater) || fallFlying;
        boolean noLower = swimming || fbtMode == FBTMode.ARMS_ONLY;
        float xRot = swimming
            ? layAmount * (-HALF_PI - (float) Math.toRadians(base.getPitch()))
            : layAmount * -HALF_PI;

        // walk animation state (vanilla LivingEntity.calculateEntityAnimation)
        double ddx = base.getX() - lastX;
        double ddz = base.getZ() - lastZ;
        lastX = base.getX();
        lastZ = base.getZ();
        if (firstTick) {
            ddx = 0;
            ddz = 0;
            firstTick = false;
        }
        float moveSpeed = passenger ? 0f : Math.min(1f, (float) Math.sqrt(ddx * ddx + ddz * ddz) * 4f);
        walkSpeed += (moveSpeed - walkSpeed) * 0.4f;
        walkPos += walkSpeed;
        if (walkPos > WALK_POS_WRAP) {
            walkPos -= WALK_POS_WRAP;
        }

        // hurt flash: signalled to the SPD shader by shifting the Y marker 16 extra windows
        // down; interpolation is suspended for the whole flash so the jump never lerps
        boolean wantTint = plugin.hurtTint() && hurtTicks > 0;
        if (hurtTicks > 0) {
            hurtTicks--;
        }
        boolean tintChanged = wantTint != tintApplied;
        tintApplied = wantTint;
        snapInterpolation = tintApplied || tintChanged;

        float bodyYaw = VRMath.bodyYawRad(seated, fbtMode, leftHanded, headDir, mainHandPos, offHandPos, waistQuat);

        // ----- head -----
        Vector3f headPivot;
        if (!swimming) {
            headPivot = VRMath.headPivot(headPos, headQuat, worldScale, heightScale, new Vector3f());
        } else {
            headPivot = new Vector3f(headPos);
        }
        float progress = VRMath.bendProgress(crouching, passenger, headPivot, worldScale, heightScale);
        float heightOffset = 22f * progress;

        Matrix3f headM = new Matrix3f().set(headQuat);
        headM.rotateLocalY(bodyYaw + PI);
        headM.rotateLocalX(-xRot);
        Quaternionf headQ = VRMath.qFromMatrix(headM, new Quaternionf());

        Vector3f headModel = VRMath.worldToModel(headPivot, bodyYaw, worldScale, heightScale, new Vector3f());
        if (swimming) {
            headModel.z += 3f;
        }
        Vector3f bodyModel = new Vector3f(headModel);

        // ----- body + arm pivots -----
        float sideOffset = 5f;
        float bodyXRotMid = 0f; // body x rotation as seen by arm/leg placement (before the late +xRot)
        Quaternionf bodyQ = new Quaternionf();
        Vector3f leftArmModel = null;
        Vector3f rightArmModel = null;

        if (passenger) {
            Vector3f dir = new Vector3f(0f - bodyModel.x, 14f - bodyModel.y, 2f + heightOffset - bodyModel.z);
            Vector3f up = dir.cross(1f, 0f, 0f, new Vector3f());
            Matrix3f m = VRMath.pointAtModelM(dir, up, new Matrix3f());
            m.rotateLocalX(-xRot);
            Vector3f eulers = m.getEulerAnglesZYX(new Vector3f());
            bodyXRotMid = -eulers.x;
            VRMath.qFromMatrix(m, bodyQ);
        } else if (noLower) {
            float bx = PI * Math.max(0f, bodyModel.y / 22f) * 0.5f;
            if (laying) {
                float layX;
                if (swimming) {
                    layX = -xRot;
                } else {
                    float aboveGround = (heightOffset - 11f) / 11f;
                    layX = progress * (PI - HALF_PI * (1f + 0.3f * (1f - aboveGround)));
                }
                bx = lerp(layAmount, bx, layX);
                headModel.y -= 2f * layAmount;
                bodyModel.y -= 2f * layAmount;
            }
            bodyXRotMid = bx;
            float bxFinal = layAmount > 0f ? bx + xRot : bx;
            Vector3f bodyDir = new Vector3f(0f, (float) Math.cos(bxFinal), (float) Math.sin(bxFinal));
            VRMath.displayAxisQ(bodyDir, bodyQ);
        } else {
            // waist tracker: pointModelAtLocal with up from the tracker RIGHT vector
            Vector3f waistModelBase = VRMath.worldToModel(waistPos, bodyYaw, worldScale, heightScale, new Vector3f());
            Vector3f bodyDir = waistModelBase.sub(bodyModel, new Vector3f());
            Vector3f waistRight = waistQuat.transform(-1f, 0f, 0f, new Vector3f());
            VRMath.worldDirToModel(waistRight, bodyYaw);
            Vector3f bodyUp = bodyDir.cross(waistRight, new Vector3f());
            Matrix3f m;
            if (bodyDir.length() <= 0.0001f) {
                m = new Matrix3f();
            } else {
                m = VRMath.pointAtModelM(bodyDir, bodyUp, new Matrix3f());
            }
            // arm pivots use the matrix before the lay correction (vivecraft order)
            Vector3f l = m.transform(sideOffset, 2f, 0f, new Vector3f());
            leftArmModel = new Vector3f(bodyModel.x + l.x, bodyModel.y + l.y, bodyModel.z - l.z);
            Vector3f r = m.transform(-sideOffset, 2f, 0f, new Vector3f());
            rightArmModel = new Vector3f(bodyModel.x + r.x, bodyModel.y + r.y, bodyModel.z - r.z);
            m.rotateLocalX(-xRot);
            VRMath.qFromMatrix(m, bodyQ);
        }

        if (passenger || noLower) {
            float cosBody = (float) Math.cos(bodyXRotMid);
            leftArmModel = new Vector3f(bodyModel.x + sideOffset, 2f * cosBody + bodyModel.y, bodyModel.z);
            rightArmModel = new Vector3f(bodyModel.x - sideOffset, 2f * cosBody + bodyModel.y, bodyModel.z);
        }

        setPose(MirrorPart.HEAD, headModel, headQ);
        setPose(MirrorPart.TORSO, bodyModel, bodyQ);
        Vector3f waistModel = new Vector3f(bodyModel)
            .add(VRMath.modelPartTransform(bodyQ, 0f, 6f, 0f, new Vector3f()));
        setPose(MirrorPart.WAIST, waistModel, bodyQ);

        // ----- thigh pivots -----
        Vector3f leftLegModel = new Vector3f(1.9f, 12f, 0f);
        Vector3f rightLegModel = new Vector3f(-1.9f, 12f, 0f);
        if (passenger) {
            leftLegModel.z = heightOffset;
            rightLegModel.z = heightOffset;
        } else if (laying && noLower) {
            if (swimming) {
                Vector3f v = new Vector3f(0f, 12f, 0f).rotateX(-xRot);
                leftLegModel.y = bodyModel.y + v.y;
                leftLegModel.z = bodyModel.z + v.z;
            } else {
                float cos2 = (float) Math.cos(bodyXRotMid);
                cos2 *= cos2;
                leftLegModel.y += 10.25f - 2f * cos2;
                leftLegModel.z = bodyModel.z + 13f - cos2 * 8f;
            }
            leftLegModel.x += bodyModel.x;
            rightLegModel.x += bodyModel.x;
            rightLegModel.y = leftLegModel.y;
            rightLegModel.z = leftLegModel.z;
        } else if (fbtMode != FBTMode.ARMS_ONLY) {
            Vector3f waistModelBase = VRMath.worldToModel(waistPos, bodyYaw, worldScale, heightScale, new Vector3f());
            Vector3f lo = waistQuat.transform(-1.9f, -2f, 0f, new Vector3f());
            VRMath.worldDirToModel(lo, bodyYaw);
            leftLegModel = new Vector3f(waistModelBase).add(lo);
            Vector3f ro = waistQuat.transform(1.9f, -2f, 0f, new Vector3f());
            VRMath.worldDirToModel(ro, bodyYaw);
            rightLegModel = new Vector3f(waistModelBase).add(ro);
        } else {
            leftLegModel.x += bodyModel.x;
            rightLegModel.x += bodyModel.x;
        }

        boolean legStyleArms = plugin.legStyleArms();
        if (!passenger && layAmount < 1f && fbtMode == FBTMode.ARMS_ONLY) {
            float sinBody = (float) Math.sin(bodyXRotMid);
            // the extra Y shift is a VRPlayerModel_WithArmsLegs-only term
            float newLegY = 12f + Math.min(bodyModel.y, 0f) + (legStyleArms ? 0f : 10f * sinBody);
            float newLegZ = bodyModel.z + 10f * sinBody;
            leftLegModel.y = lerp(layAmount, newLegY, leftLegModel.y);
            leftLegModel.z = lerp(layAmount, newLegZ, leftLegModel.z);
            rightLegModel.y = leftLegModel.y;
            rightLegModel.z = leftLegModel.z;
        }

        // ----- arms -----
        Vector3f rightElbowPos = null;
        Vector3f leftElbowPos = null;
        if (fbtMode == FBTMode.WITH_JOINTS) {
            VRBodyPartData rightElbowData = pose.getBodyPartData(VRBodyPart.RIGHT_ELBOW);
            VRBodyPartData leftElbowData = pose.getBodyPartData(VRBodyPart.LEFT_ELBOW);
            if (rightElbowData != null) {
                rightElbowPos = local(rightElbowData, base);
            }
            if (leftElbowData != null) {
                leftElbowPos = local(leftElbowData, base);
            }
        }

        boolean armAim = plugin.lowerArmAim();
        float armOffset = slim ? 0.5f : 1f;
        if (leftHanded) {
            armOffset = -armOffset;
            limb(MirrorPart.LEFT_ARM, MirrorPart.LEFT_FOREARM, leftArmModel, mainHandPos, mainHandQuat,
                0f, -armOffset, rightElbowPos, true, true, armAim, xRot, bodyYaw, worldScale, heightScale);
            limb(MirrorPart.RIGHT_ARM, MirrorPart.RIGHT_FOREARM, rightArmModel, offHandPos, offHandQuat,
                0f, armOffset, leftElbowPos, true, true, armAim, xRot, bodyYaw, worldScale, heightScale);
        } else {
            limb(MirrorPart.RIGHT_ARM, MirrorPart.RIGHT_FOREARM, rightArmModel, mainHandPos, mainHandQuat,
                0f, -armOffset, rightElbowPos, true, true, armAim, xRot, bodyYaw, worldScale, heightScale);
            limb(MirrorPart.LEFT_ARM, MirrorPart.LEFT_FOREARM, leftArmModel, offHandPos, offHandQuat,
                0f, armOffset, leftElbowPos, true, true, armAim, xRot, bodyYaw, worldScale, heightScale);
        }

        // ----- legs -----
        Vector3f rightKneePos = null;
        Vector3f leftKneePos = null;
        if (fbtMode == FBTMode.WITH_JOINTS) {
            VRBodyPartData rightKneeData = pose.getBodyPartData(VRBodyPart.RIGHT_KNEE);
            VRBodyPartData leftKneeData = pose.getBodyPartData(VRBodyPart.LEFT_KNEE);
            if (rightKneeData != null) {
                rightKneePos = local(rightKneeData, base);
            }
            if (leftKneeData != null) {
                leftKneePos = local(leftKneeData, base);
            }
        }

        boolean noLegs = passenger || (laying && (inWater || fbtMode == FBTMode.ARMS_ONLY)) || fallFlying;
        if (!noLegs) {
            // walk swing offsets (VRPlayerModel_WithArmsLegs)
            Vector3f footOffset = new Vector3f();
            Vector3f kneeOffset = new Vector3f();
            if (plugin.walkAnimation() && mode != Mode.STATIC) {
                float limbRotation = (float) Math.cos(walkPos * 0.6662f) * walkSpeed;
                footOffset.set(0f, -0.5f, 0f)
                    .rotateX(limbRotation)
                    .sub(0f, -0.5f, 0f)
                    .mul(1f, 0.75f, 1f)
                    .rotateY(-bodyYaw);
                kneeOffset.set(0f, -0.5f, 0f)
                    .rotateX(-Math.abs(limbRotation))
                    .sub(0f, -0.5f, 0f)
                    .rotateY(-bodyYaw);
            }
            if (leftKneePos != null) {
                leftKneePos.add(kneeOffset);
            }
            if (rightKneePos != null) {
                rightKneePos.add(kneeOffset);
            }

            boolean legAim = plugin.legsAimAtKnee();
            if (fbtMode == FBTMode.ARMS_ONLY && legStyleArms) {
                // SPLIT_ARMS observers see vanilla 12px legs with the vanilla hip walk swing
                float walkRot = 0f;
                if (plugin.walkAnimation() && mode != Mode.STATIC) {
                    walkRot = (float) Math.cos(walkPos * 0.6662f) * 1.4f * walkSpeed;
                }
                poseVanillaLeg(MirrorPart.RIGHT_LEG, MirrorPart.LOWER_RIGHT_LEG, rightLegModel, walkRot);
                poseVanillaLeg(MirrorPart.LEFT_LEG, MirrorPart.LOWER_LEFT_LEG, leftLegModel, -walkRot);
            } else if (fbtMode == FBTMode.ARMS_ONLY) {
                float minBodyY = Math.min(bodyModel.y, 0f);
                Vector3f leftFootModel = new Vector3f(leftLegModel.x, 24f + minBodyY, leftLegModel.z);
                Vector3f rightFootModel = new Vector3f(rightLegModel.x, 24f + minBodyY, rightLegModel.z);
                Vector3f leftFootPos = VRMath.modelToLocal(leftFootModel, bodyYaw, worldScale, heightScale, new Vector3f());
                Vector3f rightFootPos = VRMath.modelToLocal(rightFootModel, bodyYaw, worldScale, heightScale, new Vector3f());
                leftFootPos.add(footOffset);
                rightFootPos.add(-footOffset.x, footOffset.y, -footOffset.z);
                Quaternionf footQuat = new Quaternionf().rotateY(PI - bodyYaw);
                limb(MirrorPart.LEFT_LEG, MirrorPart.LOWER_LEFT_LEG, leftLegModel, leftFootPos, footQuat,
                    -HALF_PI, 0f, null, false, false, legAim, xRot, bodyYaw, worldScale, heightScale);
                limb(MirrorPart.RIGHT_LEG, MirrorPart.LOWER_RIGHT_LEG, rightLegModel, rightFootPos, footQuat,
                    -HALF_PI, 0f, null, false, false, legAim, xRot, bodyYaw, worldScale, heightScale);
            } else {
                VRBodyPartData rightFootData = pose.getBodyPartData(VRBodyPart.RIGHT_FOOT);
                VRBodyPartData leftFootData = pose.getBodyPartData(VRBodyPart.LEFT_FOOT);
                if (leftFootData != null) {
                    Vector3f footPos = local(leftFootData, base).add(footOffset);
                    limb(MirrorPart.LEFT_LEG, MirrorPart.LOWER_LEFT_LEG, leftLegModel, footPos,
                        leftFootData.getRotation(), -HALF_PI, 0f, leftKneePos, false, false, false,
                        xRot, bodyYaw, worldScale, heightScale);
                }
                if (rightFootData != null) {
                    Vector3f footPos = local(rightFootData, base).add(-footOffset.x, footOffset.y, -footOffset.z);
                    limb(MirrorPart.RIGHT_LEG, MirrorPart.LOWER_RIGHT_LEG, rightLegModel, footPos,
                        rightFootData.getRotation(), -HALF_PI, 0f, rightKneePos, false, false, false,
                        xRot, bodyYaw, worldScale, heightScale);
                }
            }
        } else {
            // feet aligned with the thighs (riding / laying / elytra)
            Quaternionf leftLegQ;
            Quaternionf rightLegQ;
            if (passenger) {
                // vanilla sitting pose eulers, converted to display space (x,y negated)
                leftLegQ = new Quaternionf().rotationZYX(-0.07853982f, 0.31415927f, 1.4137167f);
                rightLegQ = new Quaternionf().rotationZYX(0.07853982f, -0.31415927f, 1.4137167f);
            } else {
                leftLegQ = new Quaternionf();
                rightLegQ = new Quaternionf();
            }
            Vector3f v = VRMath.modelPartTransform(leftLegQ, 0f, 12f, 0f, new Vector3f());
            Vector3f leftFootModel = new Vector3f(leftLegModel).add(v);
            Vector3f rightFootModel = new Vector3f(
                rightLegModel.x - v.x,
                rightLegModel.y + v.y,
                rightLegModel.z + (passenger ? v.z : -v.z));
            setPose(MirrorPart.LEFT_LEG, leftLegModel, leftLegQ);
            setPose(MirrorPart.RIGHT_LEG, rightLegModel, rightLegQ);
            setPose(MirrorPart.LOWER_LEFT_LEG, leftFootModel, leftLegQ);
            setPose(MirrorPart.LOWER_RIGHT_LEG, rightFootModel, rightLegQ);
        }

        // ----- swim/lay rotation offset (ModelUtils.applySwimRotationOffset) -----
        if (layAmount > 0f) {
            Vector3f off = new Vector3f();
            if (visuallySwimming && !fallFlying) {
                off.set(0f, 17.06125f, 5.125f).rotateX(-xRot);
                off.y += 2f;
            }
            for (PartPose pp : partPoses.values()) {
                if (!pp.active) {
                    continue;
                }
                pp.pos.sub(off);
                pp.pos.y -= 24f;
                pp.pos.rotateX(xRot);
                pp.pos.y += 24f;
            }
        }

        poseAll(bodyYaw, worldScale, heightScale);

        // ----- held items (ItemInHandLayer + WithArms.translateToHand) -----
        if (plugin.showHeldItems() && mainItemDisplay != null) {
            MirrorPart mainArmPart = leftHanded ? MirrorPart.LEFT_FOREARM : MirrorPart.RIGHT_FOREARM;
            MirrorPart offArmPart = leftHanded ? MirrorPart.RIGHT_FOREARM : MirrorPart.LEFT_FOREARM;
            lastMainItem = placeHeldItem(mainItemDisplay, target.getInventory().getItemInMainHand(), lastMainItem,
                partPoses.get(mainArmPart), leftHanded, bodyYaw, worldScale, heightScale);
            lastOffItem = placeHeldItem(offItemDisplay, target.getInventory().getItemInOffHand(), lastOffItem,
                partPoses.get(offArmPart), !leftHanded, bodyYaw, worldScale, heightScale);
        }

        // ----- name tag (the real one is hidden by the fake invisibility) -----
        if (nameTagDisplay != null && !nameTagDisplay.isDead()) {
            Location tagLoc = anchor.clone().add(0, target.getHeight() + 0.5, 0);
            tagLoc.setYaw(0f);
            tagLoc.setPitch(0f);
            nameTagDisplay.teleport(tagLoc);
        }

    }

    /** vanilla leg: thigh and lower leg rigid, rotated around the hip (SPLIT_ARMS observers) */
    private void poseVanillaLeg(MirrorPart upper, MirrorPart lower, Vector3fc legModel, float legXRot) {
        Vector3f dir = new Vector3f(0f, (float) Math.cos(legXRot), (float) Math.sin(legXRot));
        Quaternionf q = VRMath.displayAxisQ(dir, new Quaternionf());
        // anchor the lower segment at the foot end (12px down the leg), its cube extends back up
        Vector3f foot = new Vector3f(legModel)
            .add(VRMath.modelPartTransform(q, 0f, 12f, 0f, new Vector3f()));
        setPose(upper, legModel, q);
        setPose(lower, foot, q);
    }

    /** dispatches to the connected (Vivecraft default) or split limb math */
    private void limb(MirrorPart upperPart, MirrorPart lowerPart, Vector3f upperModel,
                      Vector3fc lowerPosLocal, Quaternionfc lowerRot, float lowerXRot, float lowerXOffset,
                      Vector3fc jointPosLocal, boolean jointDown, boolean isArm, boolean aimLower, float xRot,
                      float bodyYaw, float worldScale, float heightScale) {
        if (plugin.limbsConnected()) {
            connectedLimb(upperPart, lowerPart, upperModel, lowerPosLocal, lowerRot, lowerXOffset,
                jointPosLocal, jointDown, isArm, xRot, bodyYaw, worldScale, heightScale);
        } else {
            splitLimb(upperPart, lowerPart, upperModel, lowerPosLocal, lowerRot, lowerXRot, lowerXOffset,
                jointPosLocal, jointDown, aimLower, xRot, bodyYaw, worldScale, heightScale);
        }
    }

    /**
     * 1:1 port of VRPlayerModel_WithArms.positionConnectedLimb (the Vivecraft DEFAULT,
     * playerLimbsConnected = true): both segments point at the estimated joint, the
     * shoulder/hip lifts when the limb is raised, and the arm length varies slightly.
     * The lower part does NOT follow the tracker rotation in this mode.
     */
    private void connectedLimb(MirrorPart upperPart, MirrorPart lowerPart, Vector3f upperModel,
                               Vector3fc lowerPosLocal, Quaternionfc lowerRot, float lowerXOffset,
                               Vector3fc jointPosLocal, boolean jointDown, boolean isArm, float xRot,
                               float bodyYaw, float worldScale, float heightScale) {
        if (upperModel == null || lowerPosLocal == null || lowerRot == null) {
            return;
        }

        Vector3f lowerModel = VRMath.worldToModel(lowerPosLocal, bodyYaw, worldScale, heightScale, new Vector3f());

        float armLength = 10f;
        if (isArm) {
            // increase arm length to the front, since human shoulders can move forward
            Vector3f n = new Vector3f(lowerModel);
            if (n.length() > 0.0001f) {
                n.normalize();
                armLength += 2f * n.z * n.z;
            }
        }

        Vector3f d = lowerModel.sub(upperModel, new Vector3f());
        float length = d.length();
        if (length > 0.0001f) {
            // move shoulders up when having the arms up, the rotation point is slightly offset
            upperModel.y -= 2f * Math.min(1f, length / armLength) * Math.max(0f, -d.y / length);
        }
        if (plugin.limbsLimit()) {
            d.set(lowerModel).sub(upperModel);
            if (d.length() > armLength) {
                lowerModel.set(d.normalize().mul(armLength).add(upperModel));
            }
        }

        Vector3f jointDir = VRMath.estimateJointDir(upperModel, lowerModel, lowerRot, bodyYaw,
            jointDown, jointPosLocal, worldScale, heightScale, new Vector3f());
        Vector3f joint = VRMath.estimateJoint(upperModel, lowerModel, jointDir, armLength, new Vector3f());

        if (jointDown) {
            jointDir.mul(-1f);
        }

        Vector3f jointOffset = lowerModel.sub(upperModel, new Vector3f()).cross(jointDir);
        if (jointOffset.length() > 0.001f) {
            jointOffset.normalize();
        }
        jointOffset.mul(lowerXOffset * 0.5f);

        Vector3f upperDir = joint.sub(upperModel, new Vector3f()).add(jointOffset);
        Quaternionf upperQ = new Quaternionf();
        if (upperDir.length() > 0.0001f) {
            VRMath.pointAtModelQ(upperDir, jointDir, -xRot, upperQ);
        }

        Vector3f lowerDir = lowerModel.sub(joint, new Vector3f()).add(jointOffset);
        Quaternionf lowerQ = new Quaternionf();
        if (lowerDir.length() > 0.0001f) {
            VRMath.pointAtModelQ(lowerDir, jointDir, -xRot, lowerQ);
        }

        setPose(upperPart, upperModel, upperQ);
        setPose(lowerPart, lowerModel, lowerQ);
    }

    /**
     * 1:1 port of VRPlayerModel_WithArms.positionSplitLimb:
     * lower part anchored at the tracker position with the full tracker rotation
     * (or aimed at the estimated joint when aimLower is set),
     * upper part points from its true pivot at the estimated joint (joint dir as up vector).
     */
    private void splitLimb(MirrorPart upperPart, MirrorPart lowerPart, Vector3fc upperModel,
                           Vector3fc lowerPosLocal, Quaternionfc lowerRot, float lowerXRot, float lowerXOffset,
                           Vector3fc jointPosLocal, boolean jointDown, boolean aimLower, float xRot,
                           float bodyYaw, float worldScale, float heightScale) {
        if (upperModel == null || lowerPosLocal == null || lowerRot == null) {
            return;
        }

        Vector3f lowerModel = VRMath.worldToModel(lowerPosLocal, bodyYaw, worldScale, heightScale, new Vector3f());

        Vector3f jointDir = VRMath.estimateJointDir(upperModel, lowerModel, lowerRot, bodyYaw,
            jointDown, jointPosLocal, worldScale, heightScale, new Vector3f());
        Vector3f joint = VRMath.estimateJoint(upperModel, lowerModel, jointDir, 12f, new Vector3f());

        if (jointDown) {
            jointDir.mul(-1f);
        }

        Vector3f jointOffset = lowerModel.sub(upperModel, new Vector3f()).cross(jointDir);
        if (jointOffset.length() > 0.001f) {
            jointOffset.normalize();
        }
        jointOffset.mul(lowerXOffset * 0.5f);
        joint.add(jointOffset);

        Vector3f upperDir = joint.sub(upperModel, new Vector3f());
        Quaternionf upperQ = new Quaternionf();
        if (upperDir.length() > 0.0001f) {
            VRMath.pointAtModelQ(upperDir, jointDir, -xRot, upperQ);
        }

        Quaternionf lowerQ;
        if (aimLower) {
            Vector3f lowerDir = lowerModel.sub(joint, new Vector3f());
            lowerQ = VRMath.displayAxisQ(lowerDir, new Quaternionf());
        } else {
            lowerQ = VRMath.toModelDirQ(bodyYaw, lowerRot, -xRot + lowerXRot, new Quaternionf());
        }
        if (lowerPart == MirrorPart.RIGHT_FOREARM || lowerPart == MirrorPart.LEFT_FOREARM) {
            Quaternionf twist = plugin.lowerTwist();
            if (twist != null) {
                lowerQ.mul(twist);
            }
        }

        setPose(upperPart, upperModel, upperQ);
        setPose(lowerPart, lowerModel, lowerQ);
    }

    private void poseAll(float bodyYaw, float worldScale, float heightScale) {
        for (Map.Entry<MirrorPart, PartPose> entry : partPoses.entrySet()) {
            PartPose pp = entry.getValue();
            if (pp.active) {
                pose(entry.getKey(), pp.pos, pp.rot, bodyYaw, worldScale, heightScale);
            }
        }
    }

    private void pose(MirrorPart part, Vector3fc modelPos, Quaternionfc rot, float bodyYaw, float worldScale, float heightScale) {
        ItemDisplay display = displays.get(part);
        if (display == null || display.isDead()) {
            return;
        }

        // shift the pivot so the SPD cube lands where the Vivecraft cube is, rotated with the part
        Vector3f entityModel = VRMath.offsetModelPos(modelPos, rot,
            part.localOffset(slim, new Vector3f()), new Vector3f());
        Vector3f local = VRMath.modelToLocal(entityModel, bodyYaw, worldScale, heightScale, new Vector3f());

        // passenger of the root with entity yaw 0: fold the body yaw into the rotation and put
        // the position into the translation. The SPD marker stays a pure +Y offset on top.
        Quaternionf q = new Quaternionf().rotationY(-(bodyYaw + PI)).mul(new Quaternionf(rot));

        float scale = heightScale * worldScale;
        // metadata only dirties on change, so setting the same duration every tick is free
        display.setInterpolationDuration(snapInterpolation ? 0 : plugin.interpolationTicks());
        // restart client-side interpolation every tick, otherwise transformation updates snap
        display.setInterpolationDelay(0);
        display.setTransformation(new Transformation(
            new Vector3f(local.x, local.y + part.markerOffset + (tintApplied ? HURT_MARKER : 0f), local.z),
            new Quaternionf(), new Vector3f(scale, scale, scale), q));
    }

    private ItemStack placeHeldItem(ItemDisplay display, ItemStack held, ItemStack cached, PartPose hand,
                                    boolean leftSide, float bodyYaw, float worldScale, float heightScale) {
        if (display == null || display.isDead() || !hand.active) {
            return cached;
        }

        boolean empty = held == null || held.getType().isAir();
        if (empty) {
            if (cached != null) {
                display.setItemStack(null);
            }
            return null;
        }

        // translateToHand: hand part + (+-1, -10.4, 0)px, then ItemInHandLayer:
        // RotX(-90), RotY(180), translate(+-1, 2, -10)px -> net offset (0, -0.4, -2) part-local px
        Vector3fc io = plugin.itemOffset();
        Vector3f offset = VRMath.modelPartTransform(hand.rot,
            io.x(), -0.4f + io.y(), -2f + io.z(), new Vector3f());
        Vector3f itemModel = new Vector3f(hand.pos).add(offset);

        // hand-local item correction RotX(90)*RotZ(180), calibrated in game
        // (display-pipeline equivalent of vanilla's RotX(-90)*RotY(180) hand layer)
        Quaternionf itemQ = new Quaternionf(hand.rot)
            .rotateX(HALF_PI)
            .rotateZ(PI);
        Quaternionf itemTwist = plugin.itemTwist();
        if (itemTwist != null) {
            itemQ.mul(itemTwist);
        }

        Vector3f local = VRMath.modelToLocal(itemModel, bodyYaw, worldScale, heightScale, new Vector3f());
        Quaternionf q = new Quaternionf().rotationY(-(bodyYaw + PI)).mul(itemQ);

        ItemDisplay.ItemDisplayTransform ctx = leftSide
            ? ItemDisplay.ItemDisplayTransform.THIRDPERSON_LEFTHAND
            : ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND;
        if (display.getItemDisplayTransform() != ctx) {
            display.setItemDisplayTransform(ctx);
        }

        float scale = 0.9375f * heightScale * worldScale;
        display.setInterpolationDelay(0);
        display.setTransformation(new Transformation(
            new Vector3f(local.x, local.y, local.z), new Quaternionf(), new Vector3f(scale, scale, scale), q));

        if (cached == null || !cached.equals(held)) {
            display.setItemStack(held.clone());
            return held.clone();
        }
        return cached;
    }
}

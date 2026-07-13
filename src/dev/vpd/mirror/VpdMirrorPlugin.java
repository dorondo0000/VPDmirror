package dev.vpd.mirror;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.vivecraft.ViveMain;
import org.vivecraft.api.VRAPI;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class VpdMirrorPlugin extends JavaPlugin implements Listener {

    public static final String TAG_GLOBAL = "vpd_vr";

    private static VpdMirrorPlugin instance;

    private final Map<UUID, MirrorSession> sessions = new HashMap<>();
    private final Set<UUID> autoDisabled = new HashSet<>();
    /** target -> viewers that currently received a fake invisibility effect */
    private final Map<UUID, Set<UUID>> fakeInvisViewers = new HashMap<>();
    /** target -> viewers we hid the displays from */
    private final Map<UUID, Set<UUID>> hiddenViewers = new HashMap<>();
    /** entity id -> uuid of follow-mirrored VR players; read by the netty packet filter */
    private final Map<Integer, UUID> mirroredEntityIds = new ConcurrentHashMap<>();
    /** viewers with the Vivecraft mod; read by the netty packet filter */
    private final Set<UUID> moddedViewers = ConcurrentHashMap.newKeySet();

    private float viewRange = 0.6f;
    private boolean autoSlim = true;
    private String defaultVariant = "default";
    private boolean autoMirror = true;
    private boolean hideFromModded = true;
    private boolean fakeInvisibility = true;
    private boolean showHeldItems = true;
    private boolean walkAnimation = true;
    private boolean legsAimAtKnee = true;
    private boolean limbsConnected = true;
    private boolean limbsLimit = false;
    private boolean legStyleArms = true;
    private boolean lowerArmAim = false;
    private Quaternionf lowerTwist = null;
    private Quaternionf itemTwist = null;
    private final Vector3f itemOffset = new Vector3f();
    private int interpolationTicks = 2;
    private boolean nameTag = true;
    private boolean hurtTint = true;
    private boolean shadow = true;

    private int tickCounter;

    public static VpdMirrorPlugin instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        sweepOrphans();
        for (Player player : Bukkit.getOnlinePlayers()) {
            inject(player);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
    }

    @Override
    public void onDisable() {
        for (MirrorSession session : sessions.values()) {
            cleanupFakeInvis(session.targetId());
            session.remove();
        }
        sessions.clear();
        mirroredEntityIds.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            uninject(player);
        }
        sweepOrphans();
        instance = null;
    }

    /**
     * Decides on the netty thread whether outgoing metadata/equipment of the given entity
     * must be rewritten to fake invisibility for this viewer.
     */
    public boolean shouldFakeInvis(UUID viewerId, int entityId) {
        if (!fakeInvisibility) {
            return false;
        }
        UUID targetId = mirroredEntityIds.get(entityId);
        if (targetId == null || targetId.equals(viewerId)) {
            return false;
        }
        return !moddedViewers.contains(viewerId);
    }

    private void inject(Player player) {
        try {
            var pipeline = ((CraftPlayer) player).getHandle().connection.connection.channel.pipeline();
            if (pipeline.get(PacketInterceptor.NAME) == null && pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", PacketInterceptor.NAME,
                    new PacketInterceptor(this, player.getUniqueId()));
            }
        } catch (Throwable t) {
            getLogger().warning("Failed to inject packet filter for " + player.getName() + ": " + t);
        }
    }

    private void uninject(Player player) {
        try {
            var pipeline = ((CraftPlayer) player).getHandle().connection.connection.channel.pipeline();
            if (pipeline.get(PacketInterceptor.NAME) != null) {
                pipeline.remove(PacketInterceptor.NAME);
            }
        } catch (Throwable ignored) {
        }
    }

    private void loadSettings() {
        viewRange = (float) getConfig().getDouble("view-range", 0.6);
        autoSlim = getConfig().getBoolean("auto-slim", true);
        defaultVariant = getConfig().getString("default-variant", "default");
        autoMirror = getConfig().getBoolean("auto-mirror", true);
        hideFromModded = getConfig().getBoolean("hide-from-modded", true);
        fakeInvisibility = getConfig().getBoolean("fake-invisibility", true);
        showHeldItems = getConfig().getBoolean("show-held-items", true);
        walkAnimation = getConfig().getBoolean("walk-animation", true);
        legsAimAtKnee = getConfig().getBoolean("legs-aim-at-knee", true);
        limbsConnected = getConfig().getBoolean("limbs-connected", true);
        limbsLimit = getConfig().getBoolean("limbs-limit", false);
        legStyleArms = !"legs".equalsIgnoreCase(getConfig().getString("leg-style", "arms"));
        lowerArmAim = "aim".equalsIgnoreCase(getConfig().getString("lower-arm-mode", "quat"));
        interpolationTicks = Math.max(1, Math.min(10, getConfig().getInt("interpolation-ticks", 2)));
        nameTag = getConfig().getBoolean("name-tag", true);
        itemOffset.set(
            (float) getConfig().getDouble("item-offset-x", 0),
            (float) getConfig().getDouble("item-offset-y", 0),
            (float) getConfig().getDouble("item-offset-z", 0));
        setLowerTwist(
            (float) getConfig().getDouble("lower-twist-yaw", 0),
            (float) getConfig().getDouble("lower-twist-pitch", 0),
            (float) getConfig().getDouble("lower-twist-roll", 0));
        setItemTwist(
            (float) getConfig().getDouble("item-twist-yaw", 0),
            (float) getConfig().getDouble("item-twist-pitch", 0),
            (float) getConfig().getDouble("item-twist-roll", 0));
        hurtTint = getConfig().getBoolean("hurt-tint", true);
        shadow = getConfig().getBoolean("shadow", true);
        autoDisabled.clear();
        for (String id : getConfig().getStringList("disabled-players")) {
            try {
                autoDisabled.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void setLowerTwist(float yawDeg, float pitchDeg, float rollDeg) {
        lowerTwist = makeTwist(yawDeg, pitchDeg, rollDeg);
    }

    private void setItemTwist(float yawDeg, float pitchDeg, float rollDeg) {
        itemTwist = makeTwist(yawDeg, pitchDeg, rollDeg);
    }

    private static Quaternionf makeTwist(float yawDeg, float pitchDeg, float rollDeg) {
        if (yawDeg == 0f && pitchDeg == 0f && rollDeg == 0f) {
            return null;
        }
        return new Quaternionf()
            .rotateY((float) Math.toRadians(yawDeg))
            .rotateX((float) Math.toRadians(pitchDeg))
            .rotateZ((float) Math.toRadians(rollDeg));
    }

    public float viewRange() {
        return viewRange;
    }

    public boolean showHeldItems() {
        return showHeldItems;
    }

    public boolean walkAnimation() {
        return walkAnimation;
    }

    public boolean legsAimAtKnee() {
        return legsAimAtKnee;
    }

    public boolean limbsConnected() {
        return limbsConnected;
    }

    public boolean limbsLimit() {
        return limbsLimit;
    }

    public boolean legStyleArms() {
        return legStyleArms;
    }

    public int interpolationTicks() {
        return interpolationTicks;
    }

    public boolean nameTag() {
        return nameTag;
    }

    public org.joml.Vector3fc itemOffset() {
        return itemOffset;
    }

    public boolean lowerArmAim() {
        return lowerArmAim;
    }

    public Quaternionf lowerTwist() {
        return lowerTwist;
    }

    public Quaternionf itemTwist() {
        return itemTwist;
    }

    public boolean hurtTint() {
        return hurtTint;
    }

    public boolean shadow() {
        return shadow;
    }

    public boolean isAutoMirrorEnabled(UUID playerId) {
        return !autoDisabled.contains(playerId);
    }

    public void setAutoMirrorEnabled(UUID playerId, boolean enabled) {
        if (enabled) {
            autoDisabled.remove(playerId);
        } else {
            autoDisabled.add(playerId);
            MirrorSession session = sessions.get(playerId);
            if (session != null && session.mode() == MirrorSession.Mode.FOLLOW) {
                endSession(playerId);
            }
        }
        List<String> ids = new ArrayList<>();
        for (UUID id : autoDisabled) {
            ids.add(id.toString());
        }
        getConfig().set("disabled-players", ids);
        saveConfig();
    }

    public MirrorSession sessionOf(UUID playerId) {
        return sessions.get(playerId);
    }

    private void tick() {
        tickCounter++;

        // auto-start follow mirrors for VR players
        if (autoMirror) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (sessions.containsKey(player.getUniqueId())
                    || autoDisabled.contains(player.getUniqueId())
                    || VRAPI.instance().getVRPose(player) == null) {
                    continue;
                }
                startSession(player, anchorFrom(player), MirrorSession.Mode.FOLLOW);
            }
        }

        Iterator<Map.Entry<UUID, MirrorSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, MirrorSession> entry = it.next();
            MirrorSession session = entry.getValue();
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target == null || !target.isOnline()) {
                cleanupFakeInvis(entry.getKey());
                session.remove();
                it.remove();
                continue;
            }
            if (session.mode() == MirrorSession.Mode.FOLLOW && VRAPI.instance().getVRPose(target) == null) {
                // player left VR
                cleanupFakeInvis(entry.getKey());
                session.remove();
                it.remove();
                continue;
            }
            if (!session.isLive()) {
                continue;
            }
            try {
                session.update(target);
            } catch (Exception ex) {
                getLogger().log(Level.SEVERE, "Mirror update failed for " + target.getName() + ", stopping mirror", ex);
                cleanupFakeInvis(entry.getKey());
                session.remove();
                it.remove();
            }
        }

        if (tickCounter % 20 == 0) {
            reconcileVisibility();
        }
    }

    /** hide displays from modded viewers, fake-invis the real player for unmodded viewers */
    private void reconcileVisibility() {
        for (MirrorSession session : sessions.values()) {
            if (session.mode() != MirrorSession.Mode.FOLLOW) {
                continue;
            }
            Player target = Bukkit.getPlayer(session.targetId());
            if (target == null) {
                continue;
            }
            // keep the entity id mapping fresh for the packet filter (respawns can change it)
            mirroredEntityIds.values().removeIf(id -> id.equals(session.targetId()));
            mirroredEntityIds.put(target.getEntityId(), session.targetId());

            Set<UUID> hidden = hiddenViewers.computeIfAbsent(session.targetId(), k -> new HashSet<>());
            Set<UUID> invis = fakeInvisViewers.computeIfAbsent(session.targetId(), k -> new HashSet<>());

            for (Player viewer : Bukkit.getOnlinePlayers()) {
                boolean self = viewer.getUniqueId().equals(session.targetId());
                boolean modded = ViveMain.isVivePlayer(viewer);
                if (modded) {
                    moddedViewers.add(viewer.getUniqueId());
                } else {
                    moddedViewers.remove(viewer.getUniqueId());
                }

                // the target is a modded VR player, hide the rig from them as well
                if (hideFromModded) {
                    if (modded) {
                        if (hidden.add(viewer.getUniqueId())) {
                            for (org.bukkit.entity.Display display : session.allDisplays()) {
                                viewer.hideEntity(this, display);
                            }
                        }
                    } else if (hidden.remove(viewer.getUniqueId())) {
                        for (org.bukkit.entity.Display display : session.allDisplays()) {
                            viewer.showEntity(this, display);
                        }
                    }
                }

                if (fakeInvisibility && !self) {
                    if (!modded) {
                        sendFakeInvis(viewer, target);
                        invis.add(viewer.getUniqueId());
                    } else if (invis.remove(viewer.getUniqueId())) {
                        sendRemoveInvis(viewer, target);
                    }
                }
            }
        }
    }

    /** immediately hides freshly spawned displays from all modded viewers (incl. the target) */
    public void refreshVisibility(MirrorSession session) {
        if (session.mode() != MirrorSession.Mode.FOLLOW || !hideFromModded) {
            return;
        }
        Set<UUID> hidden = hiddenViewers.computeIfAbsent(session.targetId(), k -> new HashSet<>());
        hidden.clear();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (ViveMain.isVivePlayer(viewer)) {
                hidden.add(viewer.getUniqueId());
                for (org.bukkit.entity.Display display : session.allDisplays()) {
                    viewer.hideEntity(this, display);
                }
            }
        }
    }

    /** shared entity flags byte (metadata index 0), bit 0x20 = invisible */
    private static final EntityDataAccessor<Byte> SHARED_FLAGS = EntityDataSerializers.BYTE.createAccessor(0);
    private static final byte FLAG_INVISIBLE = 0x20;

    /**
     * Remote entities render invisibility from the metadata flag (not the effect), so a fake
     * metadata packet is sent, plus empty equipment (armor/held items stay visible otherwise).
     * The server overwrites this whenever it broadcasts real metadata/equipment, which is why
     * it is re-sent periodically from reconcileVisibility().
     */
    private void sendFakeInvis(Player viewer, Player target) {
        ServerPlayer handle = ((CraftPlayer) target).getHandle();
        byte flags = (byte) (handle.getEntityData().get(SHARED_FLAGS) | FLAG_INVISIBLE);
        var conn = ((CraftPlayer) viewer).getHandle().connection;
        conn.send(new ClientboundSetEntityDataPacket(target.getEntityId(),
            List.of(SynchedEntityData.DataValue.create(SHARED_FLAGS, flags))));

        List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            slots.add(Pair.of(slot, net.minecraft.world.item.ItemStack.EMPTY));
        }
        conn.send(new ClientboundSetEquipmentPacket(target.getEntityId(), slots));
    }

    private void sendRemoveInvis(Player viewer, Player target) {
        ServerPlayer handle = ((CraftPlayer) target).getHandle();
        byte flags = handle.getEntityData().get(SHARED_FLAGS);
        var conn = ((CraftPlayer) viewer).getHandle().connection;
        conn.send(new ClientboundSetEntityDataPacket(target.getEntityId(),
            List.of(SynchedEntityData.DataValue.create(SHARED_FLAGS, flags))));

        List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> slots = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            slots.add(Pair.of(slot, handle.getItemBySlot(slot)));
        }
        conn.send(new ClientboundSetEquipmentPacket(target.getEntityId(), slots));
    }

    private void cleanupFakeInvis(UUID targetId) {
        hiddenViewers.remove(targetId);
        Set<UUID> invis = fakeInvisViewers.remove(targetId);
        if (invis == null) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            return;
        }
        for (UUID viewerId : invis) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                sendRemoveInvis(viewer, target);
            }
        }
    }

    private void startSession(Player target, Location anchor, MirrorSession.Mode mode) {
        MirrorSession session = new MirrorSession(this, target.getUniqueId(), anchor, mode);
        try {
            session.spawn(target);
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Failed to spawn mirror for " + target.getName(), ex);
            session.remove();
            return;
        }
        sessions.put(target.getUniqueId(), session);
        if (mode == MirrorSession.Mode.FOLLOW) {
            mirroredEntityIds.put(target.getEntityId(), target.getUniqueId());
        }
    }

    private void endSession(UUID targetId) {
        MirrorSession session = sessions.remove(targetId);
        mirroredEntityIds.values().removeIf(id -> id.equals(targetId));
        if (session != null) {
            cleanupFakeInvis(targetId);
            session.remove();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "vpdvr" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                Player target = player;
                if (args.length > 0) {
                    target = Bukkit.getPlayerExact(args[0]);
                    if (target == null) {
                        player.sendMessage("§cPlayer not found: " + args[0]);
                        return true;
                    }
                }
                MirrorSession existing = sessions.get(target.getUniqueId());
                if (existing != null) {
                    endSession(target.getUniqueId());
                    player.sendMessage("§cVR mirror stopped for " + target.getName() + "."
                        + (autoMirror && isAutoMirrorEnabled(target.getUniqueId())
                        ? " (auto-mirror may restart; use /vpdtoggle to disable)" : ""));
                    return true;
                }
                if (VRAPI.instance().getVRPose(target) == null) {
                    player.sendMessage("§c" + target.getName() + " has no Vivecraft VR pose yet.");
                    return true;
                }
                startSession(target, anchorFrom(player), MirrorSession.Mode.ANCHOR);
                player.sendMessage("§aAnchored VR mirror started for " + target.getName() + ".");
                return true;
            }
            case "vpdtest" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only.");
                    return true;
                }
                MirrorSession existing = sessions.get(player.getUniqueId());
                if (existing != null) {
                    endSession(player.getUniqueId());
                    player.sendMessage("§cMirror session removed.");
                    return true;
                }
                startSession(player, anchorFrom(player), MirrorSession.Mode.STATIC);
                player.sendMessage("§aStatic test rig spawned. Run /vpdtest again to remove it.");
                return true;
            }
            case "vpdtoggle" -> {
                if (args.length < 1) {
                    sender.sendMessage("§cUsage: /vpdtoggle <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    sender.sendMessage("§cPlayer not found: " + args[0]);
                    return true;
                }
                boolean enabled = !isAutoMirrorEnabled(target.getUniqueId());
                setAutoMirrorEnabled(target.getUniqueId(), enabled);
                sender.sendMessage(enabled
                    ? "§aAuto mirror enabled for " + target.getName() + "."
                    : "§cAuto mirror disabled for " + target.getName() + ".");
                return true;
            }
            case "vpdadjust" -> {
                if (args.length == 0) {
                    sender.sendMessage("§7/vpdadjust limbs <connected|split>  (vivecraft default: connected)");
                    sender.sendMessage("§7/vpdadjust legstyle <arms|legs>  (SPLIT_ARMS / SPLIT_ARMS_LEGS observers)");
                    sender.sendMessage("§7/vpdadjust itemoffset <x> <y> <z>  (hand-local pixels)");
                    sender.sendMessage("§7/vpdadjust lowermode <quat|aim>  (split mode only)");
                    sender.sendMessage("§7/vpdadjust lowertwist <yaw> <pitch> <roll>  (degrees, split mode only)");
                    sender.sendMessage("§7/vpdadjust itemtwist <yaw> <pitch> <roll>  (degrees, hand-local)");
                    sender.sendMessage("§7current: limbs=" + (limbsConnected ? "connected" : "split")
                        + " legstyle=" + (legStyleArms ? "arms" : "legs")
                        + " itemoffset=" + itemOffset.x + "/" + itemOffset.y + "/" + itemOffset.z
                        + " lowermode=" + (lowerArmAim ? "aim" : "quat")
                        + " itemtwist=" + getConfig().getDouble("item-twist-yaw", 0)
                        + "/" + getConfig().getDouble("item-twist-pitch", 0)
                        + "/" + getConfig().getDouble("item-twist-roll", 0));
                    return true;
                }
                switch (args[0].toLowerCase()) {
                    case "limbs" -> {
                        if (args.length < 2) {
                            sender.sendMessage("§cUsage: /vpdadjust limbs <connected|split>");
                            return true;
                        }
                        limbsConnected = "connected".equalsIgnoreCase(args[1]);
                        getConfig().set("limbs-connected", limbsConnected);
                        saveConfig();
                        sender.sendMessage("§aLimb mode: " + (limbsConnected ? "connected" : "split"));
                    }
                    case "hurttest" -> {
                        if (!(sender instanceof Player player)) {
                            sender.sendMessage("Players only.");
                            return true;
                        }
                        MirrorSession session = sessions.get(player.getUniqueId());
                        if (session == null) {
                            sender.sendMessage("§cNo mirror session for you. Start one first (/vpdtest or VR).");
                            return true;
                        }
                        session.markHurt(40);
                        sender.sendMessage("§aHurt tint applied for 2 seconds.");
                    }
                    case "legstyle" -> {
                        if (args.length < 2) {
                            sender.sendMessage("§cUsage: /vpdadjust legstyle <arms|legs>");
                            return true;
                        }
                        legStyleArms = !"legs".equalsIgnoreCase(args[1]);
                        getConfig().set("leg-style", legStyleArms ? "arms" : "legs");
                        saveConfig();
                        sender.sendMessage("§aLeg style: " + (legStyleArms ? "arms (vanilla swing)" : "legs (foot anchored)"));
                    }
                    case "itemoffset" -> {
                        if (args.length < 4) {
                            sender.sendMessage("§cUsage: /vpdadjust itemoffset <x> <y> <z>");
                            return true;
                        }
                        try {
                            itemOffset.set(Float.parseFloat(args[1]), Float.parseFloat(args[2]), Float.parseFloat(args[3]));
                            getConfig().set("item-offset-x", (double) itemOffset.x);
                            getConfig().set("item-offset-y", (double) itemOffset.y);
                            getConfig().set("item-offset-z", (double) itemOffset.z);
                            saveConfig();
                            sender.sendMessage("§aItem offset set to " + itemOffset.x + "/" + itemOffset.y + "/" + itemOffset.z);
                        } catch (NumberFormatException ex) {
                            sender.sendMessage("§cNumbers required.");
                        }
                    }
                    case "lowermode" -> {
                        if (args.length < 2) {
                            sender.sendMessage("§cUsage: /vpdadjust lowermode <quat|aim>");
                            return true;
                        }
                        lowerArmAim = "aim".equalsIgnoreCase(args[1]);
                        getConfig().set("lower-arm-mode", lowerArmAim ? "aim" : "quat");
                        saveConfig();
                        sender.sendMessage("§aLower arm mode: " + (lowerArmAim ? "aim" : "quat"));
                    }
                    case "lowertwist" -> {
                        if (args.length < 4) {
                            sender.sendMessage("§cUsage: /vpdadjust lowertwist <yaw> <pitch> <roll>");
                            return true;
                        }
                        try {
                            float yaw = Float.parseFloat(args[1]);
                            float pitch = Float.parseFloat(args[2]);
                            float roll = Float.parseFloat(args[3]);
                            setLowerTwist(yaw, pitch, roll);
                            getConfig().set("lower-twist-yaw", yaw);
                            getConfig().set("lower-twist-pitch", pitch);
                            getConfig().set("lower-twist-roll", roll);
                            saveConfig();
                            sender.sendMessage("§aLower twist set to " + yaw + "/" + pitch + "/" + roll);
                        } catch (NumberFormatException ex) {
                            sender.sendMessage("§cNumbers required.");
                        }
                    }
                    case "itemtwist" -> {
                        if (args.length < 4) {
                            sender.sendMessage("§cUsage: /vpdadjust itemtwist <yaw> <pitch> <roll>");
                            return true;
                        }
                        try {
                            float yaw = Float.parseFloat(args[1]);
                            float pitch = Float.parseFloat(args[2]);
                            float roll = Float.parseFloat(args[3]);
                            setItemTwist(yaw, pitch, roll);
                            getConfig().set("item-twist-yaw", yaw);
                            getConfig().set("item-twist-pitch", pitch);
                            getConfig().set("item-twist-roll", roll);
                            saveConfig();
                            sender.sendMessage("§aItem twist set to " + yaw + "/" + pitch + "/" + roll);
                        } catch (NumberFormatException ex) {
                            sender.sendMessage("§cNumbers required.");
                        }
                    }
                    default -> sender.sendMessage("§cUnknown sub command.");
                }
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endSession(event.getPlayer().getUniqueId());
        // forget this player as a viewer
        UUID viewerId = event.getPlayer().getUniqueId();
        moddedViewers.remove(viewerId);
        for (Set<UUID> set : fakeInvisViewers.values()) {
            set.remove(viewerId);
        }
        for (Set<UUID> set : hiddenViewers.values()) {
            set.remove(viewerId);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        MirrorSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.markHurt();
        }
    }

    private static Location anchorFrom(Player player) {
        Location anchor = player.getLocation().clone();
        anchor.setPitch(0f);
        return anchor;
    }

    public boolean detectSlim(Player target) {
        if (!autoSlim) {
            return "slim".equalsIgnoreCase(defaultVariant);
        }
        try {
            for (ProfileProperty property : target.getPlayerProfile().getProperties()) {
                if (!"textures".equals(property.getName())) {
                    continue;
                }
                String json = new String(Base64.getDecoder().decode(property.getValue()), StandardCharsets.UTF_8);
                if (json.contains("metadata") && json.contains("slim")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // fall through to the default variant
        }
        return "slim".equalsIgnoreCase(defaultVariant);
    }

    private void sweepOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(TAG_GLOBAL)) {
                    entity.remove();
                }
            }
        }
    }
}

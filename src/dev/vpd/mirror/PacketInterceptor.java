package dev.vpd.mirror;

import com.mojang.datafixers.util.Pair;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-viewer outgoing packet filter: rewrites entity metadata (invisible flag) and equipment
 * (empty) of mirrored VR players on the fly, so the fake invisibility can never flicker off
 * when the server broadcasts real metadata/equipment.
 *
 * Runs on the netty thread; it must only touch the plugin's concurrent state
 * ({@link VpdMirrorPlugin#shouldFakeInvis(UUID, int)}).
 */
final class PacketInterceptor extends ChannelDuplexHandler {

    static final String NAME = "vpd_mirror";

    private final VpdMirrorPlugin plugin;
    private final UUID viewerId;

    PacketInterceptor(VpdMirrorPlugin plugin, UUID viewerId) {
        this.plugin = plugin;
        this.viewerId = viewerId;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object out = msg;
        try {
            out = transform(msg);
        } catch (Throwable ignored) {
            out = msg; // never break the connection over a cosmetic feature
        }
        super.write(ctx, out, promise);
    }

    @SuppressWarnings("unchecked")
    private Object transform(Object msg) {
        if (msg instanceof ClientboundSetEntityDataPacket data) {
            return transformData(data);
        }
        if (msg instanceof ClientboundSetEquipmentPacket equipment) {
            return transformEquipment(equipment);
        }
        if (msg instanceof ClientboundBundlePacket bundle) {
            boolean changed = false;
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                Packet<? super ClientGamePacketListener> replaced = sub;
                if (sub instanceof ClientboundSetEntityDataPacket data) {
                    replaced = transformData(data);
                } else if (sub instanceof ClientboundSetEquipmentPacket equipment) {
                    replaced = transformEquipment(equipment);
                }
                if (replaced != sub) {
                    changed = true;
                }
                packets.add(replaced);
            }
            return changed ? new ClientboundBundlePacket(packets) : msg;
        }
        return msg;
    }

    private ClientboundSetEntityDataPacket transformData(ClientboundSetEntityDataPacket packet) {
        if (!plugin.shouldFakeInvis(viewerId, packet.id())) {
            return packet;
        }
        List<SynchedEntityData.DataValue<?>> items = packet.packedItems();
        List<SynchedEntityData.DataValue<?>> out = null;
        for (int i = 0; i < items.size(); i++) {
            SynchedEntityData.DataValue<?> dv = items.get(i);
            if (dv.id() == 0 && dv.value() instanceof Byte flags) {
                if (out == null) {
                    out = new ArrayList<>(items);
                }
                out.set(i, new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE,
                    (byte) (flags | 0x20)));
            }
        }
        return out == null ? packet : new ClientboundSetEntityDataPacket(packet.id(), out);
    }

    private ClientboundSetEquipmentPacket transformEquipment(ClientboundSetEquipmentPacket packet) {
        if (!plugin.shouldFakeInvis(viewerId, packet.getEntity())) {
            return packet;
        }
        List<Pair<EquipmentSlot, ItemStack>> slots = new ArrayList<>();
        for (Pair<EquipmentSlot, ItemStack> pair : packet.getSlots()) {
            slots.add(Pair.of(pair.getFirst(), ItemStack.EMPTY));
        }
        return new ClientboundSetEquipmentPacket(packet.getEntity(), slots);
    }
}

package com.example.arslauria.mana.network;

import com.example.arslauria.mana.ClientManaCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class ManaSyncS2CPacket {
    private final UUID entityId;
    private final double visibleAmount;
    private final double maxAmount;

    public ManaSyncS2CPacket(UUID entityId, double visibleAmount, double maxAmount) {
        this.entityId = entityId;
        this.visibleAmount = visibleAmount;
        this.maxAmount = maxAmount;
    }

    public static void encode(ManaSyncS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeUUID(pkt.entityId);
        buf.writeDouble(pkt.visibleAmount);
        buf.writeDouble(pkt.maxAmount);
    }

    public static ManaSyncS2CPacket decode(FriendlyByteBuf buf) {
        return new ManaSyncS2CPacket(buf.readUUID(), buf.readDouble(), buf.readDouble());
    }

    public static void handle(ManaSyncS2CPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            // client side update cache
            ClientManaCache.setVisible(msg.entityId, msg.visibleAmount, msg.maxAmount);
        });
        ctx.setPacketHandled(true);
    }
}

package com.example.arslauria.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import com.example.arslauria.client.ClientBarrierData;
import net.minecraft.client.Minecraft;

import java.util.function.Supplier;

public record BarrierSyncPacket(int entityId, boolean add, int durationTicks) {
    public static void encode(BarrierSyncPacket pkt, FriendlyByteBuf buf) {
        buf.writeInt(pkt.entityId);
        buf.writeBoolean(pkt.add);
        buf.writeInt(pkt.durationTicks);
    }
    public static BarrierSyncPacket decode(FriendlyByteBuf buf) {
        return new BarrierSyncPacket(buf.readInt(), buf.readBoolean(), buf.readInt());
    }

    public static void handle(BarrierSyncPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Этот код выполняется на стороне получателя (клиент)
            try {
                if (Minecraft.getInstance() == null || Minecraft.getInstance().level == null) return;
            } catch (Throwable t) {
                return; // на сервере — пропустить
            }

            if (pkt.add) {
                // durationTicks может быть 0 — тогда держим дефолт 1s (опционально)
                ClientBarrierData.add(pkt.entityId, Math.max(pkt.durationTicks, 0));
                System.out.println("[BarrierSyncPacket] add for entity " + pkt.entityId + " dur=" + pkt.durationTicks);
            } else {
                ClientBarrierData.remove(pkt.entityId);
                System.out.println("[BarrierSyncPacket] remove for entity " + pkt.entityId);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}

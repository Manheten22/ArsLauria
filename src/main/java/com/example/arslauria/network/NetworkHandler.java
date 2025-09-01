package com.example.arslauria.network;

import com.example.arslauria.Lauria;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Network handler: инициализация SimpleChannel и удобные методы отправки.
 *
 * Важно: вызов {@link #init()} должен происходить в FMLCommonSetupEvent (common setup).
 */
public final class NetworkHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String PROTOCOL_VERSION = "1";
    private static SimpleChannel CHANNEL = null;
    private static int packetId = 0;

    private NetworkHandler() { /* no-op */ }

    /**
     * Инициализировать канал и зарегистрировать пакеты.
     * Вызывать в FMLCommonSetupEvent (common setup).
     */
    @SuppressWarnings("removal")

    public static void init() {
        if (CHANNEL != null) {
            LOGGER.debug("[NetworkHandler] already initialized, skipping");
            return;
        }

        LOGGER.info("[NetworkHandler] init() called — registering channel & messages");
                CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Lauria.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        // Регистрация пакетов: BarrierSyncPacket — от сервера к клиенту
        CHANNEL.registerMessage(nextId(),
                BarrierSyncPacket.class,
                BarrierSyncPacket::encode,
                BarrierSyncPacket::decode,
                BarrierSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        LOGGER.info("[NetworkHandler] channel & messages registered (last id={})", packetId - 1);
    }

    private static int nextId() {
        return packetId++;
    }

    /** Проверить, инициализирован ли канал. */
    public static boolean isInitialized() {
        return CHANNEL != null;
    }

    /**
     * Отправить сообщение всем, кто отслеживает сущность + самой сущности, если это игрок.
     * Ничего не делает если канал не инициализирован.
     */
    public static void sendToTracking(LivingEntity entity, Object message) {
        if (CHANNEL == null) {
            LOGGER.warn("[NetworkHandler] sendToTracking called but CHANNEL==null; dropping message");
            return;
        }
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), message);
    }

    /**
     * Отправить сообщение конкретному серверному игроку.
     * Ничего не делает если канал не инициализирован.
     */
    public static void sendToPlayer(ServerPlayer player, Object message) {
        if (CHANNEL == null) {
            LOGGER.warn("[NetworkHandler] sendToPlayer called but CHANNEL==null; dropping message");
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    /**
     * Утилита: отправить сообщение всем игрокам в мире/серверном уровне (вручную).
     * Используется как fallback, если нужно контролировать рассылку вручную.
     */
    public static void sendToAllInServerLevel(net.minecraft.server.level.ServerLevel level, Object message) {
        if (CHANNEL == null) {
            LOGGER.warn("[NetworkHandler] sendToAllInServerLevel called but CHANNEL==null; dropping message");
            return;
        }
        for (ServerPlayer p : level.players()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), message);
        }
    }

    /**
     * Простой handler-шаблон для случаев, когда нужно выполнить действие в контексте потока.
     * В пакетах обычно используется собственный handle, но этот метод может помочь в реализации.
     */
    public static <T> void handleClientwork(Supplier<NetworkEvent.Context> ctxSupplier, java.util.function.Consumer<T> consumer, T msg) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> consumer.accept(msg));
        ctx.setPacketHandled(true);
    }
}

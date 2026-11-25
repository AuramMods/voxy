package me.cortex.voxy.common.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class LodChannel {
    public static final int MAX_OFFER = 64;
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("voxy", "lod"), () -> VERSION,
            NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    public sealed interface ClientMessage permits Scope, Offer, Section {}
    public record Scope(UUID world, ResourceLocation dimension, long scope, int radiusChunks) implements ClientMessage {}
    public record Offer(long scope, long[] keys, long[] revisions) implements ClientMessage {}
    public record Request(long scope, long[] keys) {}
    public record Section(long scope, long key, long revision, byte[] data) implements ClientMessage {}

    private static Consumer<ClientMessage> client = message -> {};
    private static BiConsumer<ServerPlayer, Request> server = (player, request) -> {};

    private LodChannel() {}

    public static void register() {
        registerClient(0, Scope.class, (message, out) -> {
            out.writeUUID(message.world());
            out.writeResourceLocation(message.dimension());
            out.writeLong(message.scope());
            out.writeVarInt(message.radiusChunks());
        }, in -> new Scope(in.readUUID(), in.readResourceLocation(), in.readLong(), in.readVarInt()));
        registerClient(1, Offer.class, (message, out) -> {
            out.writeLong(message.scope());
            out.writeVarInt(message.keys().length);
            for (int i = 0; i < message.keys().length; i++) {
                out.writeLong(message.keys()[i]);
                out.writeVarLong(message.revisions()[i]);
            }
        }, in -> {
            long scope = in.readLong();
            int count = readCount(in);
            var keys = new long[count];
            var revisions = new long[count];
            for (int i = 0; i < count; i++) {
                keys[i] = in.readLong();
                revisions[i] = in.readVarLong();
            }
            return new Offer(scope, keys, revisions);
        });
        CHANNEL.registerMessage(2, Request.class, (message, out) -> {
            out.writeLong(message.scope());
            out.writeVarInt(message.keys().length);
            for (long key : message.keys()) out.writeLong(key);
        }, in -> {
            long scope = in.readLong();
            var keys = new long[readCount(in)];
            for (int i = 0; i < keys.length; i++) keys[i] = in.readLong();
            return new Request(scope, keys);
        }, LodChannel::handleRequest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registerClient(3, Section.class, (message, out) -> {
            out.writeLong(message.scope());
            out.writeLong(message.key());
            out.writeVarLong(message.revision());
            out.writeByteArray(message.data());
        }, in -> new Section(in.readLong(), in.readLong(), in.readVarLong(),
                in.readByteArray(LodSectionCodec.MAX_COMPRESSED_BYTES)));
    }

    private static int readCount(FriendlyByteBuf in) {
        int size = in.readVarInt();
        if (size < 0 || size > MAX_OFFER) throw new IllegalArgumentException("Invalid Voxy section batch");
        return size;
    }

    private static <T extends ClientMessage> void registerClient(int id, Class<T> type,
            BiConsumer<T, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, T> decoder) {
        CHANNEL.registerMessage(id, type, encoder, decoder, LodChannel::handleClientMessage,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void handleRequest(Request message, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        var player = ctx.getSender();
        if (player != null) ctx.enqueueWork(() -> server.accept(player, message));
        ctx.setPacketHandled(true);
    }

    private static void handleClientMessage(ClientMessage message, Supplier<NetworkEvent.Context> supplier) {
        var ctx = supplier.get();
        ctx.enqueueWork(() -> client.accept(message));
        ctx.setPacketHandled(true);
    }

    public static void setClientHandler(Consumer<ClientMessage> handler) {
        client = handler;
    }

    public static void setServerHandler(BiConsumer<ServerPlayer, Request> handler) {
        server = handler;
    }

    public static boolean available(ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.connection);
    }

    public static void send(ServerPlayer player, ClientMessage message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    public static void request(Request message) {
        CHANNEL.sendToServer(message);
    }
}

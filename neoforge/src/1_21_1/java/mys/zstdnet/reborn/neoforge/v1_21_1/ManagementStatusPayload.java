package mys.zstdnet.reborn.neoforge.v1_21_1;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

record ManagementStatusPayload(
        String state,
        int port,
        long wireUpBytes,
        long rawUpBytes,
        long wireDownBytes,
        long rawDownBytes,
        double ratioPercent,
        int connections,
        String dictionary,
        int dictionaryConnections,
        int selectedDictionaryConnections
) implements CustomPacketPayload {
    static final Type<ManagementStatusPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("zstdnet", "management_status"));
    static final StreamCodec<RegistryFriendlyByteBuf, ManagementStatusPayload> STREAM_CODEC =
            StreamCodec.of(ManagementStatusPayload::write, ManagementStatusPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, ManagementStatusPayload payload) {
        buffer.writeUtf(payload.state, 32);
        buffer.writeVarInt(payload.port);
        buffer.writeVarLong(payload.wireUpBytes);
        buffer.writeVarLong(payload.rawUpBytes);
        buffer.writeVarLong(payload.wireDownBytes);
        buffer.writeVarLong(payload.rawDownBytes);
        buffer.writeDouble(payload.ratioPercent);
        buffer.writeVarInt(payload.connections);
        buffer.writeUtf(payload.dictionary, 512);
        buffer.writeVarInt(payload.dictionaryConnections);
        buffer.writeVarInt(payload.selectedDictionaryConnections);
    }

    private static ManagementStatusPayload read(RegistryFriendlyByteBuf buffer) {
        return new ManagementStatusPayload(
                buffer.readUtf(32), buffer.readVarInt(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readDouble(), buffer.readVarInt(),
                buffer.readUtf(512), buffer.readVarInt(), buffer.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

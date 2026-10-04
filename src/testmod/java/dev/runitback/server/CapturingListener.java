package dev.runitback.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Stands in for a real client connection: every packet the server sends is encoded and decoded
 * with the real game protocol (so a packet a client could not read fails here too) and kept.
 */
final class CapturingListener extends ServerGamePacketListenerImpl {
	final List<Packet<?>> received = new ArrayList<>();
	private final ProtocolInfo<ClientGamePacketListener> protocol;

	CapturingListener(ServerPlayer player) {
		super(player.level().getServer(), new Connection(PacketFlow.SERVERBOUND), player,
			CommonListenerCookie.createInitial(player.getGameProfile(), false));
		this.protocol = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(player.level().getServer().registryAccess()));
	}

	@Override
	@SuppressWarnings("unchecked")
	public void send(Packet<?> packet, ChannelFutureListener listener) {
		ByteBuf buf = Unpooled.buffer();
		try {
			protocol.codec().encode(buf, (Packet<? super ClientGamePacketListener>) packet);
			received.add(protocol.codec().decode(buf));
		} finally {
			buf.release();
		}
	}
}

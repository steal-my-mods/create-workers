package com.createworkers.net;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.block.WorkerStationMenu;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Naming a job from the station screen.
 *
 * <p>Which is a label rather than an enchantment, so it costs no anvil and no experience — the same
 * call Create's Train Stations and Frogports make. What it buys is the one thing every diagnostic in
 * this mod keeps running into: <i>which villager is it?</i> A named hat is a named job, and the name
 * comes back with the hat when its wearer dies.
 */
public record StationRenamePacket(int slot, String name) implements CustomPacketPayload {

	public static final Type<StationRenamePacket> TYPE = new Type<>(CreateWorkers.asResource("station_rename"));

	/**
	 * The length an anvil allows, enforced on the way in as well as in the screen's own box. A name is
	 * saved with the hat and sent to every client that opens the station, so its size is not the
	 * sender's to choose.
	 */
	public static final int MAX_LENGTH = 32;

	public static final StreamCodec<ByteBuf, StationRenamePacket> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, StationRenamePacket::slot,
		ByteBufCodecs.stringUtf8(MAX_LENGTH), StationRenamePacket::name,
		StationRenamePacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(StationRenamePacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			Player player = context.player();
			if (player == null || !(player.containerMenu instanceof WorkerStationMenu menu))
				return;

			// The same check as every other roster edit: the station comes out of the sender's open
			// menu rather than out of the packet, and stillValid is what closes that menu when they
			// walk away or the block is broken.
			WorkerStationBlockEntity station = menu.contentHolder;
			if (station == null || !menu.stillValid(player))
				return;

			station.renameJob(packet.slot(), packet.name());
		});
	}
}

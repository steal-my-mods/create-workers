package com.createworkers.net;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.block.WorkerStationMenu;
import com.createworkers.worker.Shift;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.EnumSet;
import java.util.Set;

/**
 * The two things the station screen changes that are not an item: which shifts a job runs on, and
 * where it sits in the rack.
 *
 * <p>One packet for both because they are the same shape — a slot and a small number — and because
 * they need exactly the same check before either is applied. The rack's order is a player's priority
 * lever and its shift toggles hire and fire villagers, so neither may be driven by a client that is
 * not actually standing at the block: the sender must have this station's menu open, which the server
 * opened for it and closes when it walks away.
 */
public record StationRosterPacket(int slot, int value, boolean reorder) implements CustomPacketPayload {

	public static final Type<StationRosterPacket> TYPE = new Type<>(CreateWorkers.asResource("station_roster"));

	public static final StreamCodec<ByteBuf, StationRosterPacket> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, StationRosterPacket::slot,
		ByteBufCodecs.VAR_INT, StationRosterPacket::value,
		ByteBufCodecs.BOOL, StationRosterPacket::reorder,
		StationRosterPacket::new);

	/** Moves the job at {@code slot} to {@code to}. */
	public static StationRosterPacket move(int slot, int to) {
		return new StationRosterPacket(slot, to, true);
	}

	/** Sets which shifts the job at {@code slot} runs on. */
	public static StationRosterPacket shifts(int slot, Set<Shift> shifts) {
		int mask = 0;
		for (Shift shift : shifts)
			mask |= 1 << shift.ordinal();
		return new StationRosterPacket(slot, mask, false);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(StationRosterPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			Player player = context.player();
			if (player == null || !(player.containerMenu instanceof WorkerStationMenu menu))
				return;

			WorkerStationBlockEntity station = menu.contentHolder;
			// stillValid is the menu's own reach check, and it is the one that matters: it is what
			// closes this menu when the player walks away or the block is broken.
			if (station == null || !menu.stillValid(player))
				return;

			if (packet.reorder()) {
				station.moveSlot(packet.slot(), packet.value());
				return;
			}

			EnumSet<Shift> wanted = EnumSet.noneOf(Shift.class);
			for (Shift shift : Shift.VALUES)
				if ((packet.value() & (1 << shift.ordinal())) != 0)
					wanted.add(shift);
			// An empty set is refused rather than applied: a job that runs on no shift could never be
			// filled and could never be seen to be empty. setShifts ignores it, and saying so here
			// keeps the screen from having to.
			if (!wanted.isEmpty())
				station.setShifts(packet.slot(), wanted);
		});
	}

}

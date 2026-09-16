package com.createworkers.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class CWNetwork {

	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1");

		registrar.playToServer(ConfigureHatPacket.TYPE, ConfigureHatPacket.STREAM_CODEC,
			ConfigureHatPacket::handle);

		registrar.playToServer(StationRosterPacket.TYPE, StationRosterPacket.STREAM_CODEC,
			StationRosterPacket::handle);

		registrar.playToServer(StationRenamePacket.TYPE, StationRenamePacket.STREAM_CODEC,
			StationRenamePacket::handle);

		registrar.playToClient(WorkerStatePacket.TYPE, WorkerStatePacket.STREAM_CODEC,
			WorkerStatePacket::handle);
	}
}

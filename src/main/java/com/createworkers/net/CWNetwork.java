package com.createworkers.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class CWNetwork {

	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar("1");

		registrar.playToServer(ConfigureHatPacket.TYPE, ConfigureHatPacket.STREAM_CODEC,
			ConfigureHatPacket::handle);

		registrar.playToClient(WorkerStatePacket.TYPE, WorkerStatePacket.STREAM_CODEC,
			WorkerStatePacket::handle);
	}
}

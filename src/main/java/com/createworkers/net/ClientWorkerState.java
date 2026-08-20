package com.createworkers.net;

import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Applies synced worker state to the client copy of an entity. Kept apart from the payload so
 * nothing here is ever loaded on a dedicated server.
 */
public class ClientWorkerState {

	public static void apply(WorkerStatePacket packet) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null)
			return;
		Entity entity = minecraft.level.getEntity(packet.entityId());
		if (entity == null)
			return;
		WorkerData data = Workers.getOrCreate(entity);
		data.applyClientState(packet.hat(), packet.held());
	}
}

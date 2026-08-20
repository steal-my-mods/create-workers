package com.createworkers.net;

import com.createworkers.CreateWorkers;
import com.createworkers.worker.WorkerData;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Tells tracking clients what a worker is wearing and carrying. Data attachments are not
 * synchronised on their own, and vanilla entities have no spare synched data slots to borrow,
 * so the render state travels in its own packet.
 */
public record WorkerStatePacket(int entityId, ItemStack hat, ItemStack held) implements CustomPacketPayload {

	public static final Type<WorkerStatePacket> TYPE = new Type<>(CreateWorkers.asResource("worker_state"));

	public static final StreamCodec<RegistryFriendlyByteBuf, WorkerStatePacket> STREAM_CODEC =
		StreamCodec.composite(
			ByteBufCodecs.VAR_INT, WorkerStatePacket::entityId,
			ItemStack.OPTIONAL_STREAM_CODEC, WorkerStatePacket::hat,
			ItemStack.OPTIONAL_STREAM_CODEC, WorkerStatePacket::held,
			WorkerStatePacket::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static WorkerStatePacket of(Entity entity, WorkerData data) {
		return new WorkerStatePacket(entity.getId(), data.getHat(), data.getHeld());
	}

	/** Pushes the current look of a worker to everyone who can see it. */
	public static void sync(Entity entity, WorkerData data) {
		if (entity.level()
			.isClientSide())
			return;
		PacketDistributor.sendToPlayersTrackingEntity(entity, of(entity, data));
	}

	public static void handle(WorkerStatePacket packet, IPayloadContext context) {
		context.enqueueWork(() -> ClientWorkerState.apply(packet));
	}
}

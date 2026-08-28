package com.createworkers.net;

import com.createworkers.CreateWorkers;
import com.createworkers.registry.CWComponents;
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
		return new WorkerStatePacket(entity.getId(), withoutProgram(data.getHat()), data.getHeld());
	}

	/**
	 * The hat as a viewer needs it, which is the hat without its programme.
	 *
	 * <p>Nothing on the receiving side ever reads the programme off a worker: the gear layer asks
	 * only whether it is employed, and the cargo layer only what it is holding. Left on, it is the
	 * whole point list — a full hat is a couple of kilobytes of type names and coordinates — and
	 * this packet goes to every client tracking the worker on every item it moves, which is twice a
	 * second apiece. A base full of workers is then a few hundred kilobytes a second of NBT that the
	 * client already has on the item and would not look at here anyway.
	 *
	 * <p>Stripping rather than sending a bare flag keeps {@code isEmployed} meaning the same thing on
	 * both sides, and leaves anything a renderer might legitimately want off the hat — damage, a
	 * name, an enchantment glint — where it can still reach it.
	 */
	private static ItemStack withoutProgram(ItemStack hat) {
		if (hat.isEmpty() || !hat.has(CWComponents.PROGRAM.get()))
			return hat;
		ItemStack stripped = hat.copy();
		stripped.remove(CWComponents.PROGRAM.get());
		return stripped;
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

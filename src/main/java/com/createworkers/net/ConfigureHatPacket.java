package com.createworkers.net;

import com.createworkers.CreateWorkers;
import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWItems;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent whenever the player changes the selection while holding a hard hat. Selection happens
 * client-side (that is where the interaction events and the outlines live), so the resulting
 * program has to be written back to the item on the server.
 */
public record ConfigureHatPacket(WorkerProgram program) implements CustomPacketPayload {

	public static final Type<ConfigureHatPacket> TYPE = new Type<>(CreateWorkers.asResource("configure_hat"));

	public static final StreamCodec<ByteBuf, ConfigureHatPacket> STREAM_CODEC =
		WorkerProgram.STREAM_CODEC.map(ConfigureHatPacket::new, ConfigureHatPacket::program);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public static void handle(ConfigureHatPacket packet, IPayloadContext context) {
		context.enqueueWork(() -> {
			Player player = context.player();
			if (player == null)
				return;
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = player.getItemInHand(hand);
				if (!stack.is(CWItems.HARD_HAT.get()))
					continue;
				HardHatItem.setProgram(stack, packet.program());
				return;
			}
		});
	}
}

package com.createworkers.net;

import com.createworkers.CWConfig;
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

	/**
	 * How much further than the beat itself a programme may sit from the player who sent it. A click
	 * lands on a block within arm's reach, and every other point is within the spread of that one.
	 */
	private static final int SENDER_SLACK = 8;

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

			// The client refuses these as you click, but it is the client, so check again here.
			//
			// In this order, deliberately. The spread check is pairwise, so handing it a list whose
			// length nobody has vouched for is a way to spend the server's tick on arithmetic: a
			// programme that fits inside the codec's own size limit holds tens of thousands of
			// points, and the pairs in it run to hundreds of millions.
			WorkerProgram program = packet.program();
			int maxSpread = CWConfig.MAX_TARGET_SPREAD.get();
			if (program.size() > CWConfig.MAX_TARGETS.get())
				return;
			if (program.exceedsSpread(maxSpread))
				return;
			if (!program.within(player.blockPosition(), maxSpread + SENDER_SLACK))
				return;
			for (InteractionHand hand : InteractionHand.values()) {
				ItemStack stack = player.getItemInHand(hand);
				if (!stack.is(CWItems.HARD_HAT.get()))
					continue;
				HardHatItem.setProgram(stack, program);
				return;
			}
		});
	}
}

package com.createworkers.client;

import com.createworkers.block.WorkerStationBlock;
import com.createworkers.block.WorkerStationBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;


/**
 * Hangs a station's hats on its board.
 *
 * <p>This replaces two attempts at painting the answer on. The first put a hat on the block's top
 * face, which a player could only see by standing over it; the second put one on the board, which
 * was a drawing of a hat rather than the hats that were actually there. Both were a picture of a
 * state, and a picture cannot count — a station with one job and a station with six looked the same.
 *
 * <p>Drawing the real items costs no art, needs no second model, and says how many. It is also what
 * the block is: a rack, with hats on it.
 *
 * <p>Only the first few show. A rack holds up to twelve and a board covered in them is a smear, so
 * it draws what fits across the board and lets the screen carry the rest — the point of this is "is
 * there work here, and roughly how much", not an inventory readout.
 */
public class WorkerStationRenderer implements BlockEntityRenderer<WorkerStationBlockEntity> {

	/** How many hats will fit across the board before they start to overlap. */
	private static final int SHOWN = 4;

	/** Where the board's face is, as a fraction of the block, and how big a hat is drawn. */
	private static final float BOARD_FACE = 10.0F / 16.0F;
	private static final float BOARD_HEIGHT = 13.5F / 16.0F;
	private static final float HAT_SCALE = 0.32F;
	/** Clear of the board, so a flat item does not z-fight with the face it hangs on. */
	private static final float STANDOFF = 0.02F;

	public WorkerStationRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(WorkerStationBlockEntity station, float partialTick, PoseStack poseStack,
		MultiBufferSource bufferSource, int packedLight, int packedOverlay) {

		BlockState state = station.getBlockState();
		if (!state.hasProperty(WorkerStationBlock.FACING))
			return;

		int shown = 0;
		for (WorkerStationBlockEntity.Slot slot : station.slots()) {
			if (slot == null)
				continue;
			hang(slot.hat(), shown++, state.getValue(WorkerStationBlock.FACING), poseStack, bufferSource,
				packedLight, packedOverlay);
			if (shown == SHOWN)
				return;
		}
	}

	private void hang(ItemStack hat, int place, Direction facing, PoseStack poseStack,
		MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
		poseStack.pushPose();

		// Into the middle of the block, turned so the board is the face we are working against, and
		// then out to where that face is. Doing it in that order means the four facings are one
		// rotation rather than four sets of coordinates.
		poseStack.translate(0.5F, BOARD_HEIGHT, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));

		float across = (place + 0.5F) / SHOWN - 0.5F;
		poseStack.translate(across, 0.0F, -(BOARD_FACE - 0.5F) - STANDOFF);
		poseStack.scale(HAT_SCALE, HAT_SCALE, HAT_SCALE);

		net.minecraft.client.Minecraft.getInstance()
			.getItemRenderer()
			.renderStatic(hat, ItemDisplayContext.FIXED, packedLight, OverlayTexture.NO_OVERLAY, poseStack,
				bufferSource, null, 0);
		poseStack.popPose();
	}
}

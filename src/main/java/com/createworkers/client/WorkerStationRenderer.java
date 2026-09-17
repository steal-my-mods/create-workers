package com.createworkers.client;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlock;
import com.createworkers.block.WorkerStationBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;


/**
 * Draws a Station's roster on its front face: one lamp for every place in the rack.
 *
 * <p><b>What a lamp means is the whole point, and it is not "there is a hat here".</b> The board used
 * to hang the actual hats, which was cheap — real items, no art — and answered the wrong question. A
 * hat says a job is <i>programmed</i>. What a player walks over to ask, when a line has stopped, is
 * whether anybody is <i>doing</i> it. So each lamp has three states:
 *
 * <ul>
 * <li><b>lit</b> — the job is fully staffed for every shift it runs,
 * <li><b>dim</b> — the job is programmed and short of somebody,
 * <li><b>dark</b> — the slot is empty.
 * </ul>
 *
 * <p>Which makes any dim lamp on the board mean "this station needs people", read at a glance and
 * without opening anything. Hats could never say that: a full station and a half-staffed one looked
 * identical, and so did one job and six.
 *
 * <p>The lit ones are drawn full-bright, so a working station reads across a dark factory.
 *
 * <p><b>Every measurement here is shared with {@code tools/generate_station_textures.py}</b>, in the
 * model's own units — sixteenths of a block — so the two can be compared without a conversion for a
 * factor of sixteen to hide in. That generator checks them against each other, against the model and
 * against {@code MAX_SLOTS} on every build. It is not ceremony: the previous version of this class
 * shipped hanging every hat a fiftieth of a block <i>inside</i> an opaque board, which is not drawn
 * badly but not drawn at all, with nothing anywhere to say so.
 */
public class WorkerStationRenderer implements BlockEntityRenderer<WorkerStationBlockEntity> {

	/** The three states side by side on one sheet, so twelve lamps are one texture. */
	private static final ResourceLocation LAMPS =
		ResourceLocation.fromNamespaceAndPath(CreateWorkers.ID, "textures/block/worker_station_lamps.png");

	private static final int STATE_DARK = 0;
	private static final int STATE_DIM = 1;
	private static final int STATE_LIT = 2;
	private static final int STATES = 3;

	/** The grid, in sixteenths of a block on the front face. Shared with the texture generator. */
	private static final int LAMP_COLUMNS = 4;
	private static final int LAMP_ROWS = 3;
	private static final float LAMP_PITCH_X = 2.45F;
	private static final float LAMP_PITCH_Y = 3.0F;
	private static final float LAMP_CENTRE_X = 8.0F;
	private static final float LAMP_CENTRE_Y = 8.0F;
	private static final float LAMP_SIZE = 2.6F;

	/** Clear of the face, so a flat quad does not z-fight with the block it is drawn on. */
	private static final float STANDOFF = 0.002F;

	private static final float PIXEL = 1.0F / 16.0F;

	public WorkerStationRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(WorkerStationBlockEntity station, float partialTick, PoseStack poseStack,
		MultiBufferSource bufferSource, int packedLight, int packedOverlay) {

		BlockState state = station.getBlockState();
		if (!state.hasProperty(WorkerStationBlock.FACING))
			return;

		Direction facing = state.getValue(WorkerStationBlock.FACING);
		Level level = station.getLevel();
		if (level == null)
			return;

		// A Station is a full cube and is meant to be built into a wall, so its front is often against
		// something. Lamps drawn there would hang inside the neighbour rather than being hidden by it,
		// because a block entity renderer is not culled by anything the way a block face is.
		BlockPos front = station.getBlockPos()
			.relative(facing);
		if (level.getBlockState(front)
			.isSolidRender(level, front))
			return;

		// The light handed to a block entity renderer is the light at the block's own position, which
		// is the light *inside* the Station. A lamp sits on its outside and is lit by the air in front
		// of it, so using the first draws a whole board in shadow in a room that plainly is not.
		int ambient = LevelRenderer.getLightColor(level, front);

		VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutout(LAMPS));
		for (int place = 0; place < LAMP_COLUMNS * LAMP_ROWS; place++) {
			WorkerStationBlockEntity.Slot slot = station.jobAt(place);
			int lamp = lampFor(slot);
			draw(consumer, poseStack, facing, place, lamp,
				lamp == STATE_LIT ? LightTexture.FULL_BRIGHT : ambient, packedOverlay);
		}
	}

	/** Lit when the job wants nobody else, dim when it is short, dark when there is no job. */
	private static int lampFor(WorkerStationBlockEntity.Slot slot) {
		if (slot == null)
			return STATE_DARK;
		return slot.staffed() >= slot.shifts()
			.size() ? STATE_LIT : STATE_DIM;
	}

	private void draw(VertexConsumer consumer, PoseStack poseStack, Direction facing, int place,
		int lamp, int light, int overlay) {

		float across = LAMP_CENTRE_X + (place % LAMP_COLUMNS - (LAMP_COLUMNS - 1) / 2.0F) * LAMP_PITCH_X;
		float up = LAMP_CENTRE_Y - (place / LAMP_COLUMNS - (LAMP_ROWS - 1) / 2.0F) * LAMP_PITCH_Y;

		poseStack.pushPose();

		// Into the middle of the block, turned so local +Z runs the way the Station faces and local +X
		// is the viewer's right, then out to the front face. One rotation rather than four sets of
		// coordinates — and the face is half a block *along* the facing from the middle, which is the
		// sign the bench version got backwards and buried every hat inside its own board.
		poseStack.translate(0.5F, 0.5F, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
		poseStack.translate((across - 8.0F) * PIXEL, (up - 8.0F) * PIXEL, 0.5F + STANDOFF);

		float half = LAMP_SIZE * PIXEL / 2.0F;
		float u1 = (float) lamp / STATES;
		float u2 = (float) (lamp + 1) / STATES;

		// Facing the viewer means facing local -Z after the rotation, so the quad is wound
		// anticlockwise seen from in front, with its normal back along the way the Station faces.
		PoseStack.Pose pose = poseStack.last();
		vertex(consumer, pose, -half, -half, u1, 1.0F, light, overlay);
		vertex(consumer, pose, half, -half, u2, 1.0F, light, overlay);
		vertex(consumer, pose, half, half, u2, 0.0F, light, overlay);
		vertex(consumer, pose, -half, half, u1, 0.0F, light, overlay);

		poseStack.popPose();
	}

	private static void vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y,
		float u, float v, int light, int overlay) {
		consumer.addVertex(pose, x, y, 0.0F)
			.setColor(0xFFFFFFFF)
			.setUv(u, v)
			.setOverlay(overlay)
			.setLight(light)
			.setNormal(pose, 0.0F, 0.0F, -1.0F);
	}
}

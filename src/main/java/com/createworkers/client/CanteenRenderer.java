package com.createworkers.client;

import java.util.List;

import com.createworkers.CreateWorkers;
import com.createworkers.block.CanteenBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Draws what is in a Canteen: a heap on the top face, and a level on each flank.
 *
 * <p><b>The stock is a thing the block knows, so it is drawn from what the block knows.</b> The food
 * used to be painted into the texture — three loaves in fixed places — which said "this is a canteen"
 * and nothing else: a full trough and a nearly empty one were the same picture. That is the mistake
 * the Station's board made when it drew a hat instead of counting one, and the fix is the same.
 *
 * <p><b>A slot owns a cell, and that mapping is the design.</b> Nine slots, a three-by-three grid,
 * one cell each — so the top is the block's own inventory at one to one rather than an abstract
 * level. Three things then make it read as food rather than as a chart:
 *
 * <ul>
 * <li><b>A silhouette per food.</b> Bread lies down, a carrot stands up under a green tuft, a potato
 * is a round lump, a beetroot is a bulb with a tail. Four lumps of the same shape in four colours is
 * a palette, not a larder.
 * <li><b>Cells claim the middle first.</b> Slot order runs centre, sides, corners, so a half-full
 * Canteen heaps in the middle instead of piling into one corner with bare trough beneath it.
 * <li><b>Three spots per slot, filled as the stack fills.</b> One piece per slot could never cover a
 * ten-texel trough however it was arranged — nine pieces is a scattering — so eight slots never
 * looked nearly full. It also means a rack of part-stacks reads as the thin thing it is, where one
 * piece per slot made nine dribbles look like nine full stacks.
 * </ul>
 *
 * <p>Pieces are a texel wider than the grid's pitch, so neighbours overlap, and each is nudged off
 * its cell by a hash of its own position. Food tipped into a trough does not land on a grid.
 *
 * <p><b>Every measurement here is shared with {@code tools/generate_block_textures.py}</b>, in
 * texels, and {@code check_canteen_grid()} holds the two together — including that every piece lands
 * inside the cavity the texture actually draws. The Station shipped its lamps a fiftieth of a block
 * <em>inside</em> an opaque board for want of exactly that check.
 */
public class CanteenRenderer implements BlockEntityRenderer<CanteenBlockEntity> {

	/** Shapes, swatches and the empty gauge, in three bands of four-texel cells. */
	private static final ResourceLocation FOOD =
		ResourceLocation.fromNamespaceAndPath(CreateWorkers.ID, "textures/block/canteen_food.png");

	/** The cavity the texture draws, in texels: the shared panel, inset by trim and its shadow. */
	private static final int CAVITY_X1 = 3;
	private static final int CAVITY_X2 = 12;

	private static final int CAVITY_CELLS = 3;
	private static final int CAVITY_PITCH = 2;
	private static final int CAVITY_PIECE = 4;
	/**
	 * How many places a slot puts food in, filled as its stack fills.
	 *
	 * <p><b>Two, and the number is a consequence of the trough's size rather than a taste.</b> A
	 * piece covers about seven texels and the trough is a hundred, so three spots a slot puts four
	 * slots at 84% coverage — a Canteen barely a third stocked looks brim full, and everything above
	 * it looks the same as everything else. Two gives 56% at four slots, 112% at eight and 126% at
	 * nine, which is the curve that was wanted: half looks half, eight looks nearly full, nine looks
	 * full.
	 *
	 * <p>It was three while this was drawn against a twelve-texel cavity. The trough had to shrink to
	 * the ten the shared panel allows — {@code check_trim} needs ring 2 to stay warm timber — and the
	 * piece count did not follow it down, which is the whole of what went wrong.
	 */
	private static final int CAVITY_SPOTS = 2;
	/**
	 * The furthest a spot offset plus its nudge can push a piece off its own cell.
	 *
	 * <p>Declared rather than inferred because it is what makes the heap fit. The first layout
	 * reached thirteen texels into a ten-texel trough and only stayed inside it by clamping, which
	 * does not spill but does quietly pile every over-reaching piece against the same edge.
	 */
	private static final int CAVITY_REACH = 2;

	/** The gauge: two texels wide on the middle of the face, nine tall for nine slots. */
	private static final int GAUGE_X1 = 7;
	private static final int GAUGE_Y1 = 4;
	private static final int GAUGE_Y2 = 12;
	private static final int GAUGE_WIDTH = 2;

	/**
	 * Which cell each slot claims. Centre, then the sides, then the corners.
	 *
	 * <p>Reading order put a half-full Canteen's food in one corner with bare trough under it, which
	 * looks like food shoved aside rather than a heap. The slot-to-cell mapping is still one to one;
	 * it is only permuted.
	 */
	private static final int[][] CELLS = {
		{ 1, 1 }, { 1, 0 }, { 0, 1 }, { 2, 1 }, { 1, 2 }, { 0, 0 }, { 2, 0 }, { 0, 2 }, { 2, 2 }
	};

	/** Where a slot's three pieces sit within its cell, before the nudge. */
	private static final int[][] SPOTS = { { 0, 0 }, { 1, 1 } };

	/**
	 * How far the model stands the trough plate off the top face, in blocks.
	 *
	 * <p>The trough is a plate in {@code models/block/canteen.json} rather than part of a sheet,
	 * because a face with a trough baked into it can never connect to a neighbour — which is the
	 * whole reason both blocks now wear Create's casing. Restated here because the heap has to be
	 * drawn <em>above</em> it, and check_canteen_grid() reads the model's own element back to make
	 * sure the two still agree. In the model it is in sixteenths, so 0.001 of a block is 16.016.
	 */
	private static final float PLATE = 0.001F;

	/**
	 * Clear of the face, so a flat quad does not z-fight with the block it is drawn on.
	 *
	 * <p>Larger than {@link #PLATE}: the heap sits on the trough, not in it, and two quads at one
	 * depth are not layered but undefined — which this block has already shipped twice.
	 */
	private static final float STANDOFF = 0.002F;

	/**
	 * How much higher each piece of the heap sits than the one before it.
	 *
	 * <p><b>Coplanar quads do not overlap, they fight.</b> Standing every piece off the face by the
	 * same {@code STANDOFF} put twenty-seven of them at one depth, and the depth buffer then picked
	 * between them per fragment and per camera angle — which does not look like a heap with pieces on
	 * top of each other, it looks like the block is tearing itself apart. The Station never met this
	 * because its lamps do not overlap.
	 *
	 * <p>Small enough that the stack of them is under half a texel, so the heap keeps its thickness
	 * without any piece visibly floating; large enough to be worth more than the depth buffer's
	 * precision at the range a block is looked at.
	 *
	 * <p><b>Both drawing methods take a layer, and neither has a default.</b> The gauge was missed
	 * when the heap was fixed — its rows sit on its backing and were left sharing a depth with it, so
	 * the level came out as streaks bleeding through black. Anything that covers anything else here
	 * has to say where it sits.
	 */
	private static final float LAYER = 0.001F;
	private static final float PIXEL = 1.0F / 16.0F;

	public CanteenRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(CanteenBlockEntity canteen, float partialTick, PoseStack poseStack,
		MultiBufferSource bufferSource, int packedLight, int packedOverlay) {

		Level level = canteen.getLevel();
		if (level == null)
			return;

		List<CanteenBlockEntity.Serving> servings = canteen.servings();
		VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutout(FOOD));

		drawHeap(canteen, level, servings, consumer, poseStack, packedOverlay);
		for (Direction facing : Direction.Plane.HORIZONTAL)
			drawGauge(canteen, level, servings, consumer, poseStack, facing, packedOverlay);
	}

	/** The trough seen from above: a slot's worth of food in every slot's cell. */
	private void drawHeap(CanteenBlockEntity canteen, Level level,
		List<CanteenBlockEntity.Serving> servings, VertexConsumer consumer, PoseStack poseStack,
		int overlay) {

		// A Canteen is a full cube and can have something set on top of it. A block entity renderer
		// is not culled by anything the way a block face is, so a heap drawn under a solid neighbour
		// hangs inside it -- the same reason the Station checks the block in front of its board.
		BlockPos above = canteen.getBlockPos()
			.above();
		if (level.getBlockState(above)
			.isSolidRender(level, above))
			return;

		int light = LevelRenderer.getLightColor(level, above);
		int layer = 0;
		for (int slot = 0; slot < CELLS.length && slot < servings.size(); slot++) {
			CanteenBlockEntity.Serving serving = servings.get(slot);
			if (serving.isEmpty())
				continue;

			int pieces = Math.max(1, Math.min(CAVITY_SPOTS, (int) Math.ceil(serving.fraction() * CAVITY_SPOTS)));
			for (int piece = 0; piece < pieces; piece++) {
				int nudge = hash(slot * 31 + piece);
				int x = CAVITY_X1 + CELLS[slot][0] * CAVITY_PITCH + SPOTS[piece][0] + (nudge & 1);
				int y = CAVITY_X1 + CELLS[slot][1] * CAVITY_PITCH + SPOTS[piece][1] + ((nudge >> 1) & 1);
				float u1 = serving.food() * CAVITY_PIECE / 16.0F;
				flat(consumer, poseStack, x, y, CAVITY_PIECE, layer++,
					u1, 0.0F, u1 + CAVITY_PIECE / 16.0F, CAVITY_PIECE / 16.0F, light, overlay);
			}
		}
	}

	/** The same answer on a flank, one row per slot, filling from the floor of the panel. */
	private void drawGauge(CanteenBlockEntity canteen, Level level,
		List<CanteenBlockEntity.Serving> servings, VertexConsumer consumer, PoseStack poseStack,
		Direction facing, int overlay) {

		BlockPos beside = canteen.getBlockPos()
			.relative(facing);
		if (level.getBlockState(beside)
			.isSolidRender(level, beside))
			return;

		int light = LevelRenderer.getLightColor(level, beside);
		int rows = GAUGE_Y2 - GAUGE_Y1 + 1;

		// The backing first, a texel proud of the bar on every side. It is the bezel, and without
		// it the gauge is invisible: a bread swatch is within a few shades of the boards behind
		// it, so a Canteen full of bread showed no bar at all. An empty row is simply this
		// showing through, which is also why only filled rows are drawn.
		upright(consumer, poseStack, facing, GAUGE_X1 - 1, GAUGE_Y1 - 1, GAUGE_WIDTH + 2,
			rows + 1, 0, texel(1), texel(2 * CAVITY_PIECE + 1), light, overlay);

		for (int slot = 0; slot < rows; slot++) {
			CanteenBlockEntity.Serving serving = slot < servings.size() ? servings.get(slot)
				: CanteenBlockEntity.Serving.NOTHING;
			if (serving.isEmpty())
				continue;
			// Slot zero is the floor of the gauge, so filling the rack fills the bar upwards.
			upright(consumer, poseStack, facing, GAUGE_X1, GAUGE_Y2 - slot, GAUGE_WIDTH, 1, 1,
				texel(serving.food() * CAVITY_PIECE + 1), texel(CAVITY_PIECE + 1), light, overlay);
		}
	}

	/**
	 * A quad lying on the top face, {@code x}/{@code y} its north-west corner in texels.
	 *
	 * <p>Two things here are easy to get wrong and both were, invisibly. The rotation maps local
	 * {@code +Y} onto world {@code +Z}, so the texture's own y runs north to south and the offset is
	 * {@code +y} — a negative one puts the whole heap a block to the north, inside whatever happens to
	 * be standing there. And the face has to wind so its normal comes out local {@code -Z}, which is
	 * world up: {@code entityCutout} culls, unlike {@code entityCutoutNoCull}, so the obvious winding
	 * points the quad at the floor and it is simply never drawn.
	 */
	private void flat(VertexConsumer consumer, PoseStack poseStack, int x, int y, int size,
		int layer, float u1, float v1, float u2, float v2, int light, int overlay) {

		poseStack.pushPose();
		poseStack.translate(0.0F, 1.0F + PLATE + STANDOFF + layer * LAYER, 0.0F);
		poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
		poseStack.translate(x * PIXEL, y * PIXEL, 0.0F);

		PoseStack.Pose pose = poseStack.last();
		float span = size * PIXEL;
		vertex(consumer, pose, 0.0F, 0.0F, u1, v1, light, overlay);
		vertex(consumer, pose, 0.0F, span, u1, v2, light, overlay);
		vertex(consumer, pose, span, span, u2, v2, light, overlay);
		vertex(consumer, pose, span, 0.0F, u2, v1, light, overlay);
		poseStack.popPose();
	}

	/**
	 * A quad standing on one of the four flanks, {@code x}/{@code y} its top-left corner in texels.
	 *
	 * <p>{@code layer} is which sheet of the gauge this is — the backing is nought and the rows sit
	 * on top of it. It is not optional for the same reason it is not optional on {@link #flat}: the
	 * rows cover the backing, and two quads covering each other at one depth are not layered, they
	 * are undefined. The gauge shipped exactly that way and the level came out as streaks of colour
	 * bleeding through black.
	 */
	private void upright(VertexConsumer consumer, PoseStack poseStack, Direction facing, int x,
		int y, int width, int height, int layer, float u, float v, int light, int overlay) {

		poseStack.pushPose();
		poseStack.translate(0.5F, 0.5F, 0.5F);
		poseStack.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
		poseStack.translate((x - 8.0F) * PIXEL, (8.0F - y - height) * PIXEL,
			0.5F + STANDOFF + layer * LAYER);

		PoseStack.Pose pose = poseStack.last();
		float w = width * PIXEL;
		float h = height * PIXEL;
		// One texel, sampled at its centre and stretched over the quad. The gauge's rows and its
		// backing are flat colour, so there is nothing here to map: an area sample of a four-texel
		// cell squashed into a one-texel row is a blend of whatever the mipmap picks, which is how a
		// swatch came out looking like torn slivers of the wrong food.
		vertex(consumer, pose, 0.0F, 0.0F, u, v, light, overlay);
		vertex(consumer, pose, w, 0.0F, u, v, light, overlay);
		vertex(consumer, pose, w, h, u, v, light, overlay);
		vertex(consumer, pose, 0.0F, h, u, v, light, overlay);
		poseStack.popPose();
	}

	/** The middle of one texel of the sheet, which is how a flat colour is sampled exactly. */
	private static float texel(int at) {
		return (at + 0.5F) / 16.0F;
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

	/** A stable nudge. Stable is the point: a heap that reshuffles every frame shimmers. */
	private static int hash(int seed) {
		int value = seed * 1103515245 + 12345;
		return (value >> 16) & 0xFF;
	}
}

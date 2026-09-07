package com.createworkers.client.model;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * The hard hat and hi-vis vest worn by workers, fitted to whichever model is going to wear them.
 *
 * <p>Boxes are declared at whole-number sizes and grown with {@link CubeDeformation} rather than
 * being written out at their final fractional size. The deformation inflates geometry without
 * touching the UVs, which keeps every face landing on exact texels — and it is what makes fitting
 * possible at all, because a fitted box is nothing more than a declared box plus a measurement.
 *
 * <p>The gear is deliberately not built per species and handed out by entity type. A worker is any
 * villager or enderman, and a mod that re-skins villagers does it by registering its own entity
 * type with its own model — Villagers Reborn draws them as player-proportioned humanoids, and its
 * own config decides which of two shapes any given villager renders as. So {@link #fitTo} measures
 * the head and torso of the model actually registered for a renderer and builds gear to match,
 * which requires knowing nothing about the mod that supplied it.
 *
 * <p>Three things come out of that measurement, and each answers something the gear has to get
 * right:
 *
 * <ul>
 * <li><b>Where the top of the head is.</b> A villager's skull is 10 units tall against a humanoid's
 * 8, and the whole hat hangs off that figure.
 * <li><b>How wide and deep the torso is.</b> A villager's is 6 deep against a humanoid's 4. The
 * depth is the only measurement the texture has an opinion about: a box's UV footprint is a
 * function of its size, so the vest is declared at the depth of whichever of the sheet's two vest
 * regions is nearer and deformed the rest of the way, which keeps both vanilla shapes on their own
 * exact texels.
 * <li><b>Whether anything is already drawn there.</b> A villager wears a {@code jacket} over its
 * body, so a vest that does not clear it z-fights with it; the same goes for a hat over a head that
 * has hair on it.
 * </ul>
 *
 * <p>That last one can only be detected, never measured, and the reason is worth knowing before
 * anyone tries to improve on it: an overlay is the same shape again, drawn a shade larger through a
 * {@link CubeDeformation}, and a deformation is baked into a cube's vertices rather than into the
 * extents {@link ModelPart.Cube} reports. Nothing public hands it back. So an overlay is found by
 * its <em>footprint</em> — an x and z span that has already been drawn once, whether by a second box
 * in the part or by an overlay part hung on it — and then assumed to be 0.5 thick, which is what
 * vanilla's own {@link net.minecraft.client.model.HumanoidModel#HAT_OVERLAY_SCALE outer layer}
 * measures, and what a villager's jacket and a player's hair both are. Clearing that by a further
 * 0.5 is exactly the 1.0 the villager vest was hand-written with, and a torso with nothing over it
 * comes out at the enderman's hand-written 0.5.
 *
 * <p>The footprint is the whole test on purpose, because a garment is not obliged to be the same
 * <em>height</em> as what it covers: a villager's jacket is a 20-unit robe over a 12-unit torso, so
 * asking whether the box repeats would miss it and dress every villager in a vest half a unit too
 * small.
 *
 * <p>The hat asks for much less, because its crown is sunk into the head by design and only has to
 * avoid sitting <em>exactly</em> on an overlay: a quarter of a unit, on the crown and on nothing
 * else. A bare skull — an enderman's, whose own overlay is inset rather than proud — gets no growth
 * at all.
 *
 * <p>One overlay this cannot see is the one vanilla's own {@link HumanoidModel} uses: a {@code hat}
 * part hung off the <em>root</em> as a sibling of {@code head} rather than as a child of it. It is
 * out of reach on purpose rather than by oversight — an enderman's sibling {@code hat} is inset by
 * 0.5 and a player-shaped one is proud by 0.5, both with the same nominal box, and a deformation
 * cannot be read back, so treating the sibling as an overlay would grow a hat that needs no growth
 * as readily as one that does. A model that puts a <em>proud</em> head overlay there may therefore
 * z-fight with the crown at the hairline; one that bakes its hair into the head part, as the mods
 * this was written for do, is measured with the hair on and cleared properly.
 *
 * <p>What all of this reads is nominal geometry, so a model that carries its bulk in a deformation
 * rather than in its boxes is fitted to the boxes. A child part is also taken at its parent's word
 * about where it is; an overlay part is posed at zero by convention, and the cost of a model that
 * breaks that convention is gear a quarter of a unit roomier than it needed to be.
 */
public class WorkerGearModels {

	public static final String HAT = "hat";
	public static final String VEST = "vest";

	public static final int TEXTURE_WIDTH = 128;
	public static final int TEXTURE_HEIGHT = 64;

	/** The parts a torso and an arm are looked up under on a model that is not a {@link HumanoidModel}. */
	private static final String BODY = "body";
	private static final String RIGHT_ARM = "right_arm";

	/** Half the width of the head and torso the gear is drawn for: a vanilla 8-wide humanoid box. */
	private static final float NOMINAL_HALF_WIDTH = 4.0F;

	/** How far clear of the torso the vest sits — the figure vanilla uses for outer armour. */
	private static final float VEST_CLEARANCE = 0.5F;

	/** How thick an overlay is taken to be, once one has been found. Vanilla's own outer layer. */
	private static final float OVERLAY_SCALE = 0.5F;

	/** All the hat needs in order not to sit exactly on an overlay it cannot measure. */
	private static final float OVERLAY_MARGIN = 0.25F;

	/** The vest boxes on the sheet, each laid out for one torso depth. */
	private record VestRegion(int depth, int u, int v) {}

	private static final VestRegion[] VEST_REGIONS = {
		new VestRegion(6, 0, 28),
		new VestRegion(4, 32, 28),
	};

	/**
	 * Builds gear for a model to wear, measured off that model's own head and torso.
	 *
	 * @return the baked root of a gear model holding {@link #HAT} and {@link #VEST}, or null if this
	 *		   model has no head and torso to fit them to — which is the answer for most of the
	 *		   renderers in the game, and the reason attaching the layers broadly costs nothing
	 */
	@Nullable
	public static ModelPart fitTo(EntityModel<?> model) {
		ModelPart head = headOf(model);
		ModelPart body = bodyOf(model);
		if (head == null || body == null)
			return null;

		Surface skull = measure(head);
		Surface torso = measure(body);
		if (!skull.measured || !torso.measured)
			return null;

		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		root.addOrReplaceChild(HAT, hatCubes(skull.top, hatClearance(skull)), PartPose.ZERO);
		addVest(root, torso);
		return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT)
			.bakeRoot();
	}

	/** @return the part a hat goes on, or null if this model does not admit to having a head. */
	@Nullable
	public static ModelPart headOf(EntityModel<?> model) {
		if (model instanceof HeadedModel headed)
			return headed.getHead();
		return null;
	}

	/** @return the part a vest and a cargo item go on, or null if this model has no torso. */
	@Nullable
	public static ModelPart bodyOf(EntityModel<?> model) {
		if (model instanceof HumanoidModel<?> humanoid)
			return humanoid.body;
		if (model instanceof HierarchicalModel<?> hierarchical) {
			ModelPart root = hierarchical.root();
			if (root.hasChild(BODY))
				return root.getChild(BODY);
		}
		return null;
	}

	/** @return the part a main hand hangs off, or null if this model has no arm of its own. */
	@Nullable
	public static ModelPart armOf(EntityModel<?> model) {
		if (model instanceof HumanoidModel<?> humanoid)
			return humanoid.rightArm;
		if (model instanceof HierarchicalModel<?> hierarchical) {
			ModelPart root = hierarchical.root();
			if (root.hasChild(RIGHT_ARM))
				return root.getChild(RIGHT_ARM);
		}
		return null;
	}

	/**
	 * The model as something with hands to put cargo in, or null if its cargo belongs on its chest.
	 *
	 * <p>Two things have to be true. The model must be an {@link ArmedModel}, which is what supplies
	 * a hand to translate to — a vanilla villager is not one, because its arms are a single merged
	 * part in a fixed pose with no hands in it, which is why cargo sits on the chest there and why a
	 * mod that draws villagers as humanoids gets hands for free without this having heard of it.
	 *
	 * <p>And the hand has to be somewhere a hand plausibly is. An enderman's model is a
	 * {@link HumanoidModel} too, but its arms are thirty units long against a twelve-unit body, so
	 * its hands hang around its ankles and an item held in one would look dropped rather than
	 * carried. Rather than naming the species, this measures: the hand may fall no more than half a
	 * torso below the bottom of the torso. A humanoid's lands exactly at it; an enderman's is a
	 * torso and a half further down.
	 *
	 * @return the model, typed as the {@link ArmedModel} the caller needs in order to find the hand
	 */
	@Nullable
	public static ArmedModel handsOf(EntityModel<?> model) {
		if (!(model instanceof ArmedModel armed))
			return null;

		ModelPart body = bodyOf(model);
		ModelPart arm = armOf(model);
		if (body == null || arm == null)
			return null;

		Surface torso = measure(body);
		Surface sleeve = measure(arm);
		if (!torso.measured || !sleeve.measured)
			return null;

		float torsoBottom = body.y + torso.bottom;
		float torsoHeight = torso.bottom - torso.top;
		float hand = arm.y + sleeve.bottom;
		return hand <= torsoBottom + torsoHeight / 2.0F ? armed : null;
	}

	/**
	 * A hard hat, which is mostly a matter of getting the brim right: a shallow rim the whole way
	 * round with a peak jutting out over the face. A wide, even brim on every side is what reads as
	 * a straw hat instead.
	 *
	 * <p>Shared with the worn-armour model, so a player's hat is the same object the workers wear.
	 *
	 * @param headTopY	 the y of the top of the skull in head-local space, where y grows downwards
	 * @param clearance	 grown onto the crown, and onto nothing else. The crown is the only box sunk
	 *					 into the head, so it is the only one that has anything to clear — and
	 *					 growing the rest would push the peak into the rim, which share a plane at
	 *					 z = -5: two overlapping coplanar faces of one render type stipple against
	 *					 each other, which is a worse artefact than the one being fixed
	 */
	public static CubeListBuilder hatCubes(float headTopY, float clearance) {
		return CubeListBuilder.create()
			// Crown, sunk into the skull so no seam shows at the hairline.
			.texOffs(0, 0)
			.addBox(-4.0F, headTopY - 3.0F, -4.0F, 8.0F, 5.0F, 8.0F, new CubeDeformation(0.5F + clearance))
			// Narrower cap above it, so the silhouette domes instead of going straight up.
			.texOffs(34, 0)
			.addBox(-3.0F, headTopY - 4.5F, -3.0F, 6.0F, 2.0F, 6.0F, new CubeDeformation(0.25F))
			// The reinforcing comb along the top, the detail that makes it read as a hard hat.
			.texOffs(60, 0)
			.addBox(-1.0F, headTopY - 5.5F, -3.0F, 2.0F, 1.0F, 6.0F)
			// Shallow rim, only one unit proud of the head all the way round.
			.texOffs(0, 14)
			.addBox(-5.0F, headTopY + 1.0F, -5.0F, 10.0F, 1.0F, 10.0F)
			// Peak over the face (-Z is forward).
			.texOffs(42, 14)
			.addBox(-3.0F, headTopY + 1.0F, -8.0F, 6.0F, 1.0F, 3.0F);
	}

	/** How much the hat grows to clear hair or headwear. Zero on a bare skull, like an enderman's. */
	private static float hatClearance(Surface skull) {
		return skull.overlaid ? OVERLAY_MARGIN : 0.0F;
	}

	private static void addVest(PartDefinition root, Surface torso) {
		VestRegion region = nearestRegion(torso.ownHalfDepth * 2.0F);
		float depth = region.depth();
		float clearance = VEST_CLEARANCE + (torso.overlaid ? OVERLAY_SCALE : 0.0F);
		float growX = torso.ownHalfWidth + clearance - NOMINAL_HALF_WIDTH;
		float growZ = torso.ownHalfDepth + clearance - depth / 2.0F;

		float grownX = growWithoutInverting(growX, 8.0F);
		float grownZ = growWithoutInverting(growZ, depth);

		root.addOrReplaceChild(VEST, CubeListBuilder.create()
			.texOffs(region.u(), region.v())
			.addBox(-4.0F, 0.5F, -depth / 2.0F, 8.0F, 9.0F, depth,
				new CubeDeformation(grownX, Math.min(grownX, grownZ), grownZ)),
			PartPose.ZERO);
	}

	/** @return the region whose UVs were laid out for the torso nearest this depth. */
	private static VestRegion nearestRegion(float torsoDepth) {
		VestRegion nearest = VEST_REGIONS[0];
		for (VestRegion region : VEST_REGIONS)
			if (Math.abs(region.depth() - torsoDepth) < Math.abs(nearest.depth() - torsoDepth))
				nearest = region;
		return nearest;
	}

	/**
	 * A deformation shrinks a box as happily as it grows one, which is the right answer for a torso
	 * slimmer than the vest was drawn for — but only down to a point. This leaves a unit of box on
	 * the axis rather than letting an extreme model turn it inside out.
	 *
	 * @param boxSize the box's declared size on the axis being grown
	 */
	private static float growWithoutInverting(float grow, float boxSize) {
		return Math.max(grow, (1.0F - boxSize) / 2.0F);
	}

	/**
	 * Measures a part by walking its boxes. {@link ModelPart} keeps its cube list to itself, so
	 * {@code visit} is the way in; the pose it hands back is ignored, because a {@code Cube} carries
	 * its own corners, and for the part's own boxes those are already in the space the gear is drawn
	 * in. A direct child's are in the child's space, which is the same space whenever the child is
	 * posed at zero — as an overlay part is.
	 *
	 * <p>Anything deeper than a direct child is a limb hung off the part rather than a layer drawn
	 * over it — a villager's {@code hat_rim} is a 16-unit plate under its head — so the walk stops
	 * counting there.
	 */
	private static Surface measure(ModelPart part) {
		Surface surface = new Surface();
		part.visit(new PoseStack(), (pose, path, index, cube) -> {
			int depth = (int) path.chars()
				.filter(character -> character == '/')
				.count();
			if (depth <= 1)
				surface.add(cube, depth == 0);
		});
		return surface;
	}

	/** The ground a box covers, looking down on it. Whatever its height. */
	private record Footprint(float minX, float minZ, float maxX, float maxZ) {

		private static Footprint of(ModelPart.Cube cube) {
			return new Footprint(cube.minX, cube.minZ, cube.maxX, cube.maxZ);
		}
	}

	/** How far a part's boxes reach, and whether one of them is drawn twice. */
	private static final class Surface {

		private final Set<Footprint> footprints = new HashSet<>();

		/** Whether the part had any boxes of its own. Nothing can be fitted to one that had none. */
		private boolean measured;

		/** Whether a footprint turned up a second time, which is how an overlay is drawn. */
		private boolean overlaid;

		/** The top of the part's own boxes. y grows downwards, so the least is the highest. */
		private float top = Float.MAX_VALUE;

		/** ...and the bottom, which is what says where a sleeve ends and a hand would be. */
		private float bottom = -Float.MAX_VALUE;

		/**
		 * How far the part's own boxes reach sideways and front-to-back. Deliberately its own boxes
		 * and not the union with its children: a child is only consulted for whether an overlay is
		 * there at all, because a child that is <em>not</em> an overlay — a satchel, a belt, a tail
		 * — would otherwise be measured as though the torso were that wide, and the vest would grow
		 * to enclose it with its texture stretched across the difference.
		 */
		private float ownHalfWidth;
		private float ownHalfDepth;

		private void add(ModelPart.Cube cube, boolean own) {
			if (!footprints.add(Footprint.of(cube)))
				overlaid = true;
			if (!own)
				return;

			measured = true;
			top = Math.min(top, cube.minY);
			bottom = Math.max(bottom, cube.maxY);
			ownHalfWidth = Math.max(ownHalfWidth, Math.max(-cube.minX, cube.maxX));
			ownHalfDepth = Math.max(ownHalfDepth, Math.max(-cube.minZ, cube.maxZ));
		}
	}
}

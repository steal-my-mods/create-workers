package com.createworkers.client.model;

import com.createworkers.CreateWorkers;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * The hard hat and hi-vis vest worn by workers.
 *
 * <p>Boxes are declared at whole-number sizes and grown with {@link CubeDeformation} rather than
 * being written out at their final fractional size. The deformation inflates the geometry without
 * touching the UVs, which keeps every face landing on exact texels.
 *
 * <p>Villagers and endermen are not the same shape, so the gear is built per species:
 *
 * <ul>
 * <li>Heads differ in height — a villager's is 10 units tall against an enderman's 8 — so the hat
 * hangs off a {@code headTopY} parameter.
 * <li>Torsos differ in depth <em>and</em> in what is already drawn over them. A villager wears a
 * {@code jacket} overlay, its body inflated by 0.5, so a vest inflated by that same 0.5 lands on
 * exactly the jacket's surface and z-fights with it. The villager vest is inflated a full 1.0 —
 * the same figure vanilla uses for outer armour over a body — to sit clear. An enderman's torso
 * has no overlay, so 0.5 is plenty there.
 * </ul>
 */
public class WorkerGearModels {

	public static final ModelLayerLocation VILLAGER_GEAR =
		new ModelLayerLocation(CreateWorkers.asResource("worker_gear"), "villager");

	public static final ModelLayerLocation ENDERMAN_GEAR =
		new ModelLayerLocation(CreateWorkers.asResource("worker_gear"), "enderman");

	public static final String HAT = "hat";
	public static final String VEST = "vest";

	private static final int TEXTURE_WIDTH = 128;
	private static final int TEXTURE_HEIGHT = 64;

	/** Villager: head spans y -10..0, torso 8x12x6 under a 0.5-inflated jacket. */
	public static LayerDefinition createVillagerGear() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		addHat(root, -10.0F);
		addVest(root, 0, 28, 6, new CubeDeformation(1.0F));
		return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);
	}

	/** Enderman: head spans y -8..0, torso 8x12x4 with nothing drawn over it. */
	public static LayerDefinition createEndermanGear() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		addHat(root, -8.0F);
		addVest(root, 32, 28, 4, new CubeDeformation(0.5F));
		return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);
	}

	/**
	 * A hard hat, which is mostly a matter of getting the brim right: a shallow rim the whole way
	 * round with a peak jutting out over the face. A wide, even brim on every side is what reads as
	 * a straw hat instead.
	 */
	private static void addHat(PartDefinition root, float headTopY) {
		root.addOrReplaceChild(HAT, CubeListBuilder.create()
			// Crown, sunk into the skull so no seam shows at the hairline.
			.texOffs(0, 0)
			.addBox(-4.0F, headTopY - 3.0F, -4.0F, 8.0F, 5.0F, 8.0F, new CubeDeformation(0.5F))
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
			.addBox(-3.0F, headTopY + 1.0F, -8.0F, 6.0F, 1.0F, 3.0F),
			PartPose.ZERO);
	}

	private static void addVest(PartDefinition root, int texU, int texV, int torsoDepth,
		CubeDeformation inflate) {
		root.addOrReplaceChild(VEST, CubeListBuilder.create()
			.texOffs(texU, texV)
			.addBox(-4.0F, 0.5F, -torsoDepth / 2.0F, 8.0F, 9.0F, torsoDepth, inflate),
			PartPose.ZERO);
	}
}

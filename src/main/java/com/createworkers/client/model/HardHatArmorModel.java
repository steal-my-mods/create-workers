package com.createworkers.client.model;

import com.createworkers.CreateWorkers;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.world.entity.LivingEntity;

/**
 * The hard hat as worn by a player, rather than by a worker.
 *
 * <p>Armour is normally just a texture stretched over head-shaped geometry, which is why an
 * untreated helmet looks like a painted scalp. This swaps in the workers' actual hat geometry so a
 * player wearing one gets the same domed crown, comb and peak.
 *
 * <p>The awkward part is vanilla's {@code hat} part. It is a second head-sized box, a sibling of
 * {@code head} rather than a child, and {@code HumanoidArmorLayer} makes it visible for anything in
 * the head slot — so leaving it in place draws a solid cube over the whole skull no matter what the
 * head part contains. It has to be emptied out.
 */
public class HardHatArmorModel extends HumanoidModel<LivingEntity> {

	public static final ModelLayerLocation LAYER =
		new ModelLayerLocation(CreateWorkers.asResource("hard_hat_armor"), "main");

	/** A humanoid head box spans y -8..0, the same as an enderman's. */
	private static final float HEAD_TOP_Y = -8.0F;

	public HardHatArmorModel(ModelPart root) {
		super(root);
	}

	public static LayerDefinition createLayer() {
		MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
		PartDefinition root = mesh.getRoot();

		// The hat stands in for the head box outright.
		root.addOrReplaceChild("head", WorkerGearModels.hatCubes(HEAD_TOP_Y), PartPose.ZERO);
		// And the overlay box it would otherwise be buried under goes away.
		root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);

		// Body, arms and legs stay in the mesh because HumanoidModel's constructor requires them,
		// but the armour layer leaves them hidden for a head slot item.
		return LayerDefinition.create(mesh, WorkerGearModels.TEXTURE_WIDTH, WorkerGearModels.TEXTURE_HEIGHT);
	}
}

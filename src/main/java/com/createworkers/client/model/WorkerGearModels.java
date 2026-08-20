package com.createworkers.client.model;

import com.createworkers.CreateWorkers;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

/**
 * The hard hat and hi-vis vest worn by workers.
 *
 * <p>Villagers and endermen are not the same shape — a villager's head is 10 units tall against
 * an enderman's 8, and its torso is 6 deep against 4 — so the gear is built per species from one
 * parameterised mesh rather than stretched to fit both.
 */
public class WorkerGearModels {

	public static final ModelLayerLocation VILLAGER_GEAR =
		new ModelLayerLocation(CreateWorkers.asResource("worker_gear"), "villager");

	public static final ModelLayerLocation ENDERMAN_GEAR =
		new ModelLayerLocation(CreateWorkers.asResource("worker_gear"), "enderman");

	public static final String HAT = "hat";
	public static final String VEST = "vest";

	/** Villager: head spans y -10..0, body is 6 deep. */
	public static LayerDefinition createVillagerGear() {
		return create(-10.0F, 3.0F);
	}

	/** Enderman: head spans y -8..0, body is 4 deep. */
	public static LayerDefinition createEndermanGear() {
		return create(-8.0F, 2.0F);
	}

	private static LayerDefinition create(float headTopY, float bodyHalfDepth) {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();

		root.addOrReplaceChild(HAT, CubeListBuilder.create()
			// crown, sunk one unit into the skull so there is no seam
			.texOffs(0, 0)
			.addBox(-4.5F, headTopY - 3.0F, -4.5F, 9.0F, 4.0F, 9.0F)
			// brim
			.texOffs(0, 13)
			.addBox(-6.0F, headTopY + 0.5F, -6.0F, 12.0F, 1.0F, 12.0F),
			PartPose.ZERO);

		root.addOrReplaceChild(VEST, CubeListBuilder.create()
			.texOffs(0, 30)
			.addBox(-4.5F, 0.5F, -bodyHalfDepth - 0.5F, 9.0F, 9.0F, bodyHalfDepth * 2.0F + 1.0F),
			PartPose.ZERO);

		return LayerDefinition.create(mesh, 64, 64);
	}
}

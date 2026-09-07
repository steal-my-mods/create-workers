package com.createworkers.client;

import com.createworkers.CreateWorkers;
import com.createworkers.client.model.WorkerGearModels;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

/** Draws the hard hat on a worker's head and the hi-vis vest on its torso. */
public class WorkerGearLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

	private static final ResourceLocation TEXTURE = CreateWorkers.asResource("textures/entity/worker_gear.png");

	private final ModelPart hat;
	private final ModelPart vest;

	/**
	 * @param gear a gear root already fitted to the parent's model by
	 *			   {@link WorkerGearModels#fitTo}, which is also what vouched for the parent having
	 *			   a head and a torso to hang it on
	 */
	public WorkerGearLayer(RenderLayerParent<T, M> parent, ModelPart gear) {
		super(parent);
		this.hat = gear.getChild(WorkerGearModels.HAT);
		this.vest = gear.getChild(WorkerGearModels.VEST);
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, T entity,
		float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
		float headPitch) {

		WorkerData data = Workers.get(entity);
		if (data == null || !data.isEmployed())
			return;
		if (entity.isInvisible())
			return;

		VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));

		// Looked up per render rather than held: a renderer is free to hand back a different model
		// than the one the gear was fitted to, and gear a shade out of place beats a crash.
		ModelPart head = WorkerGearModels.headOf(getParentModel());
		if (head != null) {
			poseStack.pushPose();
			head.translateAndRotate(poseStack);
			hat.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
			poseStack.popPose();
		}

		ModelPart body = WorkerGearModels.bodyOf(getParentModel());
		if (body != null) {
			poseStack.pushPose();
			body.translateAndRotate(poseStack);
			vest.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
			poseStack.popPose();
		}
	}
}

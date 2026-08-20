package com.createworkers.client;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CreateWorkers;
import com.createworkers.client.model.WorkerGearModels;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
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

	public WorkerGearLayer(RenderLayerParent<T, M> parent, EntityModelSet models, ModelLayerLocation gear) {
		super(parent);
		ModelPart root = models.bakeLayer(gear);
		this.hat = root.getChild(WorkerGearModels.HAT);
		this.vest = root.getChild(WorkerGearModels.VEST);
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

		ModelPart head = findHead();
		if (head != null) {
			poseStack.pushPose();
			head.translateAndRotate(poseStack);
			hat.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
			poseStack.popPose();
		}

		ModelPart body = findBody();
		if (body != null) {
			poseStack.pushPose();
			body.translateAndRotate(poseStack);
			vest.render(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
			poseStack.popPose();
		}
	}

	@Nullable
	private ModelPart findHead() {
		M model = getParentModel();
		if (model instanceof HeadedModel headed)
			return headed.getHead();
		return null;
	}

	@Nullable
	private ModelPart findBody() {
		M model = getParentModel();
		if (model instanceof HumanoidModel<?> humanoid)
			return humanoid.body;
		if (model instanceof HierarchicalModel<?> hierarchical) {
			ModelPart root = hierarchical.root();
			if (root.hasChild("body"))
				return root.getChild("body");
		}
		return null;
	}
}

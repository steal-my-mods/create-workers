package com.createworkers.client;

import org.jetbrains.annotations.Nullable;

import com.createworkers.client.model.WorkerGearModels;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Shows what a worker is carrying: in its hand if the model it is drawn with has one, and held out
 * in front of the chest if it does not.
 *
 * <p>Which it gets is a question about the model rather than about the mob or the mod that supplied
 * it — see {@link WorkerGearModels#handsOf}. A vanilla villager's arms are one merged part
 * with no hands in it, so its cargo goes on the chest; a mod that draws villagers as humanoids gets
 * the hand without this having heard of it.
 *
 * <p>Endermen already have a vanilla layer for the block they are holding, so a block cargo is
 * handed to {@code setCarriedBlock} instead and skipped here — this only fills the gap for the
 * items vanilla will not draw, and gives villagers the same visible cargo.
 */
public class WorkerCargoLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

	private final ItemRenderer itemRenderer;

	@Nullable
	private final ArmedModel hands;

	/**
	 * @param hands the parent's model typed as something with hands, from
	 *              {@link WorkerGearModels#handsOf}, or null if this model's cargo belongs against
	 *              its chest. Resolved once here rather than per frame: it is a property of the
	 *              registered model, and the parts it is measured from are <em>animated</em> —
	 *              {@code HumanoidModel.setupAnim} moves {@code body.y} and {@code rightArm.y} for
	 *              a crouch — so measuring during a render could answer differently frame to frame
	 */
	public WorkerCargoLayer(RenderLayerParent<T, M> parent, ItemRenderer itemRenderer,
		@Nullable ArmedModel hands) {
		super(parent);
		this.itemRenderer = itemRenderer;
		this.hands = hands;
	}

	@Override
	public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, T entity,
		float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
		float headPitch) {

		WorkerData data = Workers.get(entity);
		if (data == null || !data.isEmployed())
			return;

		ItemStack cargo = data.getHeld();
		if (cargo.isEmpty())
			return;
		// Vanilla's carried-block layer already covers this case.
		if (entity instanceof EnderMan && cargo.getItem() instanceof BlockItem)
			return;

		if (hands != null)
			renderInHand(poseStack, bufferSource, packedLight, entity, cargo);
		else
			renderAgainstChest(poseStack, bufferSource, packedLight, entity, cargo);
	}

	/**
	 * The same sequence vanilla's {@code ItemInHandLayer} uses, so a worker holds its cargo exactly
	 * as any other mob holds an item — including the size, which comes from the item model's own
	 * third-person transform rather than from a figure picked here.
	 */
	private void renderInHand(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
		T entity, ItemStack cargo) {

		HumanoidArm arm = entity.getMainArm();
		boolean left = arm == HumanoidArm.LEFT;

		poseStack.pushPose();
		// The same allowance vanilla makes for a baby's hand, which matters once hireChildren is on.
		if (getParentModel().young) {
			poseStack.translate(0.0F, 0.75F, 0.0F);
			poseStack.scale(0.5F, 0.5F, 0.5F);
		}
		hands.translateToHand(arm, poseStack);
		poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		poseStack.translate((left ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);

		itemRenderer.renderStatic(entity, cargo,
			left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
			left, poseStack, bufferSource, entity.level(), packedLight, OverlayTexture.NO_OVERLAY,
			entity.getId());
		poseStack.popPose();
	}

	/** For a model with no hands to put it in: held out in front of the chest. */
	private void renderAgainstChest(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
		T entity, ItemStack cargo) {

		ModelPart body = WorkerGearModels.bodyOf(getParentModel());
		if (body == null)
			return;

		poseStack.pushPose();
		body.translateAndRotate(poseStack);
		// Same orientation fix-up vanilla uses when putting an item in a mob's hand.
		poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
		poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
		poseStack.translate(0.0F, 0.4F, -0.4F);
		poseStack.scale(0.75F, 0.75F, 0.75F);

		itemRenderer.renderStatic(cargo, ItemDisplayContext.GROUND, packedLight, OverlayTexture.NO_OVERLAY,
			poseStack, bufferSource, entity.level(), entity.getId());

		poseStack.popPose();
	}
}

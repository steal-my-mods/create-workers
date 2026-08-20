package com.createworkers.client;

import org.jetbrains.annotations.Nullable;

import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Shows what a worker is carrying, held out in front of the chest.
 *
 * <p>Endermen already have a vanilla layer for the block they are holding, so a block cargo is
 * handed to {@code setCarriedBlock} instead and skipped here — this only fills the gap for the
 * items vanilla will not draw, and gives villagers the same visible cargo.
 */
public class WorkerCargoLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {

	private final ItemRenderer itemRenderer;

	public WorkerCargoLayer(RenderLayerParent<T, M> parent, ItemRenderer itemRenderer) {
		super(parent);
		this.itemRenderer = itemRenderer;
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

		ModelPart body = findBody();
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

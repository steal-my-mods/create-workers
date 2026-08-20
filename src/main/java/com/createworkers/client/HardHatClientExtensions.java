package com.createworkers.client;

import org.jetbrains.annotations.Nullable;

import com.createworkers.client.model.HardHatArmorModel;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/** Hands the armour renderer the workers' hat geometry in place of a head-shaped box. */
public class HardHatClientExtensions implements IClientItemExtensions {

	@Nullable
	private static HumanoidModel<LivingEntity> model;

	/** Re-baked whenever entity models are, so a resource reload picks up changes. */
	public static void bake(EntityModelSet models) {
		model = new HardHatArmorModel(models.bakeLayer(HardHatArmorModel.LAYER));
	}

	@Override
	public HumanoidModel<?> getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot,
		HumanoidModel<?> original) {
		// Falling back to the original is only relevant before the first bake.
		return model == null ? original : model;
	}
}

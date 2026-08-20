package com.createworkers.client;

import com.createworkers.client.model.HardHatArmorModel;
import com.createworkers.client.model.WorkerGearModels;
import com.createworkers.registry.CWItems;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** Client-only setup: gear models and the extra render layers on villagers and endermen. */
public class CWClient {

	public static void init(IEventBus modBus) {
		modBus.addListener(CWClient::registerLayerDefinitions);
		modBus.addListener(CWClient::addEntityLayers);
		modBus.addListener(CWClient::registerClientExtensions);
	}

	private static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
		event.registerLayerDefinition(WorkerGearModels.VILLAGER_GEAR, WorkerGearModels::createVillagerGear);
		event.registerLayerDefinition(WorkerGearModels.ENDERMAN_GEAR, WorkerGearModels::createEndermanGear);
		event.registerLayerDefinition(HardHatArmorModel.LAYER, HardHatArmorModel::createLayer);
	}

	private static void registerClientExtensions(RegisterClientExtensionsEvent event) {
		event.registerItem(new HardHatClientExtensions(), CWItems.HARD_HAT.get());
	}

	private static void addEntityLayers(EntityRenderersEvent.AddLayers event) {
		attach(event, EntityType.VILLAGER, WorkerGearModels.VILLAGER_GEAR);
		attach(event, EntityType.ENDERMAN, WorkerGearModels.ENDERMAN_GEAR);

		// This event also fires after every resource reload, which is when the worn-hat model needs
		// re-baking.
		HardHatClientExtensions.bake(event.getEntityModels());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void attach(EntityRenderersEvent.AddLayers event, EntityType<? extends LivingEntity> type,
		ModelLayerLocation gear) {
		EntityRenderer<?> renderer = event.getRenderer(type);
		if (!(renderer instanceof LivingEntityRenderer living))
			return;

		living.addLayer(new WorkerGearLayer(living, event.getEntityModels(), gear));
		living.addLayer(new WorkerCargoLayer(living, event.getContext()
			.getItemRenderer()));
	}
}

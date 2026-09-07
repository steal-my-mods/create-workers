package com.createworkers.client;

import com.createworkers.client.model.HardHatArmorModel;
import com.createworkers.client.model.WorkerGearModels;
import com.createworkers.client.ponder.CWPonderPlugin;
import com.createworkers.registry.CWItems;

import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** Client-only setup: gear models and the extra render layers the workers are drawn with. */
public class CWClient {

	public static void init(IEventBus modBus) {
		modBus.addListener(CWClient::registerLayerDefinitions);
		modBus.addListener(CWClient::addEntityLayers);
		modBus.addListener(CWClient::registerClientExtensions);
		modBus.addListener(CWClient::registerPonderScenes);
	}

	/**
	 * Where Create registers its own Ponder plugin, so the scenes are all in place before anything
	 * asks the index to compile them. Enqueued rather than run inline because client setup is
	 * dispatched in parallel and Ponder's plugin list is a plain {@code ArrayList}.
	 */
	private static void registerPonderScenes(FMLClientSetupEvent event) {
		event.enqueueWork(() -> PonderIndex.addPlugin(new CWPonderPlugin()));
	}

	private static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
		event.registerLayerDefinition(HardHatArmorModel.LAYER, HardHatArmorModel::createLayer);
	}

	private static void registerClientExtensions(RegisterClientExtensionsEvent event) {
		event.registerItem(new HardHatClientExtensions(), CWItems.HARD_HAT.get());
	}

	/**
	 * The gear goes on by renderer, not by entity type. A worker is any villager or enderman, and
	 * mods hand those out under their own entity types — Villagers Reborn replaces every villager
	 * in the world with one of its own, which is a {@code Villager} subclass and so hires, hauls
	 * and retires exactly like the vanilla mob, while asking for a renderer this mod had never
	 * heard of. Attaching to {@code EntityType.VILLAGER} therefore dressed nobody.
	 *
	 * <p>So every living renderer is offered the gear, and the ones that can wear it take it:
	 * {@link WorkerGearModels#fitTo} measures the model and returns null unless it has a head and a
	 * torso to hang gear on, which excludes most of the renderers in the game. The layers cost
	 * nothing on the rest — both bail out on the first line for any entity without worker state,
	 * and only villagers and endermen are ever given any.
	 *
	 * <p>What this cannot reach is a renderer that is not a {@link LivingEntityRenderer} at all.
	 * GeckoLib's is not: it draws bones rather than a {@code ModelPart} tree and keeps its own
	 * layer list, so a worker rendered through it would need separate support.
	 */
	private static void addEntityLayers(EntityRenderersEvent.AddLayers event) {
		for (EntityType<?> type : event.getEntityTypes())
			attach(event, type);

		// This event also fires after every resource reload, which is when the worn-hat model needs
		// re-baking.
		HardHatClientExtensions.bake(event.getEntityModels());
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void attach(EntityRenderersEvent.AddLayers event, EntityType<?> type) {
		EntityRenderer<?> renderer = event.getRenderer((EntityType) type);
		if (!(renderer instanceof LivingEntityRenderer living))
			return;

		ModelPart gear = WorkerGearModels.fitTo(living.getModel());
		if (gear == null)
			return;

		living.addLayer(new WorkerGearLayer(living, gear));
		living.addLayer(new WorkerCargoLayer(living, event.getContext()
			.getItemRenderer(), WorkerGearModels.handsOf(living.getModel())));
	}
}

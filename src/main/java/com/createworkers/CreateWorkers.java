package com.createworkers;

import com.createworkers.net.CWNetwork;
import com.createworkers.registry.CWAttachments;
import com.createworkers.registry.CWComponents;
import com.createworkers.registry.CWItems;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create: Workers — hard-hatted villagers and endermen that haul items around a factory the
 * way a Mechanical Arm does.
 */
@Mod(CreateWorkers.ID)
public class CreateWorkers {

	public static final String ID = "createworkers";
	public static final Logger LOGGER = LoggerFactory.getLogger("Create: Workers");

	public CreateWorkers(IEventBus modBus, ModContainer container) {
		CWComponents.REGISTER.register(modBus);
		CWAttachments.REGISTER.register(modBus);
		CWItems.ARMOR_MATERIALS.register(modBus);
		CWItems.ITEMS.register(modBus);
		CWItems.TABS.register(modBus);

		modBus.addListener(CWNetwork::register);

		if (FMLEnvironment.dist == Dist.CLIENT)
			com.createworkers.client.CWClient.init(modBus);

		container.registerConfig(ModConfig.Type.SERVER, CWConfig.SPEC);
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}

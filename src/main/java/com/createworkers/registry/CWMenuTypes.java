package com.createworkers.registry;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Screens this mod opens. */
public class CWMenuTypes {

	public static final DeferredRegister<MenuType<?>> REGISTER =
		DeferredRegister.create(Registries.MENU, CreateWorkers.ID);

	public static final DeferredHolder<MenuType<?>, MenuType<WorkerStationMenu>> WORKER_STATION =
		REGISTER.register("worker_station", () -> IMenuTypeExtension.create(
			(id, inventory, buf) -> new WorkerStationMenu(CWMenuTypes.WORKER_STATION.get(), id, inventory, buf)));
}

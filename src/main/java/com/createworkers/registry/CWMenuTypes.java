package com.createworkers.registry;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.block.WorkerStationMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Screens this mod opens. */
public class CWMenuTypes {

	public static final DeferredRegister<MenuType<?>> REGISTER =
		DeferredRegister.create(Registries.MENU, CreateWorkers.ID);

	/**
	 * The station's rack.
	 *
	 * <p>The buffer is read <b>here</b> rather than in the menu's own {@code createOnClient}, and that
	 * is not tidiness. {@code MenuBase} calls {@code createOnClient} before it has assigned itself a
	 * player, so a menu that wanted a level there would have to reach for {@code Minecraft} — which
	 * loads a client class from a class a dedicated server also loads, and dies on one. A factory takes
	 * the inventory as an argument, and an inventory has a player, and a player has a level.
	 */
	public static final DeferredHolder<MenuType<?>, MenuType<WorkerStationMenu>> WORKER_STATION =
		REGISTER.register("worker_station", () -> IMenuTypeExtension.create((id, inventory, buf) -> {
			BlockPos pos = buf.readBlockPos();
			if (inventory.player.level()
				.getBlockEntity(pos) instanceof WorkerStationBlockEntity station)
				return new WorkerStationMenu(CWMenuTypes.WORKER_STATION.get(), id, inventory, station);
			return null;
		}));
}

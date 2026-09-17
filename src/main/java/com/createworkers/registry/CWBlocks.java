package com.createworkers.registry;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlock;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Blocks, of which there is one: the station that hires workers. */
public class CWBlocks {

	public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateWorkers.ID);

	public static final DeferredBlock<WorkerStationBlock> WORKER_STATION = BLOCKS.register("worker_station",
		() -> new WorkerStationBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.WOOD)
			.strength(2.5F)
			.sound(SoundType.WOOD)));
	// No noOcclusion: the Station fills its block, so it should occlude, light and cull like any
	// other solid one. It needed the flag while it was a bench with a board on it, and leaving the
	// flag behind after the shape became a full cube would quietly cost every neighbour the face it
	// would otherwise cull -- paying for a problem the block no longer has.

	public static final DeferredItem<BlockItem> WORKER_STATION_ITEM =
		CWItems.ITEMS.registerSimpleBlockItem("worker_station", WORKER_STATION, new Item.Properties());
}

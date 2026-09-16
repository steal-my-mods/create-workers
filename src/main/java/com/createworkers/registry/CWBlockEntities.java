package com.createworkers.registry;

import com.createworkers.CreateWorkers;
import com.createworkers.block.WorkerStationBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CWBlockEntities {

	public static final DeferredRegister<BlockEntityType<?>> REGISTER =
		DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateWorkers.ID);

	public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WorkerStationBlockEntity>> WORKER_STATION =
		REGISTER.register("worker_station",
			() -> BlockEntityType.Builder.of(WorkerStationBlockEntity::new, CWBlocks.WORKER_STATION.get())
				.build(null));
}

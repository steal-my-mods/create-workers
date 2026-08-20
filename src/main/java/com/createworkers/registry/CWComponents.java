package com.createworkers.registry;

import java.util.function.Supplier;

import com.createworkers.CreateWorkers;
import com.createworkers.program.WorkerProgram;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Data components stored on the hard hat item. */
public class CWComponents {

	public static final DeferredRegister<DataComponentType<?>> REGISTER =
		DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CreateWorkers.ID);

	/** The inventories this hat has been programmed with. */
	public static final Supplier<DataComponentType<WorkerProgram>> PROGRAM = REGISTER.register("program",
		() -> DataComponentType.<WorkerProgram>builder()
			.persistent(WorkerProgram.CODEC)
			.networkSynchronized(WorkerProgram.STREAM_CODEC)
			.build());
}

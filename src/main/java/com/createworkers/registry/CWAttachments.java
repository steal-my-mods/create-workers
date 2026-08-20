package com.createworkers.registry;

import java.util.function.Supplier;

import com.createworkers.CreateWorkers;
import com.createworkers.worker.WorkerData;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public class CWAttachments {

	public static final DeferredRegister<AttachmentType<?>> REGISTER =
		DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, CreateWorkers.ID);

	/** Worker state, attached to any villager or enderman wearing a hard hat. */
	public static final Supplier<AttachmentType<WorkerData>> WORKER = REGISTER.register("worker",
		() -> AttachmentType.serializable(WorkerData::new)
			.build());
}

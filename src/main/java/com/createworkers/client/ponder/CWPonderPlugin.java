package com.createworkers.client.ponder;

import com.createworkers.CreateWorkers;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers this mod's Ponder scenes.
 *
 * <p>Ponder is not a dependency of ours in any build sense — it is one of the libraries Create
 * ships jar-in-jar and we compile against without shipping (see the build notes in CLAUDE.md), so
 * everything here is client-only and reached only from {@code CWClient}.
 *
 * <p>The component a scene is filed under is an item's registry id, which is how Ponder decides
 * what to offer when a player holds W over a stack. So the scene below hangs off the hard hat
 * itself, not off any block.
 */
public class CWPonderPlugin implements PonderPlugin {

	@Override
	public String getModId() {
		return CreateWorkers.ID;
	}

	@Override
	public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
		// The second argument names the schematic, which Ponder loads from
		// assets/createworkers/ponder/<name>.nbt. All three scenes hang off the same item, so Ponder
		// offers them as consecutive pages -- which is why the order here is the order to watch them
		// in, and why each one assumes the ones before it have been seen. They are also staged on the
		// same yard for that reason.
		helper.addStoryBoard(CreateWorkers.asResource("hard_hat"), "hard_hat", HardHatScene::programming);
		helper.addStoryBoard(CreateWorkers.asResource("hard_hat"), "worker_station", WorkerStationScene::hiring);
		helper.addStoryBoard(CreateWorkers.asResource("hard_hat"), "working_hours", WorkingHoursScene::nightShift);
	}
}

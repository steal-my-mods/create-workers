package com.createworkers.client.ponder;

import com.createworkers.registry.CWItems;
import com.createworkers.worker.Workers;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.DepotBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The hard hat's Ponder scene: programme a hat, hire a villager, watch it haul.
 *
 * <p>Staged on {@code assets/createworkers/ponder/hard_hat.nbt}, which
 * {@code tools/generate_ponder_structure.py} writes — a seven-by-seven plate with a Depot at two
 * opposite corners. The positions below are that script's, restated; there is no build step
 * tying the two together, so move a Depot in one and it has to move in the other.
 *
 * <p>Built through Create's {@link CreateSceneBuilder} rather than the plain Ponder one, for
 * {@code createItemOnBeltLike} — it hands an item to the Depot through the same
 * {@code DirectBeltInputBehaviour} a real delivery goes through, so it lands looking like
 * something that was put there rather than something that was assigned into place.
 *
 * <p>Every string handed to {@code text} here is only a default for the lang generator's benefit.
 * Outside Ponder's editing mode the text actually shown comes from
 * {@code createworkers.ponder.hard_hat.text_<n>} in the lang file, numbered by the order the calls
 * are made below, and a missing key renders as the key. Renumber carefully.
 */
public class HardHatScene {

	private static final BlockPos INPUT_DEPOT = new BlockPos(1, 1, 1);
	private static final BlockPos OUTPUT_DEPOT = new BlockPos(5, 1, 5);

	/** Where the worker stands to reach each depot, and where it waits before it is hired. */
	private static final Vec3 BESIDE_INPUT = new Vec3(2.5, 1, 1.5);
	private static final Vec3 BESIDE_OUTPUT = new Vec3(4.5, 1, 5.5);
	private static final Vec3 UNEMPLOYED = new Vec3(3.5, 1, 3.5);

	/** Roughly a villager's own pace, about ten ticks to the block. */
	private static final int WALK_TO_INPUT = 25;
	private static final int WALK_TO_OUTPUT = 45;

	/** Head height, for pointing at a worker rather than at its feet. */
	private static final Vec3 EYE = new Vec3(0, 1.5, 0);

	public static void hiring(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
		scene.title("hard_hat", "Putting Workers to Work");
		scene.configureBasePlate(0, 0, 7);
		scene.showBasePlate();

		Selection depots = util.select()
			.position(INPUT_DEPOT)
			.add(util.select()
				.position(OUTPUT_DEPOT));
		Vec3 inputTop = util.vector()
			.topOf(INPUT_DEPOT);
		Vec3 outputTop = util.vector()
			.topOf(OUTPUT_DEPOT);
		Vec3 middle = util.vector()
			.centerOf(3, 1, 3);

		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		ItemStack cargo = new ItemStack(Items.COPPER_INGOT);

		scene.idle(5);
		scene.world()
			.showSection(depots, Direction.DOWN);
		scene.idle(15);
		scene.world()
			.createItemOnBeltLike(INPUT_DEPOT, Direction.NORTH, cargo);

		scene.overlay()
			.showText(80)
			.text("Workers carry items between inventories, much like a Mechanical Arm that can walk")
			.attachKeyFrame()
			.pointAt(middle)
			.placeNearTarget();
		scene.idle(90);

		// --- programming the hat ---------------------------------------------------------

		scene.overlay()
			.showControls(inputTop, Pointing.DOWN, 30)
			.withItem(hat)
			.rightClick();
		scene.idle(7);
		scene.overlay()
			.showOutline(PonderPalette.INPUT, INPUT_DEPOT, util.select()
				.position(INPUT_DEPOT), 140);
		scene.idle(3);
		scene.overlay()
			.showText(70)
			.text("Right-Click Arm-compatible blocks while holding the Hard Hat to assign them")
			.attachKeyFrame()
			.colored(PonderPalette.INPUT)
			.pointAt(inputTop)
			.placeNearTarget();
		scene.idle(80);

		// Twice, because a fresh target starts out as an Input -- the second click is what makes
		// it an Output, exactly as on the Mechanical Arm.
		scene.overlay()
			.showControls(outputTop, Pointing.DOWN, 30)
			.withItem(hat)
			.rightClick();
		scene.idle(7);
		scene.overlay()
			.showOutline(PonderPalette.INPUT, OUTPUT_DEPOT, util.select()
				.position(OUTPUT_DEPOT), 20);
		scene.idle(20);
		scene.overlay()
			.showControls(outputTop, Pointing.DOWN, 30)
			.withItem(hat)
			.rightClick();
		scene.idle(7);
		scene.overlay()
			.showOutline(PonderPalette.OUTPUT, OUTPUT_DEPOT, util.select()
				.position(OUTPUT_DEPOT), 190);
		scene.idle(3);
		scene.overlay()
			.showText(70)
			.text("The first Right-Click marks a block as an Input, a second turns it into an Output")
			.attachKeyFrame()
			.colored(PonderPalette.OUTPUT)
			.pointAt(outputTop)
			.placeNearTarget();
		scene.idle(80);

		scene.overlay()
			.showLine(PonderPalette.WHITE, inputTop, outputTop, 70);
		scene.overlay()
			.showText(70)
			.text("One worker walks between all of them, so every assigned block has to stay within reach of every other")
			.attachKeyFrame()
			.pointAt(middle)
			.placeNearTarget();
		scene.idle(80);

		// --- hiring ----------------------------------------------------------------------

		ElementLink<EntityElement> worker = scene.world()
			.createEntity(level -> {
				Villager villager = new Villager(EntityType.VILLAGER, level);
				WalkInstruction.place(villager, UNEMPLOYED, 180);
				return villager;
			});
		scene.idle(15);

		scene.overlay()
			.showControls(UNEMPLOYED.add(EYE), Pointing.DOWN, 30)
			.withItem(hat)
			.rightClick();
		scene.idle(7);
		scene.overlay()
			.showText(70)
			.text("Right-Click a Villager or an Enderman with the programmed Hat to put them to work")
			.attachKeyFrame()
			.pointAt(UNEMPLOYED.add(EYE))
			.placeNearTarget();
		scene.idle(23);
		dress(scene, worker, hat, ItemStack.EMPTY);
		scene.idle(50);

		// --- a round of work -------------------------------------------------------------

		scene.addKeyframe();
		scene.addInstruction(new WalkInstruction(worker, UNEMPLOYED, BESIDE_INPUT, WALK_TO_INPUT));
		scene.idle(WALK_TO_INPUT + 5);

		scene.world()
			.modifyBlockEntity(INPUT_DEPOT, DepotBlockEntity.class, depot -> depot.getBehaviour(DepotBehaviour.TYPE)
				.removeHeldItem());
		dress(scene, worker, hat, cargo);
		scene.effects()
			.indicateSuccess(INPUT_DEPOT);
		scene.idle(10);

		scene.overlay()
			.showText(WALK_TO_OUTPUT + 30)
			.text("They will carry items from the assigned Inputs to the assigned Outputs, and keep at it")
			.attachKeyFrame()
			.pointAt(middle)
			.placeNearTarget();
		scene.addInstruction(new WalkInstruction(worker, BESIDE_INPUT, BESIDE_OUTPUT, WALK_TO_OUTPUT));
		scene.idle(WALK_TO_OUTPUT + 5);

		dress(scene, worker, hat, ItemStack.EMPTY);
		// From the west, which is the side the worker is standing on.
		scene.world()
			.createItemOnBeltLike(OUTPUT_DEPOT, Direction.WEST, cargo);
		scene.effects()
			.indicateSuccess(OUTPUT_DEPOT);
		scene.idle(30);

		// --- clocking off ----------------------------------------------------------------

		scene.overlay()
			.showControls(BESIDE_OUTPUT.add(EYE), Pointing.DOWN, 40)
			.whileSneaking()
			.rightClick();
		scene.idle(7);
		scene.overlay()
			.showText(70)
			.text("Sneak and Right-Click them with an empty hand to clock them out — the Hat and anything they are carrying come back")
			.attachKeyFrame()
			.pointAt(BESIDE_OUTPUT.add(EYE))
			.placeNearTarget();
		scene.idle(33);
		dress(scene, worker, ItemStack.EMPTY, ItemStack.EMPTY);
		scene.idle(50);

		scene.markAsFinished();
	}

	/**
	 * Puts the gear on a scene worker, or takes it off again.
	 *
	 * <p>The hat and the cargo are drawn straight off the entity's {@code WorkerData} attachment,
	 * which is also all {@code WorkerStatePacket} ever does to a real worker's client copy — so a
	 * ponder worker dresses itself with no server and no networking involved.
	 */
	private static void dress(CreateSceneBuilder scene, ElementLink<EntityElement> worker, ItemStack hat,
		ItemStack held) {
		scene.world()
			.modifyEntity(worker, entity -> Workers.getOrCreate(entity)
				.applyClientState(hat.copy(), held.copy()));
	}
}

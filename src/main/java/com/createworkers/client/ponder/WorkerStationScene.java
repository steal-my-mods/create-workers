package com.createworkers.client.ponder;

import com.createworkers.block.WorkerStationBlock;
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
 * The second scene: a Worker Station hires somebody.
 *
 * <p>This is the page the mod was missing. Hiring a villager by right-clicking it with a hat was
 * taken out when stations arrived — a villager cannot be put to work any other way now — and the
 * first scene went on teaching it for several versions, which is the worst kind of documentation
 * because nothing in the game contradicts it.
 *
 * <p>Staged on {@code assets/createworkers/ponder/worker_station.nbt}: the same yard as the scene
 * before it, deliberately, with a Station added in the corner the Depots leave free. The positions
 * below are {@code tools/generate_ponder_structure.py}'s restated, and nothing ties the two
 * together, so move one and move the other.
 *
 * <p>The Station starts with {@code has_job} false, because an empty one is not a job site at all
 * and that is half of what the scene is showing. The hat going in flips it.
 *
 * <p>Every string handed to {@code text} here is only a default for the lang generator's benefit.
 * Outside Ponder's editing mode the text actually shown comes from
 * {@code createworkers.ponder.worker_station.text_<n>} in the lang file, numbered by the order the
 * calls are made below, and a missing key renders as the key. Renumber carefully.
 */
public class WorkerStationScene {

	private static final BlockPos INPUT_DEPOT = new BlockPos(1, 1, 1);
	private static final BlockPos OUTPUT_DEPOT = new BlockPos(5, 1, 5);
	private static final BlockPos STATION = new BlockPos(1, 1, 5);

	/** Where the worker stands to reach each block, and where it waits before it is taken on. */
	private static final Vec3 BESIDE_INPUT = new Vec3(2.5, 1, 1.5);
	private static final Vec3 BESIDE_OUTPUT = new Vec3(4.5, 1, 5.5);
	private static final Vec3 BESIDE_STATION = new Vec3(2.5, 1, 5.5);
	private static final Vec3 UNEMPLOYED = new Vec3(3.5, 1, 3.5);

	/** Roughly a villager's own pace, about ten ticks to the block. */
	private static final int WALK_TO_STATION = 25;
	private static final int WALK_TO_INPUT = 45;
	private static final int WALK_TO_OUTPUT = 45;

	/** Head height, for pointing at a worker rather than at its feet. */
	private static final Vec3 EYE = new Vec3(0, 1.5, 0);

	public static void hiring(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
		scene.title("worker_station", "Putting Workers to Work");
		scene.configureBasePlate(0, 0, 7);
		scene.showBasePlate();

		Selection depots = util.select()
			.position(INPUT_DEPOT)
			.add(util.select()
				.position(OUTPUT_DEPOT));
		Selection station = util.select()
			.position(STATION);
		Vec3 stationTop = util.vector()
			.topOf(STATION);
		Vec3 middle = util.vector()
			.centerOf(3, 1, 3);

		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		ItemStack cargo = new ItemStack(Items.COPPER_INGOT);

		scene.idle(5);
		scene.world()
			.showSection(depots, Direction.DOWN);
		scene.idle(10);
		scene.world()
			.showSection(station, Direction.DOWN);
		scene.idle(15);
		scene.world()
			.createItemOnBeltLike(INPUT_DEPOT, Direction.NORTH, cargo);

		scene.overlay()
			.showText(80)
			.text("A Worker Station is where jobs live. Villagers are hired by one, and no other way")
			.attachKeyFrame()
			.pointAt(stationTop)
			.placeNearTarget();
		scene.idle(90);

		// --- the hat goes in ---------------------------------------------------------------

		scene.overlay()
			.showControls(stationTop, Pointing.DOWN, 30)
			.withItem(hat)
			.rightClick();
		scene.idle(7);
		// The plate ships the Station empty, which is not decoration: an empty one is not a job site
		// at all. This is the moment it becomes one.
		scene.world()
			.cycleBlockProperty(STATION, WorkerStationBlock.HAS_JOB);
		scene.effects()
			.indicateSuccess(STATION);
		scene.overlay()
			.showText(70)
			.text("Put a programmed Hard Hat into it to open a job")
			.attachKeyFrame()
			.colored(PonderPalette.GREEN)
			.pointAt(stationTop)
			.placeNearTarget();
		scene.idle(80);

		// --- somebody takes it -------------------------------------------------------------

		ElementLink<EntityElement> worker = scene.world()
			.createEntity(level -> {
				Villager villager = new Villager(EntityType.VILLAGER, level);
				WalkInstruction.place(villager, UNEMPLOYED, 400);
				return villager;
			});
		scene.idle(15);

		scene.overlay()
			.showText(70)
			.text("The Station takes on an unemployed Villager nearby — one it can walk to")
			.attachKeyFrame()
			.pointAt(UNEMPLOYED.add(EYE))
			.placeNearTarget();
		scene.idle(20);

		scene.addInstruction(new WalkInstruction(worker, UNEMPLOYED, BESIDE_STATION, WALK_TO_STATION));
		scene.idle(WALK_TO_STATION + 5);
		dress(scene, worker, hat, ItemStack.EMPTY);
		scene.effects()
			.indicateSuccess(STATION);
		scene.idle(15);

		scene.overlay()
			.showText(70)
			.text("It wears a copy of the Hat. The Hat itself stays in the Station")
			.attachKeyFrame()
			.pointAt(stationTop)
			.placeNearTarget();
		scene.idle(80);

		// --- a round of work ---------------------------------------------------------------

		scene.addKeyframe();
		scene.addInstruction(new WalkInstruction(worker, BESIDE_STATION, BESIDE_INPUT, WALK_TO_INPUT));
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
			.text("Then it works the Hat's beat, carrying items from the Inputs to the Outputs")
			.attachKeyFrame()
			.pointAt(middle)
			.placeNearTarget();
		scene.addInstruction(new WalkInstruction(worker, BESIDE_INPUT, BESIDE_OUTPUT, WALK_TO_OUTPUT));
		scene.idle(WALK_TO_OUTPUT + 5);

		dress(scene, worker, hat, ItemStack.EMPTY);
		scene.world()
			.createItemOnBeltLike(OUTPUT_DEPOT, Direction.WEST, cargo);
		scene.effects()
			.indicateSuccess(OUTPUT_DEPOT);
		scene.idle(30);

		// --- the job outlives the worker ---------------------------------------------------

		scene.overlay()
			.showOutline(PonderPalette.GREEN, STATION, station, 80);
		scene.overlay()
			.showText(80)
			.text("Because the job never left the block, a Worker that dies leaves it behind — and the next Villager along takes it up")
			.attachKeyFrame()
			.colored(PonderPalette.GREEN)
			.pointAt(stationTop)
			.placeNearTarget();
		scene.idle(90);

		scene.overlay()
			.showControls(stationTop, Pointing.DOWN, 40)
			.rightClick();
		scene.idle(7);
		scene.overlay()
			.showText(80)
			.text("Right-Click the Station to open its rack: up to twelve jobs, each with its own shifts")
			.attachKeyFrame()
			.pointAt(stationTop)
			.placeNearTarget();
		scene.idle(90);

		scene.overlay()
			.showText(80)
			.text("Taking a Hat back out ends that job and lets its Workers go")
			.attachKeyFrame()
			.pointAt(stationTop)
			.placeNearTarget();
		scene.idle(90);

		scene.overlay()
			.showText(80)
			.text("Endermen cannot use a Station. They are still hired by hand, Right-Clicked with the Hat")
			.attachKeyFrame()
			.pointAt(middle)
			.placeNearTarget();
		scene.idle(90);

		scene.markAsFinished();
	}

	/**
	 * Puts the gear on a scene worker, or takes it off again.
	 *
	 * <p>The hat and the cargo are drawn straight off the entity's {@code WorkerData} attachment,
	 * which is also all {@code WorkerStatePacket} ever does to a real worker's client copy — so a
	 * ponder worker dresses itself with no server and no networking involved.
	 */
	static void dress(CreateSceneBuilder scene, ElementLink<EntityElement> worker, ItemStack hat, ItemStack held) {
		scene.world()
			.modifyEntity(worker, entity -> Workers.getOrCreate(entity)
				.applyClientState(hat.copy(), held.copy()));
	}
}

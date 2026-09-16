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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The second of the hard hat's Ponder scenes: a worker finishing its last delivery of the day,
 * walking to its bed, and getting up again in the morning.
 *
 * <p>Staged on {@code assets/createworkers/ponder/working_hours.nbt} — the same yard as
 * {@link HardHatScene}, deliberately, so a player who has watched the first scene knows where they
 * are, with a bed added in the corner the Depots leave free.
 * {@code tools/generate_ponder_structure.py} writes both, and the positions below are that script's
 * restated; there is nothing tying the two together, so move one and move the other.
 *
 * <p>Night itself cannot be shown. A ponder level has no sky and no clock, so the only things
 * carrying "it is the end of the day" are the text and the fact that the worker gets into a bed.
 * That is also why the scene opens mid-haul rather than at dawn: a worker walking to a bed only
 * reads as knocking off if it was visibly working a moment earlier.
 *
 * <p>Every string handed to {@code text} here is only a default for the lang generator's benefit.
 * Outside Ponder's editing mode the text actually shown comes from
 * {@code createworkers.ponder.working_hours.text_<n>} in the lang file, numbered by the order the
 * calls are made below, and a missing key renders as the key. Renumber carefully.
 */
public class WorkingHoursScene {

	private static final BlockPos INPUT_DEPOT = new BlockPos(1, 1, 1);
	private static final BlockPos OUTPUT_DEPOT = new BlockPos(5, 1, 5);
	/** The head is the half that matters: it is the point of interest, and where a sleeper lies. */
	/** The same Station as the scene before, so the yard is visibly the place the player just saw. */
	private static final BlockPos STATION = new BlockPos(1, 1, 5);
	private static final BlockPos BED_HEAD = new BlockPos(5, 1, 1);
	private static final BlockPos BED_FOOT = new BlockPos(5, 1, 2);

	/** Where the worker stands to reach each Depot, and to climb into bed. */
	private static final Vec3 BESIDE_INPUT = new Vec3(2.5, 1, 1.5);
	private static final Vec3 BESIDE_OUTPUT = new Vec3(4.5, 1, 5.5);
	private static final Vec3 BESIDE_BED = new Vec3(4.5, 1, 1.5);

	/** Roughly a villager's own pace, about ten ticks to the block. */
	private static final int WALK_TO_OUTPUT = 45;
	private static final int WALK_TO_BED = 40;
	private static final int WALK_TO_WORK = 20;

	/** Head height, for pointing at a worker rather than at its feet. */
	private static final Vec3 EYE = new Vec3(0, 1.5, 0);
	/** An enderman's, which is rather higher. */
	private static final Vec3 TALL_EYE = new Vec3(0, 2.5, 0);

	/** Facing south, which is the way a worker arrives at everything on this plate. */
	private static final float FACING_SOUTH = 180;

	public static void nightShift(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
		scene.title("working_hours", "Shifts and Sleep");
		scene.configureBasePlate(0, 0, 7);
		scene.showBasePlate();

		Selection bed = util.select()
			.position(BED_HEAD)
			.add(util.select()
				.position(BED_FOOT));
		Selection fittings = util.select()
			.position(INPUT_DEPOT)
			.add(util.select()
				.position(OUTPUT_DEPOT))
			.add(util.select()
				.position(STATION))
			.add(bed);
		Vec3 bedTop = util.vector()
			.topOf(BED_HEAD);
		Vec3 outputTop = util.vector()
			.topOf(OUTPUT_DEPOT);

		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		ItemStack cargo = new ItemStack(Items.COPPER_INGOT);

		scene.idle(5);
		scene.world()
			.showSection(fittings, Direction.DOWN);
		scene.idle(15);

		// Opening mid-haul: hat on, an ingot in hand, one stop from the end of the shift.
		ElementLink<EntityElement> worker = scene.world()
			.createEntity(level -> {
				Villager villager = new Villager(EntityType.VILLAGER, level);
				WalkInstruction.place(villager, BESIDE_INPUT, FACING_SOUTH);
				return villager;
			});
		dress(scene, worker, hat, cargo);
		scene.idle(10);

		scene.overlay()
			.showText(80)
			.text("At the end of its crew's day a Worker downs tools, walks to a bed and sleeps until morning")
			.attachKeyFrame()
			.pointAt(BESIDE_INPUT.add(EYE))
			.placeNearTarget();
		scene.idle(90);

		scene.overlay()
			.showText(90)
			.text("A job can run up to three crews — day, evening and night — so one Hat can be covered around the clock")
			.attachKeyFrame()
			.pointAt(util.vector()
				.topOf(STATION))
			.placeNearTarget();
		scene.idle(100);

		// --- the last delivery of the day ------------------------------------------------

		scene.overlay()
			.showText(WALK_TO_OUTPUT + 40)
			.text("Whatever is already in their hands is delivered first — nobody clocks off holding a stack")
			.attachKeyFrame()
			.pointAt(outputTop)
			.placeNearTarget();
		scene.addInstruction(new WalkInstruction(worker, BESIDE_INPUT, BESIDE_OUTPUT, WALK_TO_OUTPUT));
		scene.idle(WALK_TO_OUTPUT + 5);

		dress(scene, worker, hat, ItemStack.EMPTY);
		// From the west, which is the side the worker is standing on.
		scene.world()
			.createItemOnBeltLike(OUTPUT_DEPOT, Direction.WEST, cargo);
		scene.effects()
			.indicateSuccess(OUTPUT_DEPOT);
		scene.idle(40);

		// --- off to bed ------------------------------------------------------------------

		scene.addKeyframe();
		scene.addInstruction(new WalkInstruction(worker, BESIDE_OUTPUT, BESIDE_BED, WALK_TO_BED));
		scene.idle(WALK_TO_BED + 5);
		scene.world()
			.modifyEntity(worker, entity -> {
				if (entity instanceof LivingEntity sleeper)
					sleeper.startSleeping(BED_HEAD);
				// startSleeping moves the sleeper onto the bed through setPosToBed, which does not
				// take a position snapshot -- so without this the worker reads as stepping the
				// bedside-to-bed distance every tick for the rest of the night, and lies there
				// pedalling. A large step, too, so it is a sprint rather than a shuffle.
				WalkInstruction.settle(entity);
			});
		scene.idle(25);

		// --- saying where ----------------------------------------------------------------

		scene.overlay()
			.showOutline(PonderPalette.WHITE, BED_HEAD, bed, 120);
		scene.overlay()
			.showControls(bedTop, Pointing.DOWN, 40)
			.withItem(hat)
			.whileSneaking()
			.rightClick();
		scene.idle(7);
		scene.overlay()
			.showText(80)
			.text("Sneak and Right-Click a Bed while holding the Hat to say where that Worker sleeps")
			.attachKeyFrame()
			.pointAt(bedTop)
			.placeNearTarget();
		scene.idle(90);

		scene.overlay()
			.showText(80)
			.text("Given no Bed, they find an unclaimed one near their work — but only one they can walk to")
			.attachKeyFrame()
			.pointAt(bedTop)
			.placeNearTarget();
		scene.idle(90);

		// --- the night shift -------------------------------------------------------------

		scene.addKeyframe();
		ElementLink<EntityElement> enderman = scene.world()
			.createEntity(level -> {
				EnderMan tall = new EnderMan(EntityType.ENDERMAN, level);
				WalkInstruction.place(tall, BESIDE_INPUT, FACING_SOUTH);
				return tall;
			});
		dress(scene, enderman, hat, ItemStack.EMPTY);
		scene.idle(10);

		scene.overlay()
			.showText(110)
			.text("Endermen keep no hours — a line staffed with them runs through the night")
			.attachKeyFrame()
			.pointAt(BESIDE_INPUT.add(TALL_EYE))
			.placeNearTarget();
		scene.idle(25);

		scene.world()
			.createItemOnBeltLike(INPUT_DEPOT, Direction.NORTH, cargo);
		scene.idle(20);
		scene.world()
			.modifyBlockEntity(INPUT_DEPOT, DepotBlockEntity.class, depot -> depot.getBehaviour(DepotBehaviour.TYPE)
				.removeHeldItem());
		dress(scene, enderman, hat, cargo);
		scene.effects()
			.indicateSuccess(INPUT_DEPOT);
		scene.idle(25);

		// A hop rather than a walk: moveTo carries the old position with it, so this renders as
		// arriving rather than as sliding across the plate.
		scene.world()
			.modifyEntity(enderman, entity -> WalkInstruction.place(entity, BESIDE_OUTPUT, FACING_SOUTH));
		scene.idle(15);
		dress(scene, enderman, hat, ItemStack.EMPTY);
		scene.world()
			.createItemOnBeltLike(OUTPUT_DEPOT, Direction.WEST, cargo);
		scene.effects()
			.indicateSuccess(OUTPUT_DEPOT);
		scene.idle(40);

		// --- morning ---------------------------------------------------------------------

		scene.addKeyframe();
		scene.world()
			.modifyEntity(worker, entity -> {
				if (entity instanceof LivingEntity sleeper)
					sleeper.stopSleeping();
				WalkInstruction.place(entity, BESIDE_BED, FACING_SOUTH);
			});
		scene.idle(15);

		scene.overlay()
			.showText(80)
			.text("At first light they are up and back at work")
			.attachKeyFrame()
			.pointAt(BESIDE_BED.add(EYE))
			.placeNearTarget();
		scene.idle(20);
		scene.addInstruction(new WalkInstruction(worker, BESIDE_BED, BESIDE_INPUT, WALK_TO_WORK));
		scene.idle(WALK_TO_WORK + 40);

		scene.markAsFinished();
	}

	/**
	 * Puts the gear on a scene worker, or takes it off again — the same trick
	 * {@link WorkerStationScene} uses, writing straight to the {@code WorkerData} attachment the render layers read.
	 */
	private static void dress(CreateSceneBuilder scene, ElementLink<EntityElement> worker, ItemStack hat,
		ItemStack held) {
		scene.world()
			.modifyEntity(worker, entity -> Workers.getOrCreate(entity)
				.applyClientState(hat.copy(), held.copy()));
	}
}

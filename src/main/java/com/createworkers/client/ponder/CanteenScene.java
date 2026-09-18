package com.createworkers.client.ponder;

import com.createworkers.registry.CWBlocks;
import com.createworkers.registry.CWItems;
import com.createworkers.worker.Shift;
import com.createworkers.worker.Workers;
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
 * The fourth scene: what a Worker eats, where it comes from, and what it will sell you.
 *
 * <p>Both halves were shipped with <b>no in-game explanation whatever</b>, which is the failure this
 * project has already had once — the hiring scene went on teaching "right-click a villager with a
 * hat" for versions after Stations took that away, and nothing in the game contradicts a Ponder
 * page. A Worker that slows to a crawl and throws unhappy particles is the mod's most alarming
 * visible state, and until this scene it was also the least explained.
 *
 * <p>Food and trades share a page because they share a reason: both are the Worker being a
 * <em>villager</em> rather than a machine. One is what it needs off you, the other is what it will
 * do for you, and the Canteen is the block that settles the first.
 *
 * <p>Staged on {@code assets/createworkers/ponder/canteen.nbt} — the same yard as the three scenes
 * before it, with the Station a player has already seen and a Canteen beside the work. The positions
 * below are {@code tools/generate_ponder_structure.py}'s, restated; nothing ties the two together,
 * so move one and move the other.
 *
 * <p>Every string handed to {@code text} here is only a default for the lang generator's benefit.
 * Outside Ponder's editing mode the text shown comes from
 * {@code createworkers.ponder.canteen.text_<n>} in the lang file, numbered by the order the calls are
 * made below, and a missing key renders as the key. Renumber carefully — and mind the clock, because
 * a {@code showText(d)} is on screen until {@code d} further ticks of {@code idle} have passed.
 */
public class CanteenScene {

	private static final BlockPos INPUT_DEPOT = new BlockPos(1, 1, 1);
	private static final BlockPos OUTPUT_DEPOT = new BlockPos(5, 1, 5);
	private static final BlockPos STATION = new BlockPos(1, 1, 5);
	private static final BlockPos CANTEEN = new BlockPos(3, 1, 1);

	private static final Vec3 BESIDE_INPUT = new Vec3(2.5, 1, 1.5);
	private static final Vec3 BESIDE_OUTPUT = new Vec3(4.5, 1, 5.5);
	/** In front of the trough, which is where a Worker happens to pass rather than somewhere it goes. */
	private static final Vec3 BESIDE_CANTEEN = new Vec3(3.5, 1, 2.5);

	private static final int WALK_TO_OUTPUT = 45;
	private static final int WALK_PAST_CANTEEN = 30;

	/** Head height, for pointing at a worker rather than at its feet. */
	private static final Vec3 EYE = new Vec3(0, 1.5, 0);
	private static final float FACING_SOUTH = 180;

	public static void feeding(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
		scene.title("canteen", "Feeding a Crew");
		scene.configureBasePlate(0, 0, 7);
		scene.showBasePlate();

		Selection fittings = util.select()
			.position(INPUT_DEPOT)
			.add(util.select()
				.position(OUTPUT_DEPOT))
			.add(util.select()
				.position(STATION));
		Selection canteen = util.select()
			.position(CANTEEN);
		Vec3 canteenTop = util.vector()
			.topOf(CANTEEN);

		ItemStack hat = new ItemStack(CWItems.HARD_HAT.get());
		ItemStack cargo = new ItemStack(Items.COPPER_INGOT);
		ItemStack bread = new ItemStack(Items.BREAD);

		scene.idle(5);
		scene.world()
			.showSection(fittings, Direction.DOWN);
		scene.idle(15);

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
			.text("A Worker spends food for every tick it is on the clock, out of its own pockets")
			.attachKeyFrame()
			.pointAt(BESIDE_INPUT.add(EYE))
			.placeNearTarget();
		scene.idle(90);

		scene.addInstruction(new WalkInstruction(worker, BESIDE_INPUT, BESIDE_OUTPUT, WALK_TO_OUTPUT));
		scene.idle(WALK_TO_OUTPUT + 5);
		dress(scene, worker, hat, ItemStack.EMPTY);
		scene.world()
			.createItemOnBeltLike(OUTPUT_DEPOT, Direction.WEST, cargo);
		scene.idle(10);

		scene.overlay()
			.showText(90)
			.text("With nothing left to eat it keeps working — but at a crawl, and it says so")
			.attachKeyFrame()
			.pointAt(BESIDE_OUTPUT.add(EYE))
			.placeNearTarget();
		scene.idle(100);

		// --- the trough ------------------------------------------------------------------

		scene.addKeyframe();
		scene.world()
			.showSection(canteen, Direction.DOWN);
		scene.idle(15);

		scene.overlay()
			.showText(90)
			.text("A Canteen feeds every villager near it. Nobody walks to one — the food goes to them")
			.attachKeyFrame()
			.pointAt(canteenTop)
			.placeNearTarget();
		scene.idle(100);

		scene.addInstruction(new WalkInstruction(worker, BESIDE_OUTPUT, BESIDE_CANTEEN, WALK_PAST_CANTEEN));
		scene.idle(WALK_PAST_CANTEEN + 5);
		scene.effects()
			.indicateSuccess(CANTEEN);
		scene.idle(10);

		scene.overlay()
			.showText(90)
			.text("So put them where the work is: a crew that merely walks past one stays fed")
			.attachKeyFrame()
			.pointAt(canteenTop)
			.placeNearTarget();
		scene.idle(100);

		scene.overlay()
			.showOutline(PonderPalette.GREEN, CANTEEN, canteen, 80);
		scene.overlay()
			.showControls(canteenTop, Pointing.DOWN, 40)
			.withItem(bread);
		scene.idle(7);
		scene.overlay()
			.showText(90)
			.text("Bread, Carrots, Potatoes and Beetroot — what a Villager will actually eat, and nothing else")
			.attachKeyFrame()
			.pointAt(canteenTop)
			.placeNearTarget();
		scene.idle(100);

		scene.overlay()
			.showText(90)
			.text("Its top is its stock and a bar on each side is the level, so an empty trough is obvious")
			.attachKeyFrame()
			.pointAt(canteenTop)
			.placeNearTarget();
		scene.idle(100);

		// --- and what it will sell you ---------------------------------------------------

		scene.addKeyframe();
		scene.overlay()
			.showControls(BESIDE_CANTEEN.add(EYE), Pointing.DOWN, 40)
			.rightClick();
		scene.idle(7);
		scene.overlay()
			.showText(90)
			.text("Off the clock a Worker trades like any Villager, and the work counts towards its level")
			.attachKeyFrame()
			.pointAt(BESIDE_CANTEEN.add(EYE))
			.placeNearTarget();
		scene.idle(100);

		scene.overlay()
			.showText(100)
			.text("It buys what a line makes — alloy, shafts, cogs, casings — and sells machines back")
			.attachKeyFrame()
			.pointAt(BESIDE_CANTEEN.add(EYE))
			.placeNearTarget();
		scene.idle(110);
	}

	private static void dress(CreateSceneBuilder scene, ElementLink<EntityElement> worker, ItemStack hat,
		ItemStack held) {
		scene.world()
			.modifyEntity(worker, entity -> Workers.getOrCreate(entity)
				.applyClientState(hat.copy(), held.copy(), Shift.DAY));
	}
}

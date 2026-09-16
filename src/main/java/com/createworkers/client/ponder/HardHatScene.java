package com.createworkers.client.ponder;

import com.createworkers.registry.CWItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * The first of the hard hat's Ponder scenes: what a hat is and how it is programmed.
 *
 * <p>It stops there on purpose. Hiring used to be the back half of this scene — right-click a
 * villager with the hat — and that is no longer how a villager is put to work: a Worker Station
 * does it, which is the scene after this one. A Ponder page that teaches a mechanic the mod does
 * not have is worse than no page at all, because a player has no way to tell which of the two is
 * wrong.
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

	public static void programming(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
		scene.title("hard_hat", "Programming a Hard Hat");
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

		scene.overlay()
			.showText(70)
			.text("A programmed Hat is a job. The next page hands one to somebody")
			.attachKeyFrame()
			.pointAt(middle)
			.placeNearTarget();
		scene.idle(80);

		scene.markAsFinished();
	}
}

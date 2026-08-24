package com.createworkers.client.ponder;

import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.EntityElement;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.instruction.TickingInstruction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a scene entity in a straight line over a fixed number of ticks.
 *
 * <p>Ponder can move a world section, a minecart and a parrot, but it has no instruction for
 * moving an entity — Create's scenes only ever spawn item entities, which fall and slide about
 * under their own steam. A worker is the whole subject of this scene, so it has to be driven by
 * hand: a ponder level reports itself as client-side, and neither the villager brain nor
 * {@code Mob.serverAiStep} runs there, so nothing else is going to move it.
 *
 * <p>Only the position is set. The facing and the leg swing fall out of it, because
 * {@code LivingEntity.tick} derives both from how far the entity has moved since {@code xo/yo/zo}
 * — see the note in {@link #tick}.
 */
public class WalkInstruction extends TickingInstruction {

	private final ElementLink<EntityElement> worker;
	private final Vec3 from;
	private final Vec3 to;

	public WalkInstruction(ElementLink<EntityElement> worker, Vec3 from, Vec3 to, int ticks) {
		super(false, ticks);
		this.worker = worker;
		this.from = from;
		this.to = to;
	}

	/**
	 * Turns the worker to face where it is about to go.
	 *
	 * <p>Vanilla will not do this part on its own. {@code LivingEntity.tick} compares the
	 * direction of travel against {@code getYRot()}, and walks the body <em>backwards</em> when
	 * the two disagree by more than 95 degrees — which is right for a mob being shoved around,
	 * and moonwalking for one that has decided to go somewhere.
	 */
	@Override
	protected void firstTick(PonderScene scene) {
		float bearing = bearing();
		scene.runWith(worker, element -> element.ifPresent(entity -> {
			entity.setYRot(bearing);
			entity.setYHeadRot(bearing);
			entity.setYBodyRot(bearing);
		}));
	}

	@Override
	public void tick(PonderScene scene) {
		super.tick(scene);

		Vec3 position = from.lerp(to, (totalTicks - remainingTicks) / (float) totalTicks);
		scene.runWith(worker, element -> element.ifPresent(entity -> {
			// Both the body's facing and the leg swing come out of the distance between the
			// entity's position and xo/yo/zo, and the only thing that refreshes those is
			// setOldPosAndRot, which a ponder level never calls -- it snapshots xOld/yOld/zOld
			// for the render interpolation and ticks the entity. Left alone they stay wherever
			// the worker was spawned, so the measured step grows the whole way across the plate
			// and the legs run flat out from the second stride. Take the snapshot here, just
			// before the move, so each tick's step is measured as one tick's step.
			entity.xo = entity.getX();
			entity.yo = entity.getY();
			entity.zo = entity.getZ();
			entity.setPos(position);
			// The plate is under the worker's feet, but the ponder level's own tick applies
			// gravity through travel(), and an entity that believes it is falling does not
			// animate a walk.
			entity.setOnGround(true);
		}));
	}

	private float bearing() {
		return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90;
	}

	/** Puts the worker down facing the way it would have arrived. */
	public static void place(Entity entity, Vec3 position, float yRot) {
		entity.moveTo(position.x, position.y, position.z, yRot, 0);
		entity.setYHeadRot(yRot);
		entity.setYBodyRot(yRot);
		entity.setOnGround(true);
	}
}

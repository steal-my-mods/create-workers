package com.createworkers.worker;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Endermen blink between targets rather than walking.
 *
 * <p>Two things keep that from being a strictly better Mechanical Arm. A teleport is paced by
 * {@code teleportCooldown}, so a round trip costs real time rather than happening the instant a job
 * appears; and a single hop reaches only {@code teleportRange}, so a long haul is made in several
 * hops and a worker moving items across a base is visibly slower than one working a tight cluster.
 *
 * <p>They will also only land somewhere they can stand without being hurt — no water, no rain
 * overhead, nothing burning — and if there is nowhere safe near a target they wait instead of
 * teleporting into harm.
 *
 * <p>An enderman also teleports on its own account, which swamps all of that; those teleports are
 * vetoed for anyone on the clock in {@code WorkerEvents.onEnderTeleport}, so the hops here are the
 * only ones a worker makes.
 *
 * <p>One instance per worker: the cooldown is per-enderman state.
 */
public class TeleportLocomotion implements WorkerLocomotion {

	/** How far around a target to look for a footing. */
	private static final int TARGET_SEARCH_RADIUS = 2;
	/** Wider, because a mid-journey waypoint is just a point in space and may well be inside terrain. */
	private static final int WAYPOINT_SEARCH_RADIUS = 4;
	private static final int VERTICAL_SLACK = 3;
	/** Deeper, because a waypoint hangs wherever the straight line put it and the ground may be well below. */
	private static final int WAYPOINT_VERTICAL_SLACK = 6;
	/** Fractions of a full hop to try, in order, when the straight-line waypoint yields nothing. */
	private static final double[] HOP_FRACTIONS = { 1.0D, 0.5D };
	/** How long to wait before scanning again after finding nowhere to land. */
	private static final int BLOCKED_RETRY_TICKS = 20;

	private int cooldown;

	@Override
	public void approach(Mob mob, WorkerTarget target) {
		if (cooldown > 0) {
			cooldown--;
			return;
		}

		BlockPos landing = chooseLanding(mob, target.getPos());
		if (landing == null) {
			// Nowhere safe to stand. Wait rather than rescanning every tick or taking damage.
			cooldown = BLOCKED_RETRY_TICKS;
			return;
		}

		double fromX = mob.getX();
		double fromY = mob.getY();
		double fromZ = mob.getZ();

		if (!mob.randomTeleport(landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D, true)) {
			cooldown = BLOCKED_RETRY_TICKS;
			return;
		}

		cooldown = CWConfig.TELEPORT_COOLDOWN.get();
		mob.getLookControl()
			.setLookAt(Vec3.atCenterOf(target.getPos()));

		// Vanilla plays this at both ends of an enderman's teleport.
		Level level = mob.level();
		level.playSound(null, fromX, fromY, fromZ, SoundEvents.ENDERMAN_TELEPORT, mob.getSoundSource(), 1.0F, 1.0F);
		mob.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
	}

	@Override
	public boolean canReach(Mob mob, WorkerTarget target) {
		double reach = CWConfig.REACH_DISTANCE.get();
		return mob.distanceToSqr(Vec3.atCenterOf(target.getPos())) <= reach * reach;
	}

	@Override
	public void tickEmployed(Mob mob) {
		// A worker on the clock is not interested in picking fights.
		if (mob instanceof EnderMan enderman) {
			enderman.setTarget(null);
			enderman.setRemainingPersistentAngerTime(0);
			enderman.setPersistentAngerTarget(null);
		}
	}

	/**
	 * Picks where to blink to next: straight to the target if it is within one hop, otherwise as far
	 * along the line towards it as a hop allows.
	 *
	 * <p>If the full-length waypoint has no footing worth taking, a shorter hop along the same line
	 * is tried, so a worker can feel its way past something in the way rather than sitting out its
	 * cooldown rescanning the same unusable spot until the job goal gives up on the target.
	 */
	@Nullable
	private static BlockPos chooseLanding(Mob mob, BlockPos targetPos) {
		int range = CWConfig.TELEPORT_RANGE.get();
		if (mob.blockPosition()
			.closerThan(targetPos, range))
			return findLandingSpot(mob, targetPos, TARGET_SEARCH_RADIUS);

		Vec3 from = mob.position();
		Vec3 towards = Vec3.atCenterOf(targetPos)
			.subtract(from);
		if (towards.lengthSqr() < 1.0E-4D)
			return null;
		Vec3 heading = towards.normalize();

		for (double fraction : HOP_FRACTIONS) {
			BlockPos waypoint = BlockPos.containing(from.add(heading.scale(range * fraction)));
			BlockPos spot = findWaypointSpot(mob, waypoint, targetPos);
			if (spot != null)
				return spot;
		}
		return null;
	}

	/**
	 * Finds the footing near {@code waypoint} that leaves the worker closest to {@code targetPos},
	 * and takes it only if it is closer than where the worker stands now.
	 *
	 * <p>Scoring against the target rather than against the waypoint is what makes a long haul look
	 * like a journey. A waypoint is a point in mid-air on the line to the target, so the footing
	 * nearest to <em>it</em> is as likely to be off to one side, or back the way the worker came, as
	 * it is to be on the way — and a run of those reads as an enderman blinking about at random
	 * rather than travelling. Insisting on progress also rules out the hop that lands where it
	 * started and then repeats for as long as the job goal will let it.
	 */
	@Nullable
	public static BlockPos findWaypointSpot(Mob mob, BlockPos waypoint, BlockPos targetPos) {
		Level level = mob.level();
		BlockPos best = null;
		// Seeded with the distance already covered, so anything accepted is strictly an improvement.
		double bestDistance = mob.blockPosition()
			.distSqr(targetPos);

		for (int dx = -WAYPOINT_SEARCH_RADIUS; dx <= WAYPOINT_SEARCH_RADIUS; dx++) {
			for (int dz = -WAYPOINT_SEARCH_RADIUS; dz <= WAYPOINT_SEARCH_RADIUS; dz++) {
				for (int dy = -WAYPOINT_VERTICAL_SLACK; dy <= WAYPOINT_VERTICAL_SLACK; dy++) {
					BlockPos candidate = waypoint.offset(dx, dy, dz);
					if (!isSafeStandingSpot(mob, level, candidate))
						continue;
					double distance = candidate.distSqr(targetPos);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = candidate.immutable();
					}
				}
			}
		}
		return best;
	}

	/**
	 * Finds the safe standing spot nearest {@code center}. The centre itself is a fair candidate —
	 * when it is the target block it fails the check anyway, being solid.
	 */
	@Nullable
	public static BlockPos findLandingSpot(Mob mob, BlockPos center, int radius) {
		Level level = mob.level();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -VERTICAL_SLACK; dy <= VERTICAL_SLACK; dy++) {
					BlockPos candidate = center.offset(dx, dy, dz);
					if (!isSafeStandingSpot(mob, level, candidate))
						continue;
					double distance = candidate.distSqr(center);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = candidate.immutable();
					}
				}
			}
		}
		return best;
	}

	private static boolean isSafeStandingSpot(Mob mob, Level level, BlockPos pos) {
		if (!level.isLoaded(pos))
			return false;

		BlockState floor = level.getBlockState(pos.below());
		if (!floor.isFaceSturdy(level, pos.below(), Direction.UP))
			return false;

		int height = Math.max(1, Mth.ceil(mob.getBbHeight()));
		for (int i = 0; i < height; i++) {
			BlockPos check = pos.above(i);
			BlockState state = level.getBlockState(check);
			if (!state.getCollisionShape(level, check)
				.isEmpty())
				return false;
			FluidState fluid = state.getFluidState();
			if (!fluid.isEmpty())
				return false;
			if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CAMPFIRE)
				|| state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.MAGMA_BLOCK))
				return false;
		}

		// Rain burns endermen, so refuse anywhere the sky is falling on.
		return !level.isRainingAt(pos);
	}
}

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
 * <p>One instance per worker: the cooldown is per-enderman state.
 */
public class TeleportLocomotion implements WorkerLocomotion {

	/** How far around a target to look for a footing. */
	private static final int TARGET_SEARCH_RADIUS = 2;
	/** Wider, because a mid-journey waypoint is just a point in space and may well be inside terrain. */
	private static final int WAYPOINT_SEARCH_RADIUS = 4;
	private static final int VERTICAL_SLACK = 3;
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

		Vec3 waypoint = from.add(towards.normalize()
			.scale(range));
		return findLandingSpot(mob, BlockPos.containing(waypoint), WAYPOINT_SEARCH_RADIUS);
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

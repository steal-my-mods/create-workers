package com.createworkers.worker;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Endermen blink straight to the next inventory rather than walking.
 *
 * <p>They will only land somewhere they can actually stand and that will not hurt them —
 * no water, no rain overhead — and if there is nowhere safe next to a target they simply
 * stop rather than teleporting into harm.
 */
public class TeleportLocomotion implements WorkerLocomotion {

	public static final TeleportLocomotion INSTANCE = new TeleportLocomotion();

	/** Horizontal offsets tried around a target, nearest first. */
	private static final int SEARCH_RADIUS = 2;
	private static final int VERTICAL_SLACK = 2;

	@Override
	public void approach(Mob mob, WorkerTarget target) {
		BlockPos landing = findLandingSpot(mob, target.getPos());
		if (landing == null) {
			// Nowhere safe to stand next to this inventory -- wait rather than take damage.
			return;
		}

		boolean teleported = mob.randomTeleport(landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D, true);
		if (teleported)
			mob.getLookControl()
				.setLookAt(Vec3.atCenterOf(target.getPos()));
	}

	@Override
	public boolean canReach(Mob mob, WorkerTarget target) {
		double reach = CWConfig.REACH_DISTANCE.get();
		return mob.distanceToSqr(Vec3.atCenterOf(target.getPos())) <= reach * reach;
	}

	@Override
	public void onCargoChanged(Mob mob, ItemStack cargo) {
		if (!(mob instanceof EnderMan enderman))
			return;
		// Vanilla already renders whatever an enderman is holding, so reuse it for the cargo.
		if (cargo.getItem() instanceof BlockItem blockItem)
			enderman.setCarriedBlock(blockItem.getBlock()
				.defaultBlockState());
		else
			enderman.setCarriedBlock(null);
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
	 * Finds a block a worker can stand on next to {@code target} without being hurt by water
	 * or rain, preferring spots closest to the inventory.
	 */
	@Nullable
	public static BlockPos findLandingSpot(Mob mob, BlockPos target) {
		Level level = mob.level();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;

		for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
			for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
				for (int dy = -VERTICAL_SLACK; dy <= VERTICAL_SLACK; dy++) {
					if (dx == 0 && dz == 0 && dy == 0)
						continue;
					BlockPos candidate = target.offset(dx, dy, dz);
					if (!isSafeStandingSpot(mob, level, candidate))
						continue;
					double distance = candidate.distSqr(target);
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

		// Enough headroom for the mob, all of it free of blocks and fluids.
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

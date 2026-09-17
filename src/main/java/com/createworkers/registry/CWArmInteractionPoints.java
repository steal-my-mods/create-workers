package com.createworkers.registry;

import com.createworkers.CreateWorkers;
import com.createworkers.block.CanteenBlock;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes.DepositOnlyArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The blocks of this mod's own that a Mechanical Arm — and therefore a worker — can use.
 *
 * <p>Only the Canteen. Registering the type is what makes the block a legal destination on a hard
 * hat, which is the point: a line that hauls bread into the canteen that feeds the workers running
 * the line is then something a player builds out of parts they already have, and this mod has to add
 * nothing to allow it.
 *
 * <p><b>Deposit only</b>, and that is a design decision rather than a shortcut.
 * {@code DepositOnlyArmInteractionPoint} refuses to extract and refuses to cycle out of
 * {@code DEPOSIT}, so a canteen can be filled by an arm or a worker and never emptied by one. Two
 * reasons. A trough that machines could drain is storage with a food filter, and the loop it invites —
 * haul bread in, haul the same bread out — is a worker doing nothing at some expense. And the thing
 * that <em>does</em> take food out is a hungry villager eating, which is not an item transfer and
 * must not compete with one; a canteen a belt keeps emptying is a canteen that never feeds anybody,
 * which is the failure the block exists to prevent.
 *
 * <p>The Worker Station is deliberately absent. Its rack is reachable as an ordinary item handler, so
 * a funnel or a vanilla hopper can stock it with hats — but a worker able to name its own Station as
 * a target could hand a hat to the block that employs it, which is a job editing its own roster.
 */
public class CWArmInteractionPoints {

	public static final DeferredRegister<ArmInteractionPointType> REGISTER =
		DeferredRegister.create(CreateRegistries.ARM_INTERACTION_POINT_TYPE, CreateWorkers.ID);

	public static final DeferredHolder<ArmInteractionPointType, CanteenPointType> CANTEEN =
		REGISTER.register("canteen", CanteenPointType::new);

	/** Matches the Canteen and nothing else; the point itself is Create's own deposit-only one. */
	public static class CanteenPointType extends ArmInteractionPointType {

		@Override
		public boolean canCreatePoint(Level level, BlockPos pos, BlockState state) {
			return state.getBlock() instanceof CanteenBlock;
		}

		@Override
		public ArmInteractionPoint createPoint(Level level, BlockPos pos, BlockState state) {
			return new DepositOnlyArmInteractionPoint(this, level, pos, state);
		}
	}
}

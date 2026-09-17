package com.createworkers.registry;

import com.createworkers.block.WorkerStationBlockEntity;

import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * What of this mod's blocks other machines can reach into.
 *
 * <p>Only the Worker Station, and only its rack of hats. A station is an inventory in the ordinary
 * sense — a funnel or an arm can stock it with programmed hats, and taking one out is firing that
 * job's crew — which is the obvious thing to allow in a Create addon and would be the odd thing to
 * refuse.
 */
public class CWCapabilities {

	public static void register(RegisterCapabilitiesEvent event) {
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CWBlockEntities.WORKER_STATION.get(),
			(station, side) -> station.rack());
		// The Canteen, on every side. Filling a trough of food by funnel, chute, belt or arm is what
		// turns feeding a crew into an ordinary automation problem, which is the entire reason the
		// block is an inventory rather than a counter. Its own handler refuses anything that is not
		// food, and it hands out an insert-only view -- a funnel on the side of a canteen was pulling
		// the bread straight back out, which is right for a chest and wrong for a trough whose whole
		// purpose is to be eaten from. See CanteenBlockEntity.intake.
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, CWBlockEntities.CANTEEN.get(),
			(canteen, side) -> canteen.intake());
	}
}

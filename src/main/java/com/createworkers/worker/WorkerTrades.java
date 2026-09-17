package com.createworkers.worker;

import com.createworkers.CreateWorkers;
import com.createworkers.registry.CWProfessions;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;

import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

/**
 * What a Worker will sell you, and what it will buy.
 *
 * <p>The rule is <b>nothing that skips a gate</b>. A shaft is an andesite alloy and a plank; a cog is
 * the same; andesite alloy is iron and andesite in a mixer or on the floor. They are all available in
 * the first hour, gated behind nothing, and making them by hand in quantity is precisely the tedium
 * Create wants you to automate past — so a labourer selling them costs a player nothing they had not
 * already been given. What a labourer has to sell is the product of labour.
 *
 * <p>What is deliberately absent is everything a factory is the <em>answer</em> to: precision
 * mechanisms, brass casings, sturdy sheets. Being able to buy those is being able to buy past the
 * game. <b>And no hard hats.</b> The hat is this mod's own gate, and the rule applies to us first: a
 * player who can buy one has bought past the item the mod is about.
 *
 * <p>Buying is the other half and matters more than it looks. A Worker that buys wheat, andesite and
 * food gives a player somewhere to put the output of the farm that feeds the crew, which is the loop
 * this whole phase is built around — and it is what makes emeralds reach a base that has no villager
 * trading hall.
 */
@EventBusSubscriber(modid = CreateWorkers.ID)
public class WorkerTrades {

	/**
	 * Registered against the profession, which is the supported way in.
	 *
	 * <p>{@code VillagerTrades.TRADES} is a plain mutable map keyed by profession and NeoForge fires
	 * this event over it, so nothing here has to reach into vanilla's statics.
	 */
	@SubscribeEvent
	public static void register(VillagerTradesEvent event) {
		if (!event.getType()
			.equals(CWProfessions.WORKER.get()))
			return;

		// Level 1: the things a labourer has lying about.
		event.getTrades()
			.get(1)
			.add(sellFor(Items.WHEAT, 20, 1));
		event.getTrades()
			.get(1)
			.add(buyWith(AllItems.ANDESITE_ALLOY.asStack(8), 1));

		// Level 2: shafts and cogs, which is the trade a player actually wants from this villager.
		event.getTrades()
			.get(2)
			.add(sellFor(Items.ANDESITE, 24, 1));
		event.getTrades()
			.get(2)
			.add(buyWith(AllBlocks.SHAFT.asStack(16), 1));

		// Level 3: the same, in the quantity that makes a workshop worth visiting.
		event.getTrades()
			.get(3)
			.add(buyWith(AllBlocks.COGWHEEL.asStack(12), 1));
		event.getTrades()
			.get(3)
			.add(sellFor(Items.BREAD, 12, 1));

		// Level 4 and 5: nothing new to sell, only more of it. A Worker is not a route to better
		// things, it is a route to *more* of the things you can already make -- which is the whole of
		// the "nothing that skips a gate" rule expressed as a trade table.
		event.getTrades()
			.get(4)
			.add(buyWith(AllBlocks.LARGE_COGWHEEL.asStack(8), 1));
		event.getTrades()
			.get(5)
			.add(buyWith(AllItems.ANDESITE_ALLOY.asStack(24), 2));
	}

	/** The worker gives emeralds for raw material — what a player sells <em>to</em> it. */
	private static VillagerTrades.ItemListing sellFor(net.minecraft.world.item.Item wanted, int count, int emeralds) {
		return (trader, random) -> new MerchantOffer(new net.minecraft.world.item.trading.ItemCost(wanted, count),
			new ItemStack(Items.EMERALD, emeralds), 12, 2, 0.05F);
	}

	/** The worker gives goods for emeralds — what a player buys <em>from</em> it. */
	private static VillagerTrades.ItemListing buyWith(ItemStack goods, int emeralds) {
		return (trader, random) -> new MerchantOffer(
			new net.minecraft.world.item.trading.ItemCost(Items.EMERALD, emeralds), goods.copy(), 12, 5, 0.05F);
	}
}

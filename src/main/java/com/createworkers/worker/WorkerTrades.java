package com.createworkers.worker;

import java.util.ArrayList;
import java.util.List;

import com.createworkers.CreateWorkers;
import com.createworkers.registry.CWProfessions;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

/**
 * What a Worker sells and what it buys.
 *
 * <p><b>The worker buys upstream and sells downstream, and never both sides of one crafting
 * chain.</b> That is the rule the whole table is built on, and it is a safety property rather than a
 * theme: crafting only runs one way, so a worker that sells a material and buys something made from
 * that material is an emerald printer. The numbers are not close. Create's saw cuts <em>one</em>
 * Andesite Alloy into six Shafts and a Shaft plus a plank is a Cogwheel, so an alloy sold at any
 * price a player would pay comes back as six cogwheels; a Zinc Ingot mixes into nine alloy and so
 * reaches fifty-four. Selling zinc was in this table until that arithmetic was done.
 * {@code theTradeTableHasNoEmeraldLoop} is what settles it now, against the server's own recipes.
 *
 * <p>So the relationship is a supplier's. The worker <b>buys the basic output of a line</b> — alloy,
 * shafts, cogwheels, casings, pressed sheets, and at the top the components that are a genuine chore
 * to automate — and <b>sells assembled machines</b>, which craft into nothing it buys. That is also
 * the answer to where emeralds come from: a factory's own output is the income, which is the thing a
 * table that bought only raw wheat and andesite could never provide.
 *
 * <p>The second rule is <b>nothing that skips a gate</b>, where a gate is a material tier that
 * unlocks recipes — brass, electron tubes, precision mechanisms, sturdy sheets — and not a machine a
 * player could already have built. It binds the <em>sell</em> side only: buying a gated item is
 * always fine, because the player had to be past the gate to be holding one. The single relaxation is
 * Brass Ingots at level five, an expensive alternate path to a material rather than a way past the
 * tier's mechanics, and it clears the loop check with room to spare. <b>Never a Hard Hat</b>, because
 * the hat is this mod's own gate and the rule applies to us first.
 *
 * <p><b>Level is progression, not price.</b> Andesite kinetics at one, logistics at two, processing
 * machines at three, contraptions and fluids at four, the package network at five — so what a Worker
 * offers tracks what a player is building, and prices stay in a narrow band throughout instead of
 * climbing with the level. Experience per trade is vanilla's own ramp.
 *
 * <p>Vanilla shows a villager <b>two</b> randomly chosen listings per level, so a long list is not a
 * cap to work around — it is variety between one Worker and the next, and a reason to have several.
 * <b>{@code maxUses} rather than price is what meters the emerald income</b>: a purchase yields at
 * most its uses before the Worker has to restock, which it can only do twice a day.
 *
 * <p><b>There is deliberately no config for this.</b> Every Create modpack that retunes villager
 * trades does it with KubeJS, which hooks the same {@link VillagerTradesEvent} NeoForge fires here —
 * so a pack can already add to, replace or clear this table without our help, and a bespoke config
 * format would only serve someone who has the standard tool and is not using it.
 */
@EventBusSubscriber(modid = CreateWorkers.ID)
public class WorkerTrades {

	private static final String EMERALD = "minecraft:emerald";

	/**
	 * One shipped trade: the player hands over {@code cost} and receives {@code result}.
	 *
	 * <p>Items are <b>named rather than resolved</b>, so an id that moves between Create versions
	 * costs that one trade and a line in the log rather than taking a world down with it.
	 */
	public record Trade(int level, String cost, int costCount, String result, int resultCount, int maxUses, int xp) {

		/** The Worker sells: the player pays emeralds. */
		public boolean isSale() {
			return EMERALD.equals(cost);
		}

		/** The Worker buys: the player is paid in emeralds. */
		public boolean isPurchase() {
			return EMERALD.equals(result);
		}

		/** The side of this trade that is not emeralds. */
		public String goods() {
			return isSale() ? result : cost;
		}

		/** Emeralds per single item, which is the only form the two directions can be compared in. */
		public double emeraldsEach() {
			return isSale() ? (double) costCount / resultCount : (double) resultCount / costCount;
		}
	}

	private static final List<Trade> TABLE = build();

	/** The shipped table, in level order. */
	public static List<Trade> table() {
		return TABLE;
	}

	@SubscribeEvent
	public static void register(VillagerTradesEvent event) {
		if (!event.getType()
			.equals(CWProfessions.WORKER.get()))
			return;

		for (Trade trade : TABLE) {
			VillagerTrades.ItemListing listing = listing(trade);
			if (listing != null)
				event.getTrades()
					.get(trade.level())
					.add(listing);
		}
	}

	/**
	 * Turns one trade into a listing, or null if it names an item this installation does not have.
	 *
	 * <p>Skipped with a warning rather than thrown: an id that has moved between Create versions
	 * should cost one line of a shop, not a world.
	 */
	public static VillagerTrades.ItemListing listing(Trade trade) {
		Item cost = item(trade.cost());
		Item result = item(trade.result());
		if (cost == null || result == null) {
			CreateWorkers.LOGGER.warn("Ignoring worker trade for {}: no such item {}",
				trade.goods(), cost == null ? trade.cost() : trade.result());
			return null;
		}
		ItemCost price = new ItemCost(cost, trade.costCount());
		ItemStack goods = new ItemStack(result, trade.resultCount());
		return (trader, random) -> new MerchantOffer(price, goods.copy(), trade.maxUses(), trade.xp(), 0.05F);
	}

	/** Null when nothing is registered under that id, which is a case every caller has to handle. */
	public static Item item(String id) {
		ResourceLocation key = ResourceLocation.tryParse(id.trim());
		return key == null ? null
			: BuiltInRegistries.ITEM.getOptional(key)
				.orElse(null);
	}

	/** Vanilla's ramp. Levels are reached at 10, 70, 150 and 250 experience. */
	private static int xp(int level) {
		return switch (level) {
			case 1 -> 2;
			case 2 -> 10;
			case 3 -> 20;
			default -> 30;
		};
	}

	private static List<Trade> build() {
		List<Trade> t = new ArrayList<>();

		// ---- Level 1: andesite age -- raw kinetics in, the first power out ----------------------
		// The buy side starts where a first factory does: alloy and shafts are what an andesite farm
		// actually produces, and a player with stacks of them has nothing else to do with them.
		buy(t, 1, "minecraft:wheat", 20, 1, 16);
		buy(t, 1, "minecraft:andesite", 24, 1, 16);
		buy(t, 1, "create:andesite_alloy", 16, 1, 16);
		buy(t, 1, "create:shaft", 24, 1, 16);

		sell(t, 1, 2, "create:water_wheel", 1, 8);
		sell(t, 1, 3, "create:large_water_wheel", 1, 8);
		sell(t, 1, 2, "create:windmill_bearing", 1, 8);
		sell(t, 1, 1, "create:sail_frame", 6, 12);
		sell(t, 1, 1, "create:white_sail", 6, 12);
		sell(t, 1, 1, "create:gearbox", 2, 12);
		sell(t, 1, 1, "create:gearshift", 2, 12);
		sell(t, 1, 1, "create:clutch", 2, 12);
		sell(t, 1, 1, "create:encased_chain_drive", 2, 12);
		sell(t, 1, 1, "create:hand_crank", 2, 12);
		// One-time tools. Nobody buys a second Wrench, so the price is a convenience fee and not a wall.
		sell(t, 1, 3, "create:wrench", 1, 4);
		sell(t, 1, 3, "create:goggles", 1, 4);
		sell(t, 1, 1, "create:sand_paper", 3, 12);
		sell(t, 1, 1, "create:clipboard", 1, 8);

		// ---- Level 2: logistics ----------------------------------------------------------------
		buy(t, 2, "create:cogwheel", 16, 1, 16);
		buy(t, 2, "create:andesite_casing", 10, 1, 16);
		buy(t, 2, "minecraft:copper_ingot", 16, 1, 16);

		sell(t, 2, 1, "create:andesite_funnel", 4, 12);
		sell(t, 2, 1, "create:andesite_tunnel", 4, 12);
		sell(t, 2, 1, "create:chute", 4, 12);
		sell(t, 2, 1, "create:depot", 3, 12);
		// Priced for the way vaults are actually built, which is by the wall rather than one at a time.
		sell(t, 2, 1, "create:item_vault", 4, 12);
		sell(t, 2, 1, "create:belt_connector", 6, 12);
		sell(t, 2, 2, "create:weighted_ejector", 1, 8);
		sell(t, 2, 1, "create:item_hatch", 3, 12);
		sell(t, 2, 1, "create:filter", 3, 12);
		sell(t, 2, 2, "create:attribute_filter", 1, 8);
		sell(t, 2, 1, "create:cardboard", 6, 12);

		// ---- Level 3: processing machines -------------------------------------------------------
		buy(t, 3, "create:large_cogwheel", 12, 1, 16);
		buy(t, 3, "create:copper_sheet", 12, 1, 16);
		buy(t, 3, "minecraft:iron_ingot", 10, 1, 16);
		// Create's own foods are a processing line's output like any other, and a Worker buying the
		// things a Canteen is stocked with is the right way round.
		buy(t, 3, "create:bar_of_chocolate", 12, 1, 16);
		buy(t, 3, "create:builders_tea", 12, 1, 16);
		buy(t, 3, "create:sweet_roll", 12, 1, 16);
		buy(t, 3, "create:honeyed_apple", 12, 1, 16);

		sell(t, 3, 2, "create:millstone", 1, 8);
		sell(t, 3, 2, "create:mechanical_press", 1, 8);
		sell(t, 3, 2, "create:encased_fan", 1, 8);
		sell(t, 3, 1, "create:basin", 2, 12);
		sell(t, 3, 2, "create:empty_blaze_burner", 1, 8);
		sell(t, 3, 3, "create:mechanical_mixer", 1, 8);
		sell(t, 3, 3, "create:mechanical_saw", 1, 8);
		sell(t, 3, 3, "create:mechanical_drill", 1, 8);
		sell(t, 3, 3, "create:mechanical_harvester", 1, 8);
		sell(t, 3, 3, "create:mechanical_plough", 1, 8);
		sell(t, 3, 1, "create:speedometer", 1, 8);
		sell(t, 3, 1, "create:stressometer", 1, 8);

		// ---- Level 4: contraptions, fluids, pneumatics ------------------------------------------
		buy(t, 4, "create:iron_sheet", 10, 1, 16);
		buy(t, 4, "create:golden_sheet", 8, 1, 16);
		buy(t, 4, "minecraft:gold_ingot", 4, 1, 16);

		sell(t, 4, 3, "create:mechanical_piston", 1, 8);
		sell(t, 4, 1, "create:piston_extension_pole", 6, 12);
		sell(t, 4, 3, "create:rope_pulley", 1, 8);
		sell(t, 4, 1, "create:metal_girder", 6, 12);
		sell(t, 4, 3, "create:cart_assembler", 1, 8);
		sell(t, 4, 1, "create:super_glue", 4, 12);
		// Here rather than lower down because what a player wants one for is an elevator, which wants
		// the Rope Pulley beside it and the Drills to dig the shaft out first.
		sell(t, 4, 1, "create:redstone_contact", 3, 12);
		sell(t, 4, 1, "create:powered_latch", 2, 12);
		sell(t, 4, 1, "create:powered_toggle_latch", 2, 12);
		sell(t, 4, 1, "create:redstone_link", 2, 12);
		sell(t, 4, 1, "create:fluid_pipe", 8, 12);
		sell(t, 4, 1, "create:fluid_tank", 2, 12);
		sell(t, 4, 1, "create:fluid_valve", 2, 12);
		sell(t, 4, 3, "create:mechanical_pump", 1, 8);
		sell(t, 4, 3, "create:spout", 1, 8);
		sell(t, 4, 3, "create:item_drain", 1, 8);
		sell(t, 4, 4, "create:copper_backtank", 1, 4);
		sell(t, 4, 4, "create:copper_diving_helmet", 1, 4);
		sell(t, 4, 4, "create:copper_diving_boots", 1, 4);

		// ---- Level 5: the package network, and the one material we sell -------------------------
		// The buy side here is ranked by how much machinery the chain needs, which is not the same as
		// how gated the item is and is worth checking against the recipes rather than guessing. A
		// Brass Casing is one item application -- a log and an ingot -- and is the only cheap one of
		// the four. An Electron Tube wants rose quartz mixed, polished by a Sandpaper that wears out,
		// and then crafted with an Iron Sheet. A Sturdy Sheet is a SIX-step sequenced assembly over
		// obsidian, and a Precision Mechanism a five-step one over gold that eats both cogwheels. The
		// last two sit together however different their materials look.
		buy(t, 5, "create:brass_casing", 4, 1, 12);
		buy(t, 5, "create:electron_tube", 1, 1, 12);
		buy(t, 5, "create:sturdy_sheet", 1, 2, 12);
		buy(t, 5, "create:precision_mechanism", 1, 3, 12);

		sell(t, 5, 6, "create:packager", 1, 8);
		sell(t, 5, 6, "create:repackager", 1, 8);
		sell(t, 5, 2, "create:stock_link", 1, 8);
		sell(t, 5, 8, "create:stock_ticker", 1, 4);
		sell(t, 5, 4, "create:package_frogport", 1, 8);
		sell(t, 5, 4, "create:redstone_requester", 1, 8);
		sell(t, 5, 4, "create:portable_storage_interface", 1, 8);
		sell(t, 5, 1, "create:chain_conveyor", 2, 12);
		// The gate rule's one relaxation, and materials only: two hundred and fifty experience of
		// trading is more work than a zinc mine, and a player still builds their own machines.
		sell(t, 5, 3, "create:brass_ingot", 4, 12);

		return List.copyOf(t);
	}

	/** The Worker sells: the player pays {@code emeralds} for {@code count} of the item. */
	private static void sell(List<Trade> into, int level, int emeralds, String item, int count, int uses) {
		into.add(new Trade(level, EMERALD, emeralds, item, count, uses, xp(level)));
	}

	/** The Worker buys: the player hands over {@code count} of the item for {@code emeralds}. */
	private static void buy(List<Trade> into, int level, String item, int count, int emeralds, int uses) {
		into.add(new Trade(level, item, count, EMERALD, emeralds, uses, xp(level)));
	}
}

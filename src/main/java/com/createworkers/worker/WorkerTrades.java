package com.createworkers.worker;

import java.util.List;

import com.createworkers.CWConfig;
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
 * What a Worker will sell you, and what it will buy — read from the config rather than written here.
 *
 * <p>The rule the shipped list follows is <b>nothing that skips a gate</b>, where a gate is a
 * material <em>tier</em> that unlocks recipes — brass, electron tubes, precision mechanisms, sturdy
 * sheets — and not a machine you could already have built. Checked against Create's own recipes
 * rather than assumed: everything in the default list is andesite age, and a Mechanical Press or a
 * Drill is on the safe side of that line while a Deployer (electron tube) and a Mechanical Arm
 * (brass, precision mechanism) are not.
 *
 * <p>What that buys a player is <b>the product of labour</b>: things they could make, in the
 * quantities that make making them by hand the tedium Create wants automated past. What it must never
 * buy them is a tier they have not reached.
 *
 * <p><b>The list is configuration, and that is the point.</b> There are several hundred candidate
 * Create items and no list chosen here would suit every pack — so the shipped one is a starting
 * point, and a server can add items from any mod, drop ones it dislikes and move prices without
 * touching code. Notably the mod does <em>not</em> police the config for gate-skipping: the rule
 * above is what this project holds <em>itself</em> to, and a server owner who wants to sell brass
 * casings on their own server is not doing anything wrong.
 *
 * <p>Vanilla shows a villager <b>two</b> randomly chosen listings per level, so a long list is not a
 * cap to work around — it is variety between one Worker and the next, and a reason to have several.
 */
@EventBusSubscriber(modid = CreateWorkers.ID)
public class WorkerTrades {

	/** Fields in a configured line, in order. */
	private static final int FIELDS = 7;

	@SubscribeEvent
	public static void register(VillagerTradesEvent event) {
		if (!event.getType()
			.equals(CWProfessions.WORKER.get()))
			return;

		// Belt and braces. The list lives in its own COMMON spec precisely so it is loaded by the time
		// this fires, but a world must never fail to load over a trade list -- so if it somehow is not
		// there, ship the defaults rather than throw.
		List<? extends String> lines = CWConfig.TRADE_SPEC.isLoaded() ? CWConfig.TRADES.get() : defaults();
		for (String line : lines) {
			VillagerTrades.ItemListing listing = parse(line);
			if (listing != null)
				event.getTrades()
					.get(level(line))
					.add(listing);
		}
	}

	/**
	 * Turns one configured line into a listing, or null if it cannot be read.
	 *
	 * <p><b>A bad line is skipped with a warning, never thrown.</b> This is parsed on every world load
	 * from text a human edited, so a typo must cost that one trade and not the server — and an
	 * unrecognised item id is the expected case rather than an exotic one, because the obvious use of
	 * this config is naming items from a mod that may or may not be installed.
	 */
	public static VillagerTrades.ItemListing parse(String line) {
		String[] parts = line.split(";");
		if (parts.length != FIELDS) {
			warn(line, "expected " + FIELDS + " fields separated by ';', found " + parts.length);
			return null;
		}
		try {
			int level = Integer.parseInt(parts[0].trim());
			if (level < 1 || level > 5) {
				warn(line, "trade level must be 1 to 5");
				return null;
			}
			Item cost = item(parts[1]);
			int costCount = Integer.parseInt(parts[2].trim());
			Item result = item(parts[3]);
			int resultCount = Integer.parseInt(parts[4].trim());
			int maxUses = Integer.parseInt(parts[5].trim());
			int xp = Integer.parseInt(parts[6].trim());

			if (cost == null || result == null) {
				warn(line, "no such item: " + (cost == null ? parts[1] : parts[3])
					+ " (a mod that provides it may not be installed, which is fine)");
				return null;
			}
			if (costCount < 1 || resultCount < 1 || maxUses < 1) {
				warn(line, "counts and uses have to be at least one");
				return null;
			}

			ItemCost price = new ItemCost(cost, costCount);
			ItemStack goods = new ItemStack(result, resultCount);
			return (trader, random) -> new MerchantOffer(price, goods.copy(), maxUses, xp, 0.05F);
		} catch (NumberFormatException malformed) {
			warn(line, "a number would not parse: " + malformed.getMessage());
			return null;
		}
	}

	/** The level a line asks for, or 1 if it is unreadable — {@link #parse} has already refused it. */
	private static int level(String line) {
		try {
			return Integer.parseInt(line.split(";")[0].trim());
		} catch (RuntimeException unreadable) {
			return 1;
		}
	}

	private static Item item(String id) {
		ResourceLocation key = ResourceLocation.tryParse(id.trim());
		return key == null ? null : BuiltInRegistries.ITEM.getOptional(key)
			.orElse(null);
	}

	private static void warn(String line, String why) {
		CreateWorkers.LOGGER.warn("Ignoring worker trade \"{}\": {}", line, why);
	}

	/**
	 * The shipped list.
	 *
	 * <p>One format for both directions, because an emerald is just an item: name it as the cost and
	 * the player is buying, name it as the result and they are selling. Fields are
	 * {@code level;costItem;costCount;resultItem;resultCount;maxUses;xp}.
	 *
	 * <p>The shape of the list is deliberate. <b>Levels 1 and 2 are materials in bulk</b> — shafts,
	 * cogs, casings, the things a factory eats by the stack and which are pure tedium by hand. Large
	 * cogwheels sit beside small ones because they are the same tier and the same chore, not a reward.
	 * <b>Levels 3 to 5 are machines</b>, and split by how they are actually used: a Press, a Fan, a
	 * Mixer or a Millstone is a thing you want <em>one</em> of, while Drills, Saws and girders get
	 * built in groups for contraptions, so those come in fours.
	 */
	public static List<String> defaults() {
		return List.of(
			// Level 1 -- the raw trade, and the two chores every factory starts with.
			"1;minecraft:wheat;20;minecraft:emerald;1;16;2",
			"1;minecraft:emerald;1;create:andesite_alloy;8;12;2",
			"1;minecraft:emerald;1;create:andesite_casing;6;12;2",
			"1;minecraft:emerald;1;create:shaft;16;12;2",
			"1;minecraft:emerald;1;create:cogwheel;12;12;2",
			"1;minecraft:emerald;1;create:large_cogwheel;8;12;2",

			// Level 2 -- logistics by the handful, and somewhere to put it all.
			"2;minecraft:andesite;24;minecraft:emerald;1;16;5",
			"2;minecraft:emerald;1;create:andesite_funnel;4;12;5",
			"2;minecraft:emerald;1;create:chute;4;12;5",
			"2;minecraft:emerald;1;create:belt_connector;4;12;5",
			"2;minecraft:emerald;1;create:depot;2;12;5",
			"2;minecraft:emerald;2;create:item_vault;1;12;5",

			// Level 3 -- the machines you want exactly one of.
			"3;minecraft:emerald;3;create:mechanical_press;1;8;10",
			"3;minecraft:emerald;3;create:encased_fan;1;8;10",
			"3;minecraft:emerald;4;create:mechanical_mixer;1;8;10",
			"3;minecraft:emerald;3;create:millstone;1;8;10",
			"3;minecraft:emerald;2;create:basin;1;12;10",

			// Level 4 -- the ones that get built in groups, for contraptions.
			"4;minecraft:emerald;2;create:mechanical_drill;4;8;15",
			"4;minecraft:emerald;2;create:mechanical_saw;4;8;15",
			"4;minecraft:emerald;3;create:mechanical_piston;1;8;15",
			"4;minecraft:emerald;1;create:piston_extension_pole;8;12;15",
			"4;minecraft:emerald;2;create:metal_girder;8;12;15",

			// Level 5 -- the fiddly things, worth a trip to a levelled Worker.
			"5;minecraft:emerald;4;create:goggles;1;4;20",
			"5;minecraft:emerald;3;create:wrench;1;4;20",
			"5;minecraft:emerald;2;create:rose_quartz;8;8;20",
			"5;minecraft:emerald;5;create:copper_backtank;1;4;20",
			"5;minecraft:emerald;1;create:super_glue;4;12;20");
	}
}

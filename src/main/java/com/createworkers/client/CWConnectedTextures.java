package com.createworkers.client;

import java.util.Map;

import com.createworkers.registry.CWBlocks;
import com.simibubi.create.AllSpriteShifts;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.decoration.encasing.EncasedCTBehaviour;
import com.simibubi.create.foundation.block.connected.CTModel;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * Makes both blocks wear Create's andesite casing, and connect like it.
 *
 * <p><b>The casing is referenced, never copied.</b> The sprite lives in Create's jar and is resolved
 * at runtime by the id our models name, so nothing of Create's art is in our jar — which is the
 * whole difference between using it and redistributing it. The machinery here is Create's code,
 * which is MIT, the same licence the transfer algorithm in {@code WorkerData} is ported under.
 *
 * <p><b>Registering with {@link CreateClient#CASING_CONNECTIVITY} is what makes the connection
 * mutual, and nothing else does.</b> The obvious route — {@code SimpleCTBehaviour} — inherits
 * {@code ConnectedTextureBehaviour.connectsTo}, which is <em>block identity</em>: our Canteen would
 * connect to other Canteens and to nothing else, and against a real Andesite Casing block we would
 * drop our trim while it kept its own. That looks worse than not connecting at all, because a seam
 * with a border down one side of it reads as a texture fault rather than as a boundary.
 * {@link EncasedCTBehaviour} instead asks the casing registry about <em>both</em> blocks, and
 * Create's own casings and encased blocks ask the same registry — so entering ours in it is what
 * lets their blocks drop their trim for ours.
 *
 * <p>Both of our blocks are one casing as far as this is concerned, so a Station and a Canteen set
 * side by side read as one machine rather than as two crates.
 */
public class CWConnectedTextures {

	/**
	 * The faces of ours that are casing all the way to the edge, which is all of them.
	 *
	 * <p>The readouts are drawn <em>over</em> the casing rather than into it — the Canteen's trough
	 * is a plate in the model and its food and gauges are quads, and the Station's lamps are quads on
	 * bare boards — so no face has to opt out. That is the reason the trough stopped being part of a
	 * sheet: a face with a trough baked into it is a face that can never connect to anything.
	 */
	private static final java.util.function.BiPredicate<
		net.minecraft.world.level.block.state.BlockState, net.minecraft.core.Direction> EVERY_FACE =
			(state, face) -> true;

	public static void init(net.neoforged.bus.api.IEventBus modBus) {
		modBus.addListener(CWConnectedTextures::registerCasings);
		modBus.addListener(CWConnectedTextures::wrapModels);
	}

	/** Enter both blocks in Create's casing registry, which is what their blocks read. */
	private static void registerCasings(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			for (Block block : blocks())
				CreateClient.CASING_CONNECTIVITY.make(block, AllSpriteShifts.ANDESITE_CASING,
					EVERY_FACE);
		});
	}

	/**
	 * Wrap each of our blocks' baked models so their casing quads are shifted to the connected sheet.
	 *
	 * <p>Create does this through Registrate for its own blocks; we do not use Registrate, and
	 * {@link CTModel} is an ordinary {@code BakedModelWrapper}, so wrapping here reaches the same
	 * place. Every variant is wrapped — the Station has eight, one per facing and job state, and a
	 * variant left unwrapped is a block that stops connecting when it is turned round.
	 */
	private static void wrapModels(ModelEvent.ModifyBakingResult event) {
		ConnectedTextureBehaviour behaviour = new EncasedCTBehaviour(AllSpriteShifts.ANDESITE_CASING);
		Map<ModelResourceLocation, BakedModel> models = event.getModels();
		for (Block block : blocks()) {
			String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block)
				.getPath();
			for (ModelResourceLocation id : models.keySet()
				.toArray(new ModelResourceLocation[0])) {
				if (!id.id()
					.getNamespace()
					.equals(com.createworkers.CreateWorkers.ID) || !id.id()
						.getPath()
						.equals(path))
					continue;
				models.put(id, new CTModel(models.get(id), behaviour));
			}
		}
	}

	private static Block[] blocks() {
		return new Block[] { CWBlocks.WORKER_STATION.get(), CWBlocks.CANTEEN.get() };
	}
}

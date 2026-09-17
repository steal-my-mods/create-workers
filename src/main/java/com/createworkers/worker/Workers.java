package com.createworkers.worker;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.block.WorkerStationBlockEntity;
import com.createworkers.net.WorkerStatePacket;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWAttachments;
import com.createworkers.registry.CWProfessions;

import java.util.ArrayList;
import java.util.List;

import com.createworkers.worker.target.WorkerTarget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** Helpers for reading and classifying worker state. */
public class Workers {

	/** @return the worker state already on this entity, or null if it has never had one. */
	@Nullable
	public static WorkerData get(Entity entity) {
		if (!entity.hasData(CWAttachments.WORKER.get()))
			return null;
		return entity.getData(CWAttachments.WORKER.get());
	}

	/** @return the worker state, creating it if needed. Only call when about to employ. */
	public static WorkerData getOrCreate(Entity entity) {
		return entity.getData(CWAttachments.WORKER.get());
	}

	public static boolean isEmployed(Entity entity) {
		WorkerData data = get(entity);
		return data != null && data.isEmployed();
	}

	/** @return whether this kind of mob can be given a hard hat at all. */
	public static boolean canBeEmployed(Entity entity) {
		return entity instanceof Villager || entity instanceof EnderMan;
	}

	/**
	 * @return a fresh locomotion for this worker, or null if it cannot be employed. Instances are
	 *         per-worker rather than shared because some hold pacing state.
	 */
	@Nullable
	public static WorkerLocomotion locomotionFor(Mob mob) {
		if (mob instanceof EnderMan)
			return new TeleportLocomotion();
		if (mob instanceof Villager)
			return new WalkLocomotion();
		return null;
	}

	/**
	 * Whether a worker has strayed off its patch: further than {@code radius} from both its job
	 * site and every inventory it was programmed with.
	 *
	 * <p>Targets count as posts in their own right, so a worker standing at the far end of a long
	 * run is at work rather than wandering, however far that is from the middle of its beat.
	 */
	public static boolean isOffStation(BlockPos pos, WorkerData data, int radius) {
		if (pos.closerThan(data.getJobSite(), radius))
			return false;
		for (WorkerTarget target : data.getInputs())
			if (pos.closerThan(target.getPos(), radius))
				return false;
		for (WorkerTarget target : data.getOutputs())
			if (pos.closerThan(target.getPos(), radius))
				return false;
		return true;
	}

	/**
	 * Everywhere a worker may amble to on its idle rounds: exactly the blocks it was programmed with.
	 *
	 * <p>Keeping the rounds to the programme is what makes idling safe. These are the same positions
	 * the worker already walks to in order to do its job, so a worker that can work its beat can
	 * always walk its beat, and it can never idle its way somewhere it cannot get back from.
	 */
	public static List<BlockPos> patrolStops(WorkerData data, long gameTime) {
		List<BlockPos> stops = new ArrayList<>(data.getInputs()
			.size()
			+ data.getOutputs()
				.size());
		addStops(stops, data.getInputs(), gameTime);
		addStops(stops, data.getOutputs(), gameTime);
		return stops;
	}

	/**
	 * Targets the worker has lately failed to reach are left off the rounds. Without that, a stop it
	 * can never arrive at is picked again every few rounds and walked at until the worker is
	 * interrupted — and each of those attempts is the villager brain pathfinding once every few ticks
	 * for as long as it lasts.
	 */
	private static void addStops(List<BlockPos> stops, List<WorkerTarget> targets, long gameTime) {
		for (WorkerTarget target : targets)
			if (!target.isUnreachable(gameTime))
				stops.add(target.getPos());
	}

	/**
	 * Puts a mob to work. The one way anything becomes a worker.
	 *
	 * <p>Villagers arrive here from a {@code WorkerStation}, having already been made Workers by
	 * vanilla's {@code AssignProfessionFromJobSite}; endermen arrive from a player's right-click. Both
	 * need the same four things done, and the schedule is the one that is easy to forget — a worker
	 * without it keeps the village's hours rather than its own.
	 *
	 * <p>Nothing here touches the villager's profession. It does not have to: a station only ever
	 * hires a villager that had none, because {@code AssignProfessionFromJobSite} refuses to convert
	 * anything else. That is what let the whole business of stashing a village job, restoring its
	 * trades, refreshing the brain and holding the trade level above {@code ResetProfession}'s reach
	 * be deleted rather than maintained — see {@code docs/professions.md} for what used to be here and
	 * why none of it is needed once hiring goes through a workstation like every other job.
	 */
	public static void employ(Mob mob, ItemStack hat, WorkerProgram programme, @Nullable GlobalPos station,
		Shift shift) {
		WorkerData data = getOrCreate(mob);
		data.employ(hat, programme);
		data.setShift(shift);
		if (station != null)
			data.rememberStation(station);

		protectFromReset(mob);
		wearTheName(mob, data, hat);
		WorkerShift.applySchedule(mob, shift);
		data.markAtWork(mob.level()
			.getGameTime());
		updateCargoAppearance(mob, data.getHeld());
		WorkerStatePacket.sync(mob, data);
	}

	/**
	 * Puts the hat's name on its wearer, so a worker can be found again.
	 *
	 * <p>Every diagnostic in this mod runs into the same question — <i>which villager is it?</i> — and
	 * this is the cheap answer: name a job "Smelting feed" in the station screen and that is what
	 * floats over the villager standing in a hole. Nothing new is stored on the hat; it is the same
	 * {@code CUSTOM_NAME} an anvil would have set.
	 *
	 * <p><b>A name the player put there is never touched.</b> A villager may well have been named
	 * before it was ever hired, and a station that overwrote that would be taking something away and
	 * then, on retirement, clearing a name it never gave. So the worker remembers whether the name it
	 * is wearing is the job's, and only ever replaces its own handiwork.
	 *
	 * <p>Not made permanently visible either: this follows vanilla's rules, which show a name when you
	 * are near and looking at it. A dozen workers with a dozen labels floating over them is a factory
	 * nobody can see.
	 */
	/**
	 * Puts a job's new name on the worker already doing it.
	 *
	 * <p>The name lives on the hat in the rack, but a worker wears a <em>copy</em> taken when it was
	 * hired — so without this a rename reached the block and never the villager, and the label floating
	 * over the one standing in a hole stayed whatever the job was called when it was taken on. Which is
	 * the entire point of being able to name a job.
	 *
	 * <p>The copy is renamed too, so it stays a copy.
	 */
	public static void renameWorker(Mob mob, ItemStack hat) {
		WorkerData data = get(mob);
		if (data == null || !data.isEmployed())
			return;

		Component name = hat.get(DataComponents.CUSTOM_NAME);
		ItemStack worn = data.getHat();
		if (!worn.isEmpty()) {
			if (name == null)
				worn.remove(DataComponents.CUSTOM_NAME);
			else
				worn.set(DataComponents.CUSTOM_NAME, name);
		}
		// Still refuses to write over a name a player gave the villager themselves.
		wearTheName(mob, data, hat);
	}

	private static void wearTheName(Mob mob, WorkerData data, ItemStack hat) {
		if (mob.hasCustomName() && !data.isNamedByStation())
			return;

		Component name = hat.get(DataComponents.CUSTOM_NAME);
		mob.setCustomName(name);
		data.setNamedByStation(name != null);
	}

	/** Takes back a name this mod gave, and leaves alone one it did not. */
	private static void dropTheName(Mob mob, WorkerData data) {
		if (!data.isNamedByStation())
			return;
		mob.setCustomName(null);
		data.setNamedByStation(false);
	}

	/**
	 * Keeps {@code ResetProfession} from clearing a worker's profession out from under it.
	 *
	 * <p>Vanilla's own shield is a job site, and a worker cannot hold one — {@code PoiCompetitorScan}
	 * strips a shared job site from every villager but the richest, which for a rack of workers on
	 * nothing apiece is all but one of them, every tick, and a cleared profession takes the crew's
	 * schedule with it. What is left of {@code ResetProfession}'s conditions is trading experience, so
	 * a worker is given a single point of it. It never trades, so the point never goes anywhere.
	 */
	public static void protectFromReset(Mob mob) {
		if (mob instanceof Villager villager && villager.getVillagerXp() == 0)
			villager.setVillagerXp(PROTECTIVE_XP);
	}

	/**
	 * The single point of trading experience that keeps {@code ResetProfession} off a worker's back.
	 *
	 * <p>Named rather than written twice, because {@link #allowReset} has to be able to tell it from
	 * experience a player earned by trading — and telling those apart is the whole of how trades and
	 * retirement coexist.
	 */
	private static final int PROTECTIVE_XP = 1;

	/**
	 * Hands a retired worker back to vanilla, which cannot tidy up after one that still looks employed.
	 *
	 * <p>The mirror of {@link #protectFromReset}: with the experience gone, {@code ResetProfession}
	 * clears the profession within a tick or two and the {@code refreshBrain} that comes with it puts
	 * the village's own hours back.
	 */
	public static void allowReset(Mob mob) {
		if (!(mob instanceof Villager villager))
			return;
		// **Only the point we put there, and only from a worker that never really traded.**
		//
		// Giving workers trades broke this, and the comment on dismiss() had already named the
		// precondition: it works "because nothing raises its trade level any more". ResetProfession
		// wants xp == 0 *and* level <= 1, so a worker that has traded is one vanilla will never reset
		// -- and wiping its experience to force the issue would be taking away levels a player earned,
		// on a villager vanilla considers settled in its job.
		//
		// That lock is vanilla's own rule, not damage: a librarian you have traded with is a librarian
		// forever. So a worker that has traded stays a Worker, and what stops that being a dead end is
		// the other half of this change -- a Station will re-hire a former worker. A career labourer
		// is a better answer than a villager stuck holding a profession nothing can use.
		if (!isCareerWorker(villager))
			villager.setVillagerXp(0);
	}

	/**
	 * Whether this villager is a Worker <em>vanilla will never take the profession back off</em>.
	 *
	 * <p>{@code ResetProfession} wants experience of zero and trade level one, so a villager that has
	 * actually traded is settled in its job for good — that is vanilla's rule for every profession,
	 * not something this mod introduced, and a librarian you have bought from is a librarian forever.
	 *
	 * <p>One definition, used by both sides of the deal. {@link #allowReset} hands back only a worker
	 * this is false for, because taking the experience off one it is true for would be stripping
	 * levels a player earned to force a reset vanilla has already refused. And a Station will re-hire
	 * one it is true for, because the alternative is a villager holding a profession whose job-site
	 * predicates match nothing — unemployable by us and unemployable by the village. Somebody who has
	 * done this work before is exactly who you would hire.
	 *
	 * <p>It must be <b>narrower</b> than "has the Worker profession", and that is not a nicety. A
	 * worker the station has just let go still wears the profession for the tick or two before
	 * {@code ResetProfession} clears it, so a Station that hired anything wearing it would re-hire the
	 * villager it had that moment released — which is exactly what
	 * {@code aWorkerWhoseShiftIsTurnedOffFinishesFirst} caught.
	 */
	public static boolean isCareerWorker(Villager villager) {
		return villager.getVillagerData()
			.getProfession() == CWProfessions.WORKER.get()
			&& (villager.getVillagerXp() > PROTECTIVE_XP || villager.getVillagerData()
				.getLevel() > 1);
	}

	/**
	 * Takes a mob off the job, and returns whatever it should drop.
	 *
	 * <p>A villager's profession is left alone here too, and vanilla tidies it: a worker with no job
	 * site that has never traded and is still on trade level one is exactly what {@code
	 * ResetProfession} clears, so within a tick or two of its station going away it is an ordinary
	 * unemployed villager that can take any job again. That only works because nothing raises its
	 * trade level any more.
	 */
	public static List<ItemStack> dismiss(Mob mob) {
		WorkerData data = get(mob);
		if (data == null || !data.isEmployed())
			return List.of();

		wake(mob);
		allowReset(mob);
		dropTheName(mob, data);
		// Tell the station before the worker forgets which one it was. Whatever ends a job -- a death,
		// a conversion, a hat coming out -- comes through here, and a station that is loaded should not
		// have to notice on its own clock.
		tellStation(mob, data);
		List<ItemStack> drops = data.dismiss();
		updateCargoAppearance(mob, ItemStack.EMPTY);

		WorkerLocomotion locomotion = locomotionFor(mob);
		if (locomotion != null)
			locomotion.stop(mob);

		WorkerStatePacket.sync(mob, data);
		return drops;
	}

	/**
	 * Checks that a worker's station still has it down as one of its own, and sacks it if not.
	 *
	 * <p>The roster is the authority on who works for a station, and this is the other half of that:
	 * a station that cannot see a worker has to guess whether it is unloaded or gone, and when it
	 * guesses gone it hires a replacement. A worker that then turns out to have been merely unloaded
	 * would come back doing a job somebody else now has, invisible to the block that is supposed to
	 * know about it. So every worker asks, on every load, whether it is still on the books.
	 *
	 * <p>A station whose chunk is away answers nothing and the worker is left alone — it will ask
	 * again next time. Only a station that is there and says no counts as a no.
	 */
	public static void verifyEmployment(Mob mob) {
		WorkerData data = get(mob);
		if (data == null || !data.isEmployed())
			return;

		GlobalPos station = data.getStation();
		if (station == null || station.dimension() != mob.level()
			.dimension())
			return;
		if (!mob.level()
			.isLoaded(station.pos()))
			return;

		if (!(mob.level()
			.getBlockEntity(station.pos()) instanceof WorkerStationBlockEntity rack)) {
			// The block is gone without having sacked anybody -- something removed it out from under
			// the world. Nothing is left to employ this worker, so it is let go.
			//
			// **It keeps nothing.** A station worker's hat belongs to the block and never to the
			// worker: it wears a copy, and breaking a station already drops every hat in its rack. It
			// used to forget the station first and then dismiss, which is the one order that mints a
			// second hat -- dismiss withholds the hat precisely while the station is still set, so
			// clearing it first turned the copy into a drop. The worker's own cargo still falls, as
			// it does on any other dismissal. dismiss clears the station itself, so there is nothing
			// left to forget afterwards.
			for (ItemStack drop : dismiss(mob))
				mob.spawnAtLocation(drop);
			return;
		}

		if (!rack.employs(mob.getUUID()))
			for (ItemStack drop : dismiss(mob))
				mob.spawnAtLocation(drop);
	}

	private static void tellStation(Mob mob, WorkerData data) {
		GlobalPos station = data.getStation();
		if (station == null || station.dimension() != mob.level()
			.dimension())
			return;
		if (mob.level()
			.isLoaded(station.pos())
			&& mob.level()
				.getBlockEntity(station.pos()) instanceof WorkerStationBlockEntity rack)
			rack.forget(mob.getUUID());
	}

	/**
	 * Gets a worker out of bed.
	 *
	 * <p>A bed keeps the {@code OCCUPIED} flag it was given when somebody lay down in it, and clearing
	 * it is the sleeper's job on the way out. Dying does that for itself — {@code LivingEntity.die}
	 * wakes the entity first — but being <em>replaced</em> does not, so a worker bitten in its sleep
	 * would leave a bed nobody could ever use again. Cheap to call when the worker is already up.
	 */
	public static void wake(Entity entity) {
		if (entity instanceof LivingEntity living && living.isSleeping())
			living.stopSleeping();
	}

	/**
	 * Updates anything the mob itself draws for its cargo. Endermen already have a vanilla layer for
	 * a carried block, so a block cargo is handed to that rather than drawn twice.
	 *
	 * <p>That only tells the truth because an employed enderman is barred from picking blocks up and
	 * putting them down of its own accord — see {@code WorkerEvents.onMobGriefing}. Otherwise the
	 * block in its hands is whatever it last dug out of the floor.
	 */
	public static void updateCargoAppearance(Mob mob, ItemStack cargo) {
		if (!(mob instanceof EnderMan enderman))
			return;
		if (cargo.getItem() instanceof BlockItem blockItem)
			enderman.setCarriedBlock(blockItem.getBlock()
				.defaultBlockState());
		else
			enderman.setCarriedBlock(null);
	}
}

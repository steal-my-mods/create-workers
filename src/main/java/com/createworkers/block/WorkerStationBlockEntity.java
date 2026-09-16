package com.createworkers.block;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWBlockEntities;
import com.createworkers.registry.CWPoiTypes;
import com.createworkers.worker.Shift;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * A line's roster: an ordered rack of programmed hard hats, each of which may run on any of the three
 * shifts, and the villagers wearing them.
 *
 * <p>Nearly all of the hiring is vanilla's. The station is a point of interest in the
 * {@code acquirable_job_site} tag, so an unemployed villager finds it, walks to it and takes a ticket
 * through {@code AcquirePoi}, and {@code AssignProfessionFromJobSite} turns it into a Worker once it
 * is within two blocks — the same path that makes a librarian out of somebody standing at a lectern.
 * All this has to do is notice that it happened and hand over the right hat.
 *
 * <p>The hats <b>stay in the station</b> and each worker wears a copy. That one decision is the whole
 * self-healing property: nothing has to be handed back when a worker dies, because the job never left
 * the block.
 *
 * <h2>Why a station holds a whole line rather than one job</h2>
 *
 * <p>Workers in a factory are a chain, not a pool: take one out and the one before it fills a depot
 * that never drains and then stops entirely, so a shift missing a worker usually produces nothing
 * rather than less. That makes <i>fill whole shifts before starting the next</i> a correctness rule,
 * and a rule about several jobs at once has to live somewhere that can see several jobs at once. One
 * station with N slots can loop over its own roster; N stations with a slot each could only coordinate
 * by talking to one another, which is a distributed problem invented to avoid a list.
 *
 * <p>So the fill order is <b>shift-major, slot-minor</b>: every slot's day shift, then every slot's
 * evening, then every slot's night. Slot order breaks ties within a shift, which makes the order of
 * the rack the player's way of saying which roles matter most when the village is short of villagers.
 * The station never refuses to open a part-staffed shift — it cannot know whether it is looking at a
 * chain, at deliberate redundancy or at several unrelated jobs — it only fills in an order that is
 * defensible under all three.
 */
public class WorkerStationBlockEntity extends BlockEntity {

	/**
	 * The most slots any station can ever have.
	 *
	 * <p>A hard constant rather than a config value, because it has to agree with the point of
	 * interest's {@code maxTickets}, which is fixed when the type is registered and cannot vary per
	 * block. The config caps slots <em>below</em> this; nothing can raise it.
	 */
	public static final int MAX_SLOTS = 12;

	/** How often the station looks over its roster. */
	private static final int STAFFING_INTERVAL = 20;
	/** How far from the block to look for an arriving claimant. Vanilla assigns within 2; this is slack. */
	private static final double HIRING_RANGE = 4.0D;

	/** One job: a hat, the shifts it runs on, and who is wearing it on each of them. */
	public static final class Slot {

		private ItemStack hat;
		private final EnumSet<Shift> shifts = EnumSet.of(Shift.DAY);
		private final UUID[] workers = new UUID[Shift.VALUES.length];

		private Slot(ItemStack hat) {
			this.hat = hat;
		}

		public ItemStack hat() {
			return hat;
		}

		public boolean runs(Shift shift) {
			return shifts.contains(shift);
		}

		public Set<Shift> shifts() {
			return shifts;
		}

		@Nullable
		public UUID worker(Shift shift) {
			return workers[shift.ordinal()];
		}

		/** How many of this job's shifts have somebody on them. */
		public int staffed() {
			int count = 0;
			for (Shift shift : Shift.VALUES)
				if (runs(shift) && workers[shift.ordinal()] != null)
					count++;
			return count;
		}
	}

	private final List<Slot> slots = new ArrayList<>();

	/**
	 * How many of this block's point-of-interest tickets the station is holding back from villagers.
	 *
	 * <p>{@code maxTickets} is a property of the POI <em>type</em>, so every station advertises room
	 * for the largest roster the mod allows however few hats are actually in it. Left alone, a station
	 * with one job would have three dozen villagers walk across the village to be turned away. So the
	 * station takes its own surplus tickets and releases them one at a time as vacancies appear,
	 * which makes "free tickets" mean "openings" — the number vanilla's own {@code AcquirePoi} then
	 * enforces for us, exactly as it enforces one librarian per lectern.
	 *
	 * <p>Persisted, because the tickets themselves are: a {@code PoiRecord} saves its free count with
	 * the chunk section, so a station that forgot what it was holding would leak the difference.
	 */
	private int reserved;

	private int untilNextLook;

	public WorkerStationBlockEntity(BlockPos pos, BlockState state) {
		super(CWBlockEntities.WORKER_STATION.get(), pos, state);
	}

	// --- the rack ------------------------------------------------------------------------

	public List<Slot> slots() {
		return java.util.Collections.unmodifiableList(slots);
	}

	public boolean hasJob() {
		return !slots.isEmpty();
	}

	/** How many slots this station will accept, which the config may hold below {@link #MAX_SLOTS}. */
	public static int capacity() {
		return Math.min(MAX_SLOTS, CWConfig.STATION_SLOTS.get());
	}

	/**
	 * Racks a hat at the end of the list, on the day shift.
	 *
	 * @return whether there was room for it.
	 */
	public boolean addHat(ItemStack stack) {
		if (slots.size() >= capacity())
			return false;

		boolean wasEmpty = slots.isEmpty();
		slots.add(new Slot(stack.copyWithCount(1)));
		// A station with nothing in it is not a job site at all, so its point of interest -- and with
		// it every ticket this block was holding -- was thrown away when the last hat came out. The
		// record about to be created starts full, so anything we thought we were holding is fiction.
		if (wasEmpty)
			reserved = 0;
		changed();
		return true;
	}

	/**
	 * Takes a hat back out, and sacks everybody wearing a copy of it.
	 *
	 * <p>This is how a villager worker is fired, there being no other way now. The hat itself is not
	 * among what the workers drop — it is this one, which goes back to whoever asked for it — but a
	 * half-finished delivery is the worker's own and is dropped where it stands rather than deleted.
	 */
	public ItemStack removeHat(int index) {
		if (index < 0 || index >= slots.size())
			return ItemStack.EMPTY;

		Slot slot = slots.remove(index);
		dismissAll(slot);
		changed();
		return slot.hat;
	}

	/** Which shifts a job runs on. Turning one off sacks whoever was covering it. */
	public void setShifts(int index, Set<Shift> wanted) {
		if (index < 0 || index >= slots.size() || wanted.isEmpty())
			return;

		Slot slot = slots.get(index);
		for (Shift shift : Shift.VALUES)
			if (slot.runs(shift) && !wanted.contains(shift))
				dismiss(slot, shift);

		slot.shifts.clear();
		slot.shifts.addAll(wanted);
		changed();
	}

	/**
	 * Moves a job up or down the rack, which is what changes its hiring priority.
	 *
	 * <p>Nobody is sacked for it: the order decides who gets hired next, not who keeps their job, and
	 * tearing down a running crew because the player rearranged a list would be a far more violent
	 * thing than the one it prevents.
	 */
	public boolean moveSlot(int from, int to) {
		if (from < 0 || from >= slots.size() || to < 0 || to >= slots.size() || from == to)
			return false;
		slots.add(to, slots.remove(from));
		changed();
		return true;
	}

	/** @return whether this station has {@code id} down as one of its workers. */
	public boolean employs(UUID id) {
		for (Slot slot : slots)
			for (Shift shift : Shift.VALUES)
				if (id.equals(slot.workers[shift.ordinal()]))
					return true;
		return false;
	}

	// --- the readout ---------------------------------------------------------------------

	/** How many villagers this roster wants on {@code shift}. */
	public int positions(Shift shift) {
		int count = 0;
		for (Slot slot : slots)
			if (slot.runs(shift))
				count++;
		return count;
	}

	/** How many of them it has. */
	public int staffed(Shift shift) {
		int count = 0;
		for (Slot slot : slots)
			if (slot.runs(shift) && slot.workers[shift.ordinal()] != null)
				count++;
		return count;
	}

	private int vacancies() {
		int count = 0;
		for (Shift shift : Shift.VALUES)
			count += positions(shift) - staffed(shift);
		return count;
	}

	// --- the tick ------------------------------------------------------------------------

	public static void serverTick(Level level, BlockPos pos, BlockState state, WorkerStationBlockEntity station) {
		if (station.untilNextLook-- > 0)
			return;
		station.untilNextLook = STAFFING_INTERVAL;
		if (level instanceof ServerLevel server)
			station.manage(server);
	}

	private void manage(ServerLevel server) {
		if (!hasJob())
			return;

		auditRoster(server);
		sackAbsentees(server);
		rebalance(server);
		reconcileTickets(server);
		staffUp(server);
	}

	/**
	 * Strikes off workers that are no longer doing this job.
	 *
	 * <p>A loaded worker answers the question exactly. One that is not loaded does not, and the
	 * difference matters: a station that read "not loaded" as "gone" would hire a second villager onto
	 * a job somebody is already doing the moment the first walked into a chunk nobody is standing in.
	 *
	 * <p>The ticket count settles it in aggregate. Tickets are released by {@code Villager} on death
	 * and conversion and never on unloading, so the number of them out on loan is the number of
	 * workers still alive — and comparing that with the size of the roster says <em>how many</em> of
	 * the ones we cannot see have gone, even though a counter can never say which. When several are
	 * unaccounted for at once the ones lowest in the fill order are struck off first, on the principle
	 * that a station short-handed should be short-handed at the bottom of its list; the hat a returning
	 * worker is actually wearing is then reconciled by the worker itself, which checks on every load
	 * that its station still has it down.
	 */
	private void auditRoster(ServerLevel server) {
		List<Slot> unknownSlots = new ArrayList<>();
		List<Shift> unknownShifts = new ArrayList<>();
		int knownAlive = 0;

		for (Slot slot : slots) {
			for (Shift shift : Shift.VALUES) {
				UUID id = slot.workers[shift.ordinal()];
				if (id == null)
					continue;

				Entity entity = server.getEntity(id);
				if (entity == null) {
					unknownSlots.add(slot);
					unknownShifts.add(shift);
				} else if (isStillOurs(entity)) {
					knownAlive++;
				} else {
					slot.workers[shift.ordinal()] = null;
					setChanged();
				}
			}
		}

		int holding = CWPoiTypes.MAX_TICKETS - server.getPoiManager()
			.getFreeTickets(worldPosition) - reserved;
		int gone = knownAlive + unknownSlots.size() - holding;
		for (int i = unknownSlots.size() - 1; i >= 0 && gone > 0; i--, gone--) {
			unknownSlots.get(i).workers[unknownShifts.get(i)
				.ordinal()] = null;
			setChanged();
		}
	}

	/** Whether an entity we have a record of is still the worker we recorded. */
	private boolean isStillOurs(Entity entity) {
		if (!entity.isAlive() || !Workers.isEmployed(entity))
			return false;
		WorkerData data = Workers.get(entity);
		GlobalPos station = data == null ? null : data.getStation();
		return station != null && station.pos()
			.equals(worldPosition);
	}

	/**
	 * Holds back every ticket that is not an actual opening.
	 *
	 * <p>Aims at exactly one invariant — free tickets equal vacancies — from whichever side it is
	 * currently on, so it repairs itself after a load, a config change or a worker dying while the
	 * station was not there to see it, without any of those needing a case of their own.
	 */
	private void reconcileTickets(ServerLevel server) {
		int wanted = vacancies();
		int free = server.getPoiManager()
			.getFreeTickets(worldPosition);

		while (free > wanted && takeTicket(server)) {
			free--;
			reserved++;
			setChanged();
		}
		while (free < wanted && reserved > 0 && releaseTicket(server)) {
			free++;
			reserved--;
			setChanged();
		}
	}

	private boolean takeTicket(ServerLevel server) {
		return server.getPoiManager()
			.take(type -> type.is(CWPoiTypes.WORKER_STATION_KEY), (type, pos) -> pos.equals(worldPosition),
				worldPosition, 1)
			.isPresent();
	}

	private boolean releaseTicket(ServerLevel server) {
		// release() throws rather than returns for a position that was never a point of interest, and
		// a station whose block state has just changed under it is exactly that.
		if (!server.getPoiManager()
			.exists(worldPosition, type -> type.is(CWPoiTypes.WORKER_STATION_KEY)))
			return false;
		return server.getPoiManager()
			.release(worldPosition);
	}

	/**
	 * Gives a job to a villager that has walked over for it.
	 *
	 * <p>A claimant that arrives when the last opening has gone — the roster shrank, or somebody
	 * beat it here — is put back the way it came: the ticket returned by hand and the job site
	 * forgotten, after which {@code ResetProfession} makes it an ordinary unemployed villager again
	 * within a tick or two.
	 */
	private void staffUp(ServerLevel server) {
		AABB nearby = new AABB(worldPosition).inflate(HIRING_RANGE);
		for (Villager villager : server.getEntitiesOfClass(Villager.class, nearby, this::hasClaimedThis)) {
			if (Workers.isEmployed(villager))
				continue;
			// A villager killed at its station is unemployed the instant it dies -- our own death
			// handler takes the hat off it -- but it stays in the world for its death animation,
			// still holding this block as its job site. Without this the station hires the corpse,
			// shows the job as covered for a second, and then has to strike it off again when the
			// entity finally goes.

			Position vacancy = nextVacancy();
			if (vacancy == null) {
				turnAway(villager);
				continue;
			}

			hire(server, villager, vacancy);
		}
	}

	/** A place on the roster: one slot on one shift, whether or not anybody is in it. */
	private record Position(int slot, Shift shift) {
	}

	/** Where a position falls in the fill order, so two of them can be compared. */
	private int order(Position position) {
		return position.shift()
			.ordinal() * slots.size() + position.slot();
	}

	/**
	 * The opening to fill next: shift-major, slot-minor.
	 *
	 * <p>Whole shifts before deep ones, because concentrating a short crew on one window is never
	 * worse than scattering it across three and is often the difference between a working factory and
	 * nothing at all — and the day shift first, so an understaffed line runs during the hours a player
	 * is most likely to be standing in it. A factory that only works while you are asleep is one you
	 * cannot debug.
	 */
	@Nullable
	private Position nextVacancy() {
		for (Shift shift : Shift.VALUES)
			for (int i = 0; i < slots.size(); i++)
				if (slots.get(i)
					.runs(shift)
					&& slots.get(i).workers[shift.ordinal()] == null)
					return new Position(i, shift);
		return null;
	}

	/** The last place on the roster that has somebody in it, reading the fill order backwards. */
	@Nullable
	private Position lastStaffed() {
		for (int s = Shift.VALUES.length - 1; s >= 0; s--) {
			Shift shift = Shift.VALUES[s];
			for (int i = slots.size() - 1; i >= 0; i--)
				if (slots.get(i)
					.runs(shift)
					&& slots.get(i).workers[shift.ordinal()] != null)
					return new Position(i, shift);
		}
		return null;
	}

	/**
	 * Moves workers up the fill order until the roster is a prefix of it again.
	 *
	 * <p>Without this the fill order only holds while a roster is <em>growing</em>, and every death
	 * degrades it for good. Three jobs on two shifts with four villagers gives a complete day crew and
	 * one evening worker; lose one of the day crew and the day line is broken, the evening line was
	 * never whole, and the factory produces nothing out of three surviving workers. Promoting the
	 * evening worker up to days restores a working line — which is the chain argument that put the fill
	 * order there in the first place, applied to the case the fill order alone cannot reach.
	 *
	 * <p>It costs nothing to do eagerly rather than waiting to see whether a replacement turns up,
	 * because a replacement fills the <em>last</em> place in the order either way: promoting first and
	 * hiring into the hole behind reaches the same roster, and the state in between is the one that
	 * works. If no villager is spare it is the difference between a running factory and a stopped one.
	 *
	 * <p>The worker that moves is the last one in the fill order, which is a rule a player can predict
	 * and which the rack's own order already expresses. It is re-employed rather than edited: a
	 * promotion is usually a different hat as well as different hours, so it wakes up, puts down
	 * whatever it was carrying and starts the new job clean.
	 */
	private void rebalance(ServerLevel server) {
		// lastStaffed strictly decreases on every successful move, so this cannot run away -- but a
		// roster is at most thirty-six places and a loop over a mutating list deserves the belt.
		for (int guard = slots.size() * Shift.VALUES.length; guard > 0 && promoteOne(server); guard--)
			;
	}

	private boolean promoteOne(ServerLevel server) {
		Position vacancy = nextVacancy();
		Position last = lastStaffed();
		if (vacancy == null || last == null || order(last) < order(vacancy))
			return false;

		Slot from = slots.get(last.slot());
		UUID id = from.workers[last.shift()
			.ordinal()];
		// An unloaded worker cannot be promoted, and guessing at one is what the roster audit already
		// refuses to do. Left where it is; the next look will find it.
		if (!(server.getEntity(id) instanceof Villager worker))
			return false;

		Slot to = slots.get(vacancy.slot());
		WorkerProgram programme = HardHatItem.getProgram(to.hat);
		if (!programme.hasTargets())
			return false;

		from.workers[last.shift()
			.ordinal()] = null;
		drop(worker);
		Workers.employ(worker, to.hat, programme, GlobalPos.of(server.dimension(), worldPosition), vacancy.shift());
		to.workers[vacancy.shift()
			.ordinal()] = id;
		setChanged();
		return true;
	}

	private void hire(ServerLevel server, Villager villager, Position vacancy) {
		Slot slot = slots.get(vacancy.slot());
		WorkerProgram programme = HardHatItem.getProgram(slot.hat);
		if (!programme.hasTargets()) {
			turnAway(villager);
			return;
		}

		Workers.employ(villager, slot.hat, programme, GlobalPos.of(server.dimension(), worldPosition),
			vacancy.shift());
		slot.workers[vacancy.shift()
			.ordinal()] = villager.getUUID();
		setChanged();
	}

	private void turnAway(Villager villager) {
		villager.releasePoi(MemoryModuleType.JOB_SITE);
		villager.getBrain()
			.eraseMemory(MemoryModuleType.JOB_SITE);
	}

	/**
	 * Gives a job away when its holder has stopped turning up.
	 *
	 * <p>The station can replace a worker that dies, because dying frees the ticket. It cannot replace
	 * one that is merely never coming back — walled in, fallen somewhere, stranded across a gap — and
	 * that is the failure this whole block was built to answer: a line quietly running short with
	 * nothing to say which villager to go and look for.
	 *
	 * <p>Absence is measured by where a worker is and not by what it has moved, because a worker with
	 * nothing to haul is idle rather than absent — and a worker uselessly cycling through targets it
	 * can never reach looks busy by every other measure.
	 */
	private void sackAbsentees(ServerLevel server) {
		int timeout = CWConfig.ABSENTEE_TIMEOUT.get();
		if (timeout <= 0)
			return;

		for (Slot slot : slots) {
			for (Shift shift : Shift.VALUES) {
				UUID id = slot.workers[shift.ordinal()];
				if (id == null)
					continue;
				// Not loaded is not judged: its clock is not running either.
				if (!(server.getEntity(id) instanceof Villager worker))
					continue;

				WorkerData data = Workers.get(worker);
				if (data == null || server.getGameTime() - data.lastAtWork() <= timeout)
					continue;

				// The ticket has to go back by hand. A sacked absentee is alive and still holding this
				// block as its job site, so its ticket would stay taken and nobody could ever replace
				// it -- which is the exact failure this is meant to end.
				turnAway(worker);
				drop(worker);
				slot.workers[shift.ordinal()] = null;
				setChanged();
			}
		}
	}

	// --- sacking -------------------------------------------------------------------------

	/** Sacks everybody on the rack. What breaking the block does. */
	public void dismissAll() {
		for (Slot slot : slots)
			dismissAll(slot);
	}

	private void dismissAll(Slot slot) {
		for (Shift shift : Shift.VALUES)
			dismiss(slot, shift);
	}

	private void dismiss(Slot slot, Shift shift) {
		UUID id = slot.workers[shift.ordinal()];
		if (id == null)
			return;
		slot.workers[shift.ordinal()] = null;

		if (level instanceof ServerLevel server && server.getEntity(id) instanceof Mob mob)
			drop(mob);
	}

	private static void drop(Mob mob) {
		for (ItemStack stack : Workers.dismiss(mob))
			mob.spawnAtLocation(stack);
	}

	private boolean hasClaimedThis(Villager villager) {
		return villager.getBrain()
			.getMemory(MemoryModuleType.JOB_SITE)
			.map(site -> site.pos()
				.equals(worldPosition))
			.orElse(false);
	}

	/**
	 * Saves, and keeps the block state in step with whether there is a job here.
	 *
	 * <p>{@code HAS_JOB} is what the point of interest is registered over, so a station with no hats is
	 * not a job site at all: nobody walks to it and nobody is left standing beside it having become a
	 * Worker with nothing to do — and unable to take any other job, a Worker's only workstation being
	 * the block it is standing at.
	 */
	private void changed() {
		setChanged();
		if (level != null && getBlockState().getValue(WorkerStationBlock.HAS_JOB) != hasJob())
			level.setBlock(worldPosition, getBlockState().setValue(WorkerStationBlock.HAS_JOB, hasJob()), 3);
	}

	// --- serialization -------------------------------------------------------------------

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		slots.clear();
		reserved = tag.getInt("Reserved");

		ListTag racked = tag.getList("Slots", Tag.TAG_COMPOUND);
		for (int i = 0; i < racked.size() && slots.size() < MAX_SLOTS; i++) {
			CompoundTag entry = racked.getCompound(i);
			ItemStack hat = ItemStack.parseOptional(registries, entry.getCompound("Hat"));
			if (hat.isEmpty())
				continue;

			Slot slot = new Slot(hat);
			slot.shifts.clear();
			ListTag shifts = entry.getList("Shifts", Tag.TAG_STRING);
			for (int s = 0; s < shifts.size(); s++)
				slot.shifts.add(Shift.byName(shifts.getString(s), Shift.DAY));
			// A slot that ran on no shift at all could never be filled and could never be seen to be
			// empty. Save data that says so is damage, and the day crew is the harmless reading.
			if (slot.shifts.isEmpty())
				slot.shifts.add(Shift.DAY);

			CompoundTag workers = entry.getCompound("Workers");
			for (Shift shift : Shift.VALUES)
				if (workers.hasUUID(shift.getSerializedName()))
					slot.workers[shift.ordinal()] = workers.getUUID(shift.getSerializedName());

			slots.add(slot);
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putInt("Reserved", reserved);

		ListTag racked = new ListTag();
		for (Slot slot : slots) {
			CompoundTag entry = new CompoundTag();
			entry.put("Hat", slot.hat.save(registries));

			ListTag shifts = new ListTag();
			for (Shift shift : Shift.VALUES)
				if (slot.runs(shift))
					shifts.add(net.minecraft.nbt.StringTag.valueOf(shift.getSerializedName()));
			entry.put("Shifts", shifts);

			CompoundTag workers = new CompoundTag();
			for (Shift shift : Shift.VALUES)
				if (slot.workers[shift.ordinal()] != null)
					workers.putUUID(shift.getSerializedName(), slot.workers[shift.ordinal()]);
			entry.put("Workers", workers);

			racked.add(entry);
		}
		tag.put("Slots", racked);
	}
}

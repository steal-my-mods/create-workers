package com.createworkers.block;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;
import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWBlockEntities;
import com.createworkers.registry.CWProfessions;
import com.createworkers.worker.Shift;
import com.createworkers.worker.WorkerData;
import com.createworkers.worker.Workers;
import com.simibubi.create.foundation.utility.IInteractionChecker;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * A line's roster: an ordered rack of programmed hard hats, each of which may run on any of the three
 * shifts, and the villagers wearing them.
 *
 * <p>The station does its own hiring — see {@link #recruit}. It looks for an unemployed adult
 * villager near enough to path to it, gives it the Worker profession and this block as a job site,
 * and hands over a hat. It used to leave all of that to vanilla, the way a lectern does, and that
 * route cannot staff one block with more than one villager.
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
public class WorkerStationBlockEntity extends BlockEntity implements IInteractionChecker {

	/**
	 * The most slots any station can ever have.
	 *
	 * <p>A hard constant rather than a config value, because it has to agree with the point of
	 * interest's {@code maxTickets}, which is fixed when the type is registered and cannot vary per
	 * block. The config caps slots <em>below</em> this; nothing can raise it.
	 */
	public static final int MAX_SLOTS = 12;

	/** As long a name as an anvil allows. Checked here too: a packet is not the sender's to size. */
	private static final int MAX_NAME_LENGTH = 32;

	/** How often the station looks over its roster. */
	private static final int STAFFING_INTERVAL = 20;
	/** How far from the block to look for an arriving claimant. Vanilla assigns within 2; this is slack. */
	private static final double HIRING_RANGE = 4.0D;
	/**
	 * How far a station will look for somebody to hire.
	 *
	 * <p>A station hires out of the building it is in, not across a village. Deliberately far short of
	 * the 48 blocks {@code AcquirePoi} searches: this is an entity query and a pathfind rather than a
	 * point-of-interest lookup, it only runs while there is an opening, and a job board nobody can see
	 * from where they are standing is a strange thing to be recruited by.
	 */
	private static final double RECRUIT_RANGE = 16.0D;
	/** How many candidates are path-checked in one look. Each one is an A*. */
	private static final int RECRUIT_CANDIDATES = 3;
	/**
	 * How long a worker gets to finish what it is carrying before its station moves it or lets it go.
	 *
	 * <p>Long enough to walk a beat and hand over a stack, short enough that a job whose last output is
	 * full or unreachable is not a job nobody can ever be hired into. Thirty seconds.
	 */
	private static final int NOTICE_TICKS = 600;

	/** One job: a hat, the shifts it runs on, and who is wearing it on each of them. */
	public static final class Slot {

		private ItemStack hat;
		private final EnumSet<Shift> shifts = EnumSet.of(Shift.DAY);
		private final UUID[] workers = new UUID[Shift.VALUES.length];
		/**
		 * When each worker was last found in the world, and the whole of how a station now tells a
		 * worker that died from one whose chunk is simply away. Runtime only: a station that has just
		 * loaded has not failed to find anybody yet, so the first look starts the clock.
		 */
		private final long[] seen = new long[Shift.VALUES.length];

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

	/**
	 * The rack, as a fixed set of places with gaps allowed rather than a list that closes up.
	 *
	 * <p>A place is where the player put a hat, and that is the whole of it. Requiring the next free
	 * index made the rack impossible to use as a priority order — a job you know is your least
	 * important is one you want to put at the <em>bottom</em>, before you have anything above it —
	 * and it made taking a hat out of the middle shuffle every job below it into a different priority,
	 * which is not what anyone clicking an item out of an inventory means.
	 *
	 * <p>It also removes a whole class of bug. A dense list backing an inventory disagrees with vanilla
	 * about what a slot is: {@code moveItemStackTo} shrinks the stack it finds <em>in place</em>, so
	 * shift-clicking a hat out left a job holding a zero-count stack, which the block entity then could
	 * not save ("Cannot encode empty ItemStack"). A fixed array is what an inventory already is.
	 */
	private final Slot[] slots = new Slot[MAX_SLOTS];

	private int untilNextLook;

	/**
	 * Whether this tick changed who is on the rack.
	 *
	 * <p>Hiring, striking off, sacking and promoting all used to call {@code setChanged} alone, which
	 * saves the block and tells nobody — so a screen showed a job as unstaffed until something the
	 * player did happened to call {@code changed()}, and a shift toggled while that was stale wrote the
	 * client's fiction back over the truth. They set this instead, and the tick syncs once at the end
	 * rather than sending a packet per worker.
	 */
	private boolean rosterChanged;

	/**
	 * The rack as an inventory, so a funnel or an arm can stock it and the station screen can have
	 * real slots rather than a picture of some.
	 *
	 * <p>It is a set of <b>places</b> wearing an inventory's clothes. The order of the rack is the
	 * player's priority lever, so a job's index is a statement about what matters most and is theirs
	 * to choose: {@link #isItemValid} admits any free place, not merely the next one, and taking a hat
	 * out leaves the gap behind it rather than promoting everything below into a priority nobody asked
	 * for.
	 */
	private final IItemHandlerModifiable rack = new IItemHandlerModifiable() {

		/**
		 * Always the hard ceiling, never the configured one. A menu's slots are laid out when it is
		 * built, on both sides, so letting their number follow a setting would make the screen's shape
		 * depend on a config having reached the client. Capacity is enforced where a hat goes in
		 * instead, which is the only place it can be disobeyed.
		 */
		@Override
		public int getSlots() {
			return MAX_SLOTS;
		}

		@Override
		public ItemStack getStackInSlot(int index) {
			Slot job = jobAt(index);
			return job == null ? ItemStack.EMPTY : job.hat;
		}

		@Override
		public ItemStack insertItem(int index, ItemStack stack, boolean simulate) {
			if (!isItemValid(index, stack))
				return stack;
			// putHat, not addHat: the index is the place the player asked for, and the whole point of
			// a rack with gaps is that it is not the next free one.
			if (!simulate)
				putHat(index, stack);
			return stack.copyWithCount(stack.getCount() - 1);
		}

		@Override
		public ItemStack extractItem(int index, int amount, boolean simulate) {
			Slot job = jobAt(index);
			if (amount < 1 || job == null)
				return ItemStack.EMPTY;
			if (simulate)
				return job.hat.copy();
			// Not a quiet extraction: this hat is somebody's job, and the crew wearing copies of it
			// goes with it. Exactly what taking it out by hand does.
			return removeHat(index);
		}

		@Override
		public int getSlotLimit(int index) {
			return 1;
		}

		@Override
		public boolean isItemValid(int index, ItemStack stack) {
			return index >= 0 && index < capacity() && slots[index] == null
				&& stack.getItem() instanceof HardHatItem;
		}

		/**
		 * Only a menu calls this — nothing in the world sets a slot outright — and on the client it is
		 * how the server's view of the rack lands. It stays honest about the list underneath: emptying
		 * a slot removes the job, and a slot past the end can only be the next one.
		 */
		@Override
		public void setStackInSlot(int index, ItemStack stack) {
			if (index < 0 || index >= MAX_SLOTS)
				return;
			// Empty covers a zero-count stack as well as ItemStack.EMPTY, which is what vanilla leaves
			// behind when it takes the last of a slot: moveItemStackTo shrinks the live stack rather
			// than replacing it, and a job holding a zero-count hat is one the block entity cannot save.
			if (stack.isEmpty()) {
				if (slots[index] != null)
					removeHat(index);
				return;
			}
			if (slots[index] == null)
				slots[index] = new Slot(stack.copyWithCount(1));
			else
				slots[index].hat = stack.copyWithCount(1);
			changed();
		}
	};

	public WorkerStationBlockEntity(BlockPos pos, BlockState state) {
		super(CWBlockEntities.WORKER_STATION.get(), pos, state);
	}

	/** @return the rack as an inventory, for the menu's slots and for anything piping hats in. */
	public IItemHandlerModifiable rack() {
		return rack;
	}

	// --- the rack ------------------------------------------------------------------------

	/** The rack by position, with a null wherever there is no job. Always {@link #MAX_SLOTS} long. */
	public List<Slot> slots() {
		return java.util.Collections.unmodifiableList(java.util.Arrays.asList(slots));
	}

	/** The job at {@code index}, or null — which is an ordinary answer now, not an edge case. */
	@Nullable
	public Slot jobAt(int index) {
		return index >= 0 && index < MAX_SLOTS ? slots[index] : null;
	}

	/** How many jobs are on the rack, gaps not counted. */
	public int jobCount() {
		int count = 0;
		for (Slot slot : slots)
			if (slot != null)
				count++;
		return count;
	}

	public boolean hasJob() {
		for (Slot slot : slots)
			if (slot != null)
				return true;
		return false;
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
		for (int i = 0; i < capacity(); i++)
			if (slots[i] == null)
				return putHat(i, stack);
		return false;
	}

	/**
	 * Racks a hat at a place the player chose.
	 *
	 * @return whether that place was free.
	 */
	public boolean putHat(int index, ItemStack stack) {
		if (index < 0 || index >= capacity() || slots[index] != null)
			return false;

		slots[index] = new Slot(stack.copyWithCount(1));
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
		Slot slot = jobAt(index);
		if (slot == null)
			return ItemStack.EMPTY;

		// The place is left empty rather than closed up. Every job below it keeps the priority the
		// player gave it, which is the whole point of the rack being an order.
		slots[index] = null;
		dismissAll(slot);
		changed();
		return slot.hat;
	}

	/** Which shifts a job runs on. Turning one off sacks whoever was covering it. */
	public void setShifts(int index, Set<Shift> wanted) {
		Slot slot = jobAt(index);
		if (slot == null || wanted.isEmpty())
			return;

		// Nobody is sacked here. A worker on a shift this job no longer runs is one the next look gives
		// notice to and lets go once its hands are empty -- see finishHandovers. Doing it on the spot
		// dropped a half-finished delivery on the floor and then, the worker being unemployed and still
		// stood at its job site, hired it straight back onto whichever shift had just been switched on.
		slot.shifts.clear();
		slot.shifts.addAll(wanted);

		// Turning a shift back on cancels the notice its worker was given, and this is where that
		// belongs -- it is an answer to a thing the player just did, not a condition to re-test
		// twenty times a second against every worker on the rack.
		if (level instanceof ServerLevel server)
			for (Shift shift : wanted) {
				UUID id = slot.workers[shift.ordinal()];
				if (id != null && server.getEntity(id) instanceof Villager worker) {
					WorkerData data = Workers.get(worker);
					if (data != null)
						data.clearNotice();
				}
			}

		changed();
	}

	/**
	 * Names a job, or takes its name away again.
	 *
	 * <p>The name lives on the hat rather than on the slot, so it survives the hat being taken out,
	 * moved down the rack or dropped by a worker that died — and it is the same {@code CUSTOM_NAME} an
	 * anvil would have set, so nothing new has to be saved or synced for it.
	 */
	public void renameJob(int index, String name) {
		Slot job = jobAt(index);
		if (job == null)
			return;

		ItemStack hat = job.hat;
		String trimmed = name.trim();
		if (trimmed.isEmpty())
			hat.remove(DataComponents.CUSTOM_NAME);
		else
			hat.set(DataComponents.CUSTOM_NAME,
				Component.literal(trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH)
					: trimmed));

		// And the villagers already doing it, who wear a copy taken when they were hired and would
		// otherwise keep answering to the old name until they died.
		if (level instanceof ServerLevel server)
			for (Shift shift : Shift.VALUES) {
				UUID id = job.workers[shift.ordinal()];
				if (id != null && server.getEntity(id) instanceof Villager worker)
					Workers.renameWorker(worker, hat);
			}

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
		if (from < 0 || from >= MAX_SLOTS || to < 0 || to >= MAX_SLOTS || from == to)
			return false;
		if (slots[from] == null)
			return false;

		// A swap, not an insert. With gaps allowed there is nothing to close up, and swapping is what
		// lets a job be pushed into an empty place above it rather than only past another job.
		Slot moved = slots[from];
		slots[from] = slots[to];
		slots[to] = moved;
		changed();
		return true;
	}

	/**
	 * Strikes a worker off the moment it stops being one, rather than waiting to notice.
	 *
	 * <p>The audit's clock is a backstop for a death the station was not loaded to see. It is a poor
	 * primary signal, because a corpse is only in the world for the twenty ticks of its death animation
	 * and a station only looks every twenty — so whether a death was caught promptly or sat out the
	 * absentee timeout came down to which tick it landed on.
	 */
	public void forget(UUID id) {
		for (Slot slot : slots) {
			if (slot == null)
				continue;
			for (Shift shift : Shift.VALUES)
				if (id.equals(slot.workers[shift.ordinal()])) {
					slot.workers[shift.ordinal()] = null;
					rosterChanged();
				}
		}
	}

	/** @return whether this station has {@code id} down as one of its workers. */
	public boolean employs(UUID id) {
		for (Slot slot : slots)
			if (slot != null)
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
			if (slot != null && slot.runs(shift))
				count++;
		return count;
	}

	/** How many of them it has. */
	public int staffed(Shift shift) {
		int count = 0;
		for (Slot slot : slots)
			if (slot != null && slot.runs(shift) && slot.workers[shift.ordinal()] != null)
				count++;
		return count;
	}

	/** How many villagers this rack is short. What it advertises, and never more. */
	public int vacancies() {
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
		finishHandovers(server);
		sackAbsentees(server);
		rebalance(server);
		recruit(server);

		// Once, at the end. Everything above may have moved several workers, and the screen wants the
		// answer rather than the working.
		if (rosterChanged) {
			rosterChanged = false;
			changed();
		}
	}

	/** Records that who is on the rack has changed, for the one sync at the end of the tick. */
	private void rosterChanged() {
		rosterChanged = true;
		setChanged();
	}

	/**
	 * Strikes off workers that are no longer doing this job.
	 *
	 * <p>A loaded worker answers the question exactly. One that is not loaded does not, and the
	 * difference matters: a station that read "not loaded" as "gone" would hire a second villager onto
	 * a job somebody is already doing the moment the first walked into a chunk nobody is standing in.
	 *
	 * <p>So time settles it. Every look that finds a worker stamps it, and one that has not been
	 * findable for as long as an absentee gets is written off. That covers what nothing else can — a
	 * worker that died while its station was not loaded to see it — and it is wrong only about a worker
	 * that was away for minutes and comes back, which sacks itself on load when the roster no longer has
	 * it down and is hired again a moment later.
	 *
	 * <p>This was point-of-interest tickets, which vanilla released on death whether anybody was
	 * watching or not. That was the better signal and it is gone for a reason: a worker holds no job
	 * site any more, so nothing releases a ticket for us and there is nothing left to count.
	 */
	private void auditRoster(ServerLevel server) {
		long now = server.getGameTime();
		int lost = CWConfig.ABSENTEE_TIMEOUT.get();

		for (Slot slot : slots) {
			if (slot == null)
				continue;
			for (Shift shift : Shift.VALUES) {
				int index = shift.ordinal();
				if (slot.workers[index] == null)
					continue;

				Entity entity = server.getEntity(slot.workers[index]);
				if (entity != null && isStillOurs(entity)) {
					slot.seen[index] = now;
					continue;
				}
				if (entity != null) {
					// There, and demonstrably not ours: dead, or wearing somebody else's hat.
					slot.workers[index] = null;
					rosterChanged();
					continue;
				}

				if (slot.seen[index] == 0L)
					slot.seen[index] = now;
				else if (lost > 0 && now - slot.seen[index] > lost) {
					slot.workers[index] = null;
					rosterChanged();
				}
			}
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

	private record Position(int slot, Shift shift) {
	}

	/**
	 * Finds somebody for the next opening and puts them to work.
	 *
	 * <p><b>This is the mod's own hiring, and it has to be.</b> The station used to sit in
	 * {@code minecraft:acquirable_job_site} and let vanilla do all of it — an unemployed villager
	 * found the block, claimed it, walked over and was turned into a Worker on arrival, exactly as one
	 * takes a lectern. That route cannot staff this block, and the reason is a behaviour rather than a
	 * bug: {@code YieldJobSite}, villager CORE priority 8, runs on any villager holding a
	 * {@code POTENTIAL_JOB_SITE} and makes it <em>give up its claim</em> as soon as it sees another
	 * villager whose profession already holds that same point of interest. A worker this station has
	 * already hired is precisely that villager. So the first villager was hired and every one after it
	 * walked over, yielded and stood about — and the claim it dropped leaked a ticket, because erasing
	 * the memory does not release one.
	 *
	 * <p>The rule is right for vanilla, where every workstation holds exactly one villager. A station
	 * holds up to thirty-six, so it cannot use a route built on that assumption. What is left of
	 * vanilla's part is everything that still works: the profession, the job-site memory that keeps
	 * {@code ResetProfession} off a worker's back, and the tickets that say who is still alive.
	 */
	/**
	 * Whether a job's work is close enough to this block to be worth hiring for.
	 *
	 * <p>Measured to {@link WorkerProgram#centre()}, the middle of the hat's targets, because that is
	 * what a worker is given as its job site and what its leash is anchored to. It is a <b>radius from
	 * the block</b> and deliberately not {@code maxTargetSpread}, which is a diameter across one hat's
	 * own targets: a job may be wide and near, or narrow and far, and only the second is a problem.
	 *
	 * <p>Out of range is held rather than refused. A hat is accepted into the rack either way, because
	 * a hat already in one can be taken out and reprogrammed somewhere else, and a rule that only ran
	 * at the door would miss exactly that — this way the same check covers both, and the screen can say
	 * which job is the problem instead of a slot silently declining to accept anything.
	 */
	public boolean workIsInRange(int index) {
		Slot slot = jobAt(index);
		if (slot == null)
			return false;
		WorkerProgram programme = HardHatItem.getProgram(slot.hat);
		if (!programme.hasTargets())
			return false;
		int range = CWConfig.STATION_RANGE.get();
		return programme.centre()
			.distSqr(worldPosition) <= (double) range * range;
	}

	private void recruit(ServerLevel server) {
		Position vacancy = nextVacancy();
		if (vacancy == null)
			return;
		// Before anybody is made a Worker, not after. A villager given the profession and then turned
		// away for an unprogrammed hat would be shielded from ResetProfession with no job to show for
		// it, which is a villager stuck as a Worker for the rest of the world's life.
		if (!HardHatItem.getProgram(slots[vacancy.slot()].hat)
			.hasTargets())
			return;
		// And not for work that is too far from this block to walk to. Nothing bounded the two before,
		// so a hat programmed a thousand blocks away turned a station into a villager grinder: it hires
		// whoever is standing next to it, employs them to a job site they will never reach, the leash
		// walks them at it until the stall clocks give up, the absentee timeout strikes them off -- and
		// then it hires the next one and does it again, converting every villager in range in turn.
		if (!workIsInRange(vacancy.slot()))
			return;

		AABB nearby = new AABB(worldPosition).inflate(RECRUIT_RANGE);
		List<Villager> candidates = server.getEntitiesOfClass(Villager.class, nearby, this::couldWork);
		candidates.sort(java.util.Comparator.comparingDouble(villager -> villager.distanceToSqr(
			worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D, worldPosition.getZ() + 0.5D)));

		int checked = 0;
		for (Villager villager : candidates) {
			if (checked++ >= RECRUIT_CANDIDATES)
				return;
			// Path-verified, as vanilla's own search is. A villager walled away from its station is one
			// that could never reach the work either, and hiring it would only start the absentee clock.
			Path path = villager.getNavigation()
				.createPath(worldPosition, 1);
			if (path == null || !path.canReach())
				continue;

			takeTheJob(server, villager);
			hire(server, villager, vacancy);
			return;
		}
	}

	/** Whether this villager is one a station may approach: an adult with no job of its own. */
	private boolean couldWork(Villager villager) {
		return villager.isAlive() && !villager.isBaby() && !villager.isDeadOrDying()
			&& villager.getVillagerData()
				.getProfession() == VillagerProfession.NONE
			&& !Workers.isEmployed(villager);
	}

	/**
	 * Everything {@code AssignProfessionFromJobSite} used to do on the villager's arrival.
	 *
	 * <p><b>No job site.</b> That is what {@code AssignProfessionFromJobSite} would have set, and what
	 * normally keeps {@code ResetProfession} from clearing a profession straight back off again — but a
	 * worker cannot hold one. {@code PoiCompetitorScan} takes every villager whose job site is the same
	 * position with a matching profession and erases it from all but the one with the most trading
	 * experience, which for a rack of workers on none apiece is all but one of them, every tick. Losing
	 * the memory is not the injury; what follows it is, because {@code ResetProfession} then calls
	 * {@code refreshBrain}, and a refreshed brain is back on the village's hours rather than its crew's.
	 *
	 * <p>So the shield is a single point of trading experience, which {@code ResetProfession} also
	 * refuses to act on and which no two workers can strip from one another. The profession still goes
	 * on first and the brain refresh second, because a refresh rebuilds the brain.
	 */
	private void takeTheJob(ServerLevel server, Villager villager) {
		villager.setVillagerData(villager.getVillagerData()
			.setProfession(CWProfessions.WORKER.get()));
		villager.refreshBrain(server);
	}

	/** Where a position falls in the fill order, so two of them can be compared. */
	private int order(Position position) {
		return position.shift()
			.ordinal() * MAX_SLOTS + position.slot();
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
			for (int i = 0; i < MAX_SLOTS; i++)
				if (slots[i] != null && slots[i].runs(shift) && slots[i].workers[shift.ordinal()] == null)
					return new Position(i, shift);
		return null;
	}

	/** The last place on the roster that has somebody in it, reading the fill order backwards. */
	@Nullable
	private Position lastStaffed() {
		for (int s = Shift.VALUES.length - 1; s >= 0; s--) {
			Shift shift = Shift.VALUES[s];
			for (int i = MAX_SLOTS - 1; i >= 0; i--)
				if (slots[i] != null && slots[i].runs(shift) && slots[i].workers[shift.ordinal()] != null)
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
	 * and which the rack's own order already expresses. A worker with something in its hands serves
	 * notice first: the load was picked up for the job it is leaving, and the job it is joining may have
	 * nowhere that should take it, so carrying it across would put items where they do not belong.
	 */
	private void rebalance(ServerLevel server) {
		// lastStaffed strictly decreases on every successful move, so this cannot run away -- but a
		// roster is at most thirty-six places and a loop over a mutating list deserves the belt.
		for (int guard = MAX_SLOTS * Shift.VALUES.length; guard > 0 && promoteOne(server); guard--)
			;
	}

	/** Lets a worker off a notice nobody is waiting on any more. */
	private void releaseNotice(ServerLevel server, Position position) {
		Slot slot = slots[position.slot()];
		UUID id = slot.workers[position.shift()
			.ordinal()];
		if (id == null || !(server.getEntity(id) instanceof Villager worker))
			return;
		WorkerData data = Workers.get(worker);
		if (data != null && data.isServingNotice())
			data.clearNotice();
	}

	private boolean promoteOne(ServerLevel server) {
		Position vacancy = nextVacancy();
		Position last = lastStaffed();
		if (vacancy == null || last == null || order(last) < order(vacancy)) {
			// Nothing to promote any more -- the vacancy was filled by a new hire, or the shifts
			// changed under it. Whoever was waiting to be moved is let off, because a notice is also
			// what stops a worker picking anything new up, and one left set on a worker nobody is
			// waiting for is a worker that quietly stops working.
			if (last != null)
				releaseNotice(server, last);
			return false;
		}

		Slot from = slots[last.slot()];
		UUID id = from.workers[last.shift()
			.ordinal()];
		// An unloaded worker cannot be promoted, and guessing at one is what the roster audit already
		// refuses to do. Left where it is; the next look will find it.
		if (!(server.getEntity(id) instanceof Villager worker))
			return false;

		Slot to = slots[vacancy.slot()];
		WorkerProgram programme = HardHatItem.getProgram(to.hat);
		if (!programme.hasTargets())
			return false;

		// A promotion is a different job, so whatever is in this worker's hands was picked up for
		// somewhere the new job may not deliver to -- and handing it on regardless would put items
		// where they do not belong, which is worse than dropping them. So it finishes first.
		WorkerData carrying = Workers.get(worker);
		if (carrying != null && !carrying.getHeld()
			.isEmpty()) {
			carrying.giveNotice(server.getGameTime() + NOTICE_TICKS);
			if (!carrying.noticeExpired(server.getGameTime()))
				return false;
			drop(worker);
		}

		from.workers[last.shift()
			.ordinal()] = null;
		Workers.employ(worker, to.hat, programme, GlobalPos.of(server.dimension(), worldPosition),
			vacancy.shift());
		to.workers[vacancy.shift()
			.ordinal()] = id;
		// Stamped, exactly as hire does. The place being moved into may have held somebody who died
		// or was struck off, and its seen clock would still carry that worker's last sighting -- so a
		// promoted worker that unloaded before the next audit was struck off on the strength of its
		// predecessor's timestamp, and sacked itself on its next load for a job it was doing properly.
		to.seen[vacancy.shift()
			.ordinal()] = server.getGameTime();
		rosterChanged();
		return true;
	}

	private void hire(ServerLevel server, Villager villager, Position vacancy) {
		Slot slot = slots[vacancy.slot()];
		Workers.employ(villager, slot.hat, HardHatItem.getProgram(slot.hat),
			GlobalPos.of(server.dimension(), worldPosition), vacancy.shift());
		slot.workers[vacancy.shift()
			.ordinal()] = villager.getUUID();
		slot.seen[vacancy.shift()
			.ordinal()] = server.getGameTime();
		rosterChanged();
	}

	/**
	 * Gives a job away when its holder has stopped turning up.
	 *
	 * <p>The station can replace a worker that dies, because the roster audit finds the entity gone.
	 * It cannot replace one that is merely never coming back — walled in, fallen somewhere, stranded
	 * across a gap — and
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
			if (slot == null)
				continue;
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

				// Let go on the spot. There is no point-of-interest ticket to hand back any more --
				// a worker holds no job site, which is what YieldJobSite forced -- so the roster entry
				// below is the whole of the bookkeeping, and freeing it is what lets somebody else be
				// hired into the job this one stopped turning up to.
				drop(worker);
				slot.workers[shift.ordinal()] = null;
				rosterChanged();
			}
		}
	}

	/**
	 * Lets go of workers whose shift the player has turned off, once they have finished.
	 *
	 * <p>A worker is left on the roster after its shift is switched off, holding a place the job no
	 * longer runs — which is nowhere, as far as every count and the fill order are concerned, so it is
	 * neither double-hired nor shown as staffing anything. What it gets is time: it stops taking new
	 * pickups and hands over what it is already carrying, and only then is it let go. Dropping a
	 * half-finished delivery on the floor the instant a toggle is clicked is items out of the player's
	 * own machines, scattered for a reason nothing in the world explains.
	 *
	 * <p>Turning the shift back on before it finishes cancels the whole thing, which is what a player
	 * who clicked the wrong toggle means.
	 */
	private void finishHandovers(ServerLevel server) {
		for (Slot slot : slots) {
			if (slot == null)
				continue;
			for (Shift shift : Shift.VALUES) {
				UUID id = slot.workers[shift.ordinal()];
				if (id == null)
					continue;

				WorkerData data = server.getEntity(id) instanceof Villager worker ? Workers.get(worker) : null;
				if (slot.runs(shift)) {
					// Its place is one the job still runs, so there is nothing to hand over. **It does
					// not clear the notice here**, although it used to: a worker being promoted is by
					// definition at a position its job still runs, so this branch cancelled the
					// promotion's notice on every twenty-tick look. giveNotice deliberately refuses to
					// push an existing deadline back, so re-setting it against a field something else
					// had just cleared meant the deadline never actually arrived -- and a worker
					// holding a stack none of its outputs would accept was therefore never moved and
					// never released, which is the one case the deadline exists to bound. Cancelling a
					// handover now happens in setShifts, where the thing that warrants it happens.
					continue;
				}

				if (!(server.getEntity(id) instanceof Villager worker) || data == null) {
					// Not there to be given notice, and its place is not one the job runs any more.
					slot.workers[shift.ordinal()] = null;
					rosterChanged();
					continue;
				}


				if (!data.getHeld()
					.isEmpty() && !data.noticeExpired(server.getGameTime())) {
					data.giveNotice(server.getGameTime() + NOTICE_TICKS);
					continue;
				}

				drop(worker);
				slot.workers[shift.ordinal()] = null;
				rosterChanged();
			}
		}
	}

	// --- sacking -------------------------------------------------------------------------

	/** Sacks everybody on the rack. What breaking the block does. */
	public void dismissAll() {
		for (Slot slot : slots)
			if (slot != null)
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
		// Everything past here is the server's. This also runs on the client, where the menu's own slot
		// sync writes into the rack through setStackInSlot -- and a client that answered by setting
		// blocks would be inventing a world the server has not agreed to, only to have it corrected on
		// the next update.
		if (level == null || level.isClientSide())
			return;

		if (getBlockState().getValue(WorkerStationBlock.HAS_JOB) != hasJob())
			level.setBlock(worldPosition, getBlockState().setValue(WorkerStationBlock.HAS_JOB, hasJob()), 3);
		// The screen reads the rack straight off the block entity, so the rack has to reach the client
		// at all. Cheap enough to send whole: a station changes when somebody is hired, dies or moves a
		// hat, which is a handful of times an hour, not a handful of times a tick.
		level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Nullable
	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	/**
	 * Whether this player may still have the rack open.
	 *
	 * <p>Not optional, and not decoration. {@code MenuBase.stillValid} returns <b>true unconditionally</b>
	 * for a content holder that does not implement this — so without it the station's menu would never
	 * close on its own, and the roster packet's one check, which is exactly this call, would be no check
	 * at all: the order of the rack and its shift toggles hire and fire villagers, and a client could
	 * drive them from anywhere in the world or after the block had been broken.
	 */
	@Override
	public boolean canPlayerUse(Player player) {
		if (level == null || level.getBlockEntity(worldPosition) != this)
			return false;
		return player.distanceToSqr(worldPosition.getX() + 0.5D, worldPosition.getY() + 0.5D,
			worldPosition.getZ() + 0.5D) <= 64.0D;
	}

	// --- serialization -------------------------------------------------------------------

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		java.util.Arrays.fill(slots, null);

		ListTag racked = tag.getList("Slots", Tag.TAG_COMPOUND);
		for (int i = 0; i < racked.size(); i++) {
			CompoundTag entry = racked.getCompound(i);
			// The place is saved with the job, because the rack has gaps and a job's place in it is the
			// player's statement of what matters most. An entry with no index is from before that was
			// true, and packing those from the top reproduces the order they used to have.
			int index = entry.contains("Index") ? entry.getInt("Index") : i;
			if (index < 0 || index >= MAX_SLOTS || slots[index] != null)
				continue;

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

			slots[index] = slot;
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);

		ListTag racked = new ListTag();
		for (int index = 0; index < MAX_SLOTS; index++) {
			Slot slot = slots[index];
			// A gap is saved by not being saved. An empty stack cannot be encoded at all, which is how
			// a job left holding a zero-count hat used to take the whole block entity down with it.
			if (slot == null || slot.hat.isEmpty())
				continue;

			CompoundTag entry = new CompoundTag();
			entry.putInt("Index", index);
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

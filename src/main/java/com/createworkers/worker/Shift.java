package com.createworkers.worker;

import org.jetbrains.annotations.Nullable;

import com.createworkers.CWConfig;

import net.minecraft.util.StringRepresentable;

/**
 * Which third of the day a worker is on the clock for.
 *
 * <p>A shift is <b>an offset, not a pair of times</b>. The operator sets one working day in the
 * config — {@code clockOn} to {@code clockOff} — and the three crews keep that same day, each one
 * started {@link #OFFSET} ticks later than the last. So a server that shortens the working day
 * shortens it for everybody, and one that moves dawn moves every crew with it, rather than three
 * sets of settings that could be made to contradict each other.
 *
 * <p><b>The span is exactly {@link #OFFSET} and is not adjustable.</b> That is the one length at
 * which the three crews tile the clock with no overlap and no gap: longer and two crews are on at the
 * changeover, shorter and the factory stands unstaffed between them. It used to be a setting, and
 * every value but the default was one of those two faults — so {@link #clockOff()} is
 * {@code clockOn + OFFSET} and {@code theShippedCrewsDoNotOverlap} pins it. What stops a chain of
 * workers stalling at the hand-over is muster, which wakes the next crew early enough to be at its
 * post, rather than an overlap that would put two crews on the clock at once.
 */
public enum Shift implements StringRepresentable {

	DAY("day"),
	EVENING("evening"),
	NIGHT("night");

	/** How much later each crew starts than the one before it: a third of a day. */
	public static final int OFFSET = WorkerShift.DAY_LENGTH / 3;

	/** Cached, because {@code values()} allocates and this is asked per worker per tick. */
	public static final Shift[] VALUES = values();

	private final String name;

	Shift(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return name;
	}

	/** The key for this crew's name, for the station screen and the readout. */
	public String translationKey() {
		return "createworkers.shift." + name;
	}

	/** How far into the day this crew's copy of the working day starts. */
	public int offset() {
		return ordinal() * OFFSET;
	}

	/** When this crew starts work, in ticks of the day. */
	public int clockOn() {
		return Math.floorMod(CWConfig.CLOCK_ON.get() + offset(), WorkerShift.DAY_LENGTH);
	}

	/** When this crew stops, in ticks of the day. */
	public int clockOff() {
		return Math.floorMod(CWConfig.CLOCK_ON.get() + OFFSET + offset(), WorkerShift.DAY_LENGTH);
	}

	/**
	 * @return the shift of this name, or {@code fallback} if it is not one.
	 *
	 * <p>Lenient on purpose: this reads save data, and a shift that has been renamed or removed should
	 * leave a worker on the day crew rather than throw a chunk load.
	 */
	public static Shift byName(@Nullable String name, Shift fallback) {
		if (name == null)
			return fallback;
		for (Shift shift : VALUES)
			if (shift.name.equals(name))
				return shift;
		return fallback;
	}
}

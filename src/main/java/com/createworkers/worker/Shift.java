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
 * <p>The offset is a third of a day rather than the length of a shift, which is what leaves the span
 * to the player: a span of exactly {@link #OFFSET} tiles the clock with no overlap and no gap, a
 * longer one has two crews on at the changeover — the default does, deliberately, because an
 * overlapping hand-over is what stops a chain of workers stalling at dusk — and a shorter one leaves
 * the factory unstaffed between crews, which is a legitimate thing to want and shows in the station's
 * readout rather than being silently corrected.
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
		return Math.floorMod(CWConfig.CLOCK_OFF.get() + offset(), WorkerShift.DAY_LENGTH);
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

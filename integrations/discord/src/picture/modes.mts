import type { Image } from "../dump/images.mts"
import { loopTicks } from "./timeline.mts"

/**
 * How a layer is drawn at a tick. Every field has a resting value, so `{}` draws the layer where the dump puts it. The
 * layer keeps showing the still of its own loop at that tick whatever its pose. Layers after the first only show where
 * the first one is, as in dumper/FORMAT.md, so a mode that hides layer 0 hides the whole picture.
 */
export interface Pose {
	/** Opacity from 0 to 1. At rest, 1. */
	readonly alpha?: number
	/** Size as a multiple of the layer's own, about its centre. At rest, 1. */
	readonly scale?: number
	/** Clockwise turn in degrees, about the layer's centre. At rest, 0. */
	readonly turn?: number
	/** Shift right and down in pixels. At rest, 0. */
	readonly dx?: number
	readonly dy?: number
}

/** What a render mode makes of one picture. */
export interface Plan {
	/** The drawing plays ticks from 0 up to this one. */
	readonly end: number
	/** How many times the drawing plays: 0 for ever. */
	readonly loops: number
	/** Ticks where the pose of some layer changes. Every tick where a layer changes its still starts a frame anyway. */
	readonly steps?: Iterable<number>
	/** When the mode cut a longer animation short: how many ticks the whole of it takes. */
	readonly uncut?: number
	/** How the `i`th layer is drawn at `tick`, or null to leave it out. */
	pose(i: number, tick: number): Pose | null
}

export type Mode = (image: Image) => Plan

/**
 * The longest looping drawing, in ticks: 60 seconds. A picture whose layers take longer to line up again is cut there.
 * In 0.13.8 that is 0.3% of the animated pictures; 74% loop within 40 ticks.
 */
const MAX_TICKS = 1200

/** The picture as EMI shows it: every layer at rest, cycling its stills, until they all line up again; and over. */
export const cycle: Mode = ({ layers }) => {
	const whole = loopTicks(layers)
	return { end: Math.min(whole, MAX_TICKS), loops: 0, uncut: whole > MAX_TICKS ? whole : undefined, pose: () => ({}) }
}

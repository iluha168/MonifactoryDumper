import type { Image } from "../dump/images.mts"
import { type Layer, loopTicks } from "./timeline.mts"

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

/** A layer along with its size in pixels: that of its stills, as stills.json gives it. */
export interface SizedLayer extends Layer {
	readonly w: number
	readonly h: number
}

/** A picture as a mode sees it: every layer with its size. */
export interface SizedImage extends Image {
	readonly layers: readonly SizedLayer[]
}

export type Mode = (image: SizedImage) => Plan

/**
 * The longest looping drawing, in ticks: 60 seconds. A picture whose layers take longer to line up again is cut there.
 * In 0.13.8 that is 0.3% of the animated pictures; 74% loop within 40 ticks.
 */
const MAX_TICKS = 1200

/** Starts slow and speeds up: 0 at 0, 1 at 1. */
function easeIn(t: number): number {
	return t * t
}

/** Starts fast and slows down: 0 at 0, 1 at 1. */
function easeOut(t: number): number {
	return 1 - easeIn(1 - t)
}

const ROLL_IN_APPEAR = 10
const ROLL_IN_ROTATE = 20
/** A widget at least this much of the card's width or height stays put with it: it is most likely a container of others. */
const ROLL_IN_STATIC = 0.8
const ROLL_IN_ASPECT = 0.5

export const modes = {
	/** EMI, staying truthful to the */
	default({ layers }) {
		const whole = loopTicks(layers)
		return { end: Math.min(whole, MAX_TICKS), loops: 0, uncut: whole > MAX_TICKS ? whole : undefined, pose: () => ({}) }
	},
	/**
	 * Every widget rolls in over the card, one after another; then one loop of {@link modes.default}. The card, and any
	 * widget nearly as wide or as tall as it or far from square, stay put throughout.
	 */
	screwIn(image) {
		// The card is layer 0, the size of the whole picture.
		const rolls = ({ w, h }: SizedLayer) => w < image.w * ROLL_IN_STATIC && h < image.h * ROLL_IN_STATIC && Math.max(w, h) / Math.min(w, h) - 1 <= ROLL_IN_ASPECT
		const rolling = image.layers.keys().filter((i) => i > 0 && rolls(image.layers[i])).toArray()
		/** The tick each rolling layer appears on, by layer. */
		const starts = new Map(rolling.map((i, k) => [i, Math.round(ROLL_IN_APPEAR * easeIn(rolling.length > 1 ? k / (rolling.length - 1) : 0))]))
		const intro = rolling.length ? Math.max(...starts.values()) + ROLL_IN_ROTATE : 0
		const whole = loopTicks(image.layers)
		return {
			end: intro + Math.min(whole, MAX_TICKS),
			loops: 1,
			// Up to and with the tick the last widget settles on.
			steps: Array.from({ length: intro + 1 }, (_, tick) => tick),
			uncut: whole > MAX_TICKS ? intro + whole : undefined,

			pose(i, tick) {
				const start = starts.get(i)
				if (start === undefined) return {}
				const since = tick - start
				if (since < 0) return null
				if (since >= ROLL_IN_ROTATE) return {}
				const p = easeOut(since / ROLL_IN_ROTATE)
				return { turn: 90 * (1 - p), scale: p }
			},
		}
	},
} as const satisfies Record<string, Mode>

export type ModeName = keyof typeof modes

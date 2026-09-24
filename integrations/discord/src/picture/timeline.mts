/** A layer of a record's `image`, as dumper/FORMAT.md describes it. */
export interface Layer {
	readonly x: number
	readonly y: number
	/** Still ids, one per entry of the loop. */
	readonly f: readonly number[]
	/** How many ticks each entry of {@link f} shows. */
	readonly d: readonly number[]
}

/** How many ticks the layer takes to show every entry once. */
export function period(layer: Layer): number {
	return layer.d.reduce((sum, d) => sum + d, 0)
}

/** The index into the layer's {@link Layer.f} that shows at `tick`, which may be any integer. */
export function entryAt(layer: Layer, tick: number): number {
	const length = period(layer)
	let t = (tick % length + length) % length
	for (let i = 0;; i++) {
		t -= layer.d[i]
		if (t < 0) return i
	}
}

/**
 * After how many ticks every layer is back at its first entry together: the least common multiple of their loops. It
 * reaches 1,185,600 ticks in 0.13.8, but for 99.7% of the animated pictures it is at most 1,200.
 */
export function loopTicks(layers: readonly Layer[]): number {
	return layers.map(period).reduce((lcm, length) => lcm / gcd(lcm, length) * length, 1)
}

/** The frames that play a picture: frame `i` shows tick `ticks[i]` for `durations[i]` ticks. */
export interface Timeline {
	readonly ticks: readonly number[]
	readonly durations: readonly number[]
}

/**
 * The frames from tick 0 up to `end`: one starts at every tick where some layer changes its still, and at every tick of
 * `steps`, which a render mode adds where it moves a layer.
 */
export function timeline(layers: readonly Layer[], end: number, steps: Iterable<number> = []): Timeline {
	const cuts = new Set([0, ...steps])
	for (const layer of layers) {
		if (layer.f.length === 1) continue
		for (let tick = 0; tick < end;) {
			for (const d of layer.d) {
				if (tick >= end) break
				cuts.add(tick)
				tick += d
			}
		}
	}
	const ticks = [...cuts].filter((tick) => tick >= 0 && tick < end).sort((a, b) => a - b)
	return { ticks, durations: ticks.map((tick, i) => (ticks[i + 1] ?? end) - tick) }
}

function gcd(a: number, b: number): number {
	while (b) [a, b] = [b, a % b]
	return a
}

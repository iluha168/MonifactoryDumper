import sharp from "sharp"

// libvips caches operations to redo them faster, and no drawing is ever encoded twice. One thread per encoding: the
// animation encoder is mostly one thread anyway, and every thread holds buffers of its own.
sharp.cache(false)
sharp.concurrency(1)

/**
 * Frames of one size, each `millis[i]` long, stacked top to bottom in `data`: straight (not premultiplied) RGBA, 8 bits
 * a channel, top row first.
 */
export interface Frames {
	readonly width: number
	readonly height: number
	readonly data: Uint8Array<ArrayBuffer>
	readonly millis: readonly number[]
}

/**
 * A lossless WebP that plays the frames `loops` times, or for ever if `loops` is 0; one frame makes a plain still.
 * libwebp's animation encoder keeps only what changed from frame to frame, off the main thread. A pixel whose alpha is
 * 0 may come back another colour, which means nothing anyway. Effort 2 of 6: more barely pays here.
 */
export async function encodeWebp({ width, height, data, millis }: Frames, loops: number): Promise<Uint8Array<ArrayBuffer>> {
	const raw = { width, height: height * millis.length, channels: 4, pageHeight: height } as const
	const webp = sharp(data, { raw }).webp({ lossless: true, quality: 25, effort: 2, loop: loops, delay: [...millis] })
	return new Uint8Array(await webp.toBuffer())
}

/**
 * Pixels whose channels spread over fewer levels than this are grey. A picture where most colourful pixels are fewer
 * than {@link MIN_COLOURFUL} of the opaque ones is taken to be grey as a whole.
 */
const GREY_CHROMA = 32
const MIN_COLOURFUL = 0.02

/**
 * The colour a picture's first frame mostly is, as 0xRRGGBB: its opaque pixels binned by 16 levels a channel, the
 * heaviest bin's pixels averaged. A colourful pixel weighs as much as its chroma, and a grey one nothing, so that a
 * slot's greys lose to the item in it.
 *
 * When next to nothing is colourful, as with an iron ingot, every pixel weighs the same, less those of the slot: a slot
 * is 18 GUI pixels across, its frame the outer one and the background showing at the corners inside it, where no item
 * reaches. Colours that only the slot has are passed over, unless nothing else is left.
 */
export async function dominantColor(image: Uint8Array): Promise<number> {
	const { data, info: { width, height } } = await sharp(image).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
	const opaque = (i: number) => data[i + 3] >= 128
	const binOf = (i: number) => data[i] >> 4 << 8 | data[i + 1] >> 4 << 4 | data[i + 2] >> 4

	let [count, colourful] = [0, 0]
	let [left, top, right, bottom] = [width, height, -1, -1]
	for (let i = 0; i < data.length; i += 4) {
		if (!opaque(i)) continue
		count++
		if (chroma(data, i) >= GREY_CHROMA) colourful++
		const [x, y] = [i / 4 % width, Math.floor(i / 4 / width)]
		;[left, top, right, bottom] = [Math.min(left, x), Math.min(top, y), Math.max(right, x), Math.max(bottom, y)]
	}
	if (!count) return 0

	const grey = colourful < count * MIN_COLOURFUL
	const slot = new Set<number>()
	if (grey) {
		const frame = Math.max(1, Math.round((right - left + 1) / 18))
		for (let y = top; y <= bottom; y++) {
			for (let x = left; x <= right; x++) {
				const inFrame = x < left + frame || x > right - frame || y < top + frame || y > bottom - frame
				const i = (y * width + x) * 4
				if (inFrame && opaque(i)) slot.add(binOf(i))
			}
		}
		for (const [x, y] of [[left, top], [right, top], [left, bottom], [right, bottom]]) {
			const i = ((y + Math.sign(top + bottom - 2 * y) * frame) * width + x + Math.sign(left + right - 2 * x) * frame) * 4
			if (opaque(i)) slot.add(binOf(i))
		}
	}

	const bins = new Map<number, { weight: number; r: number; g: number; b: number }>()
	for (let i = 0; i < data.length; i += 4) {
		const weight = !opaque(i) ? 0 : grey ? 1 : chroma(data, i)
		if (!weight) continue
		const key = binOf(i)
		const bin = bins.get(key) ?? { weight: 0, r: 0, g: 0, b: 0 }
		bin.weight += weight
		bin.r += data[i] * weight
		bin.g += data[i + 1] * weight
		bin.b += data[i + 2] * weight
		bins.set(key, bin)
	}
	const heaviest = (entries: Iterable<[number, { weight: number; r: number; g: number; b: number }]>) => {
		let best: { weight: number; r: number; g: number; b: number } | undefined
		for (const [, bin] of entries) if (!best || bin.weight > best.weight) best = bin
		return best
	}
	const best = heaviest(bins.entries().filter(([key]) => !slot.has(key))) ?? heaviest(bins.entries())!
	const channel = (sum: number) => Math.round(sum / best.weight)
	return channel(best.r) << 16 | channel(best.g) << 8 | channel(best.b)
}

function chroma(data: Uint8Array, i: number): number {
	return Math.max(data[i], data[i + 1], data[i + 2]) - Math.min(data[i], data[i + 1], data[i + 2])
}

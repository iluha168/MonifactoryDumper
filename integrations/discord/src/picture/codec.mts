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

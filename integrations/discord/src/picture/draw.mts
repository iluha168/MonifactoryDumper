import { encodeWebp } from "./codec.mts"
import type { Drawn, Job, ModeName, Reply, StillFile } from "./draw.worker.mts"
import type { StillTable } from "../dump/stills.mts"
import type { Image } from "../dump/images.mts"
import { dumpMeta } from "../dump/meta.mts"

export interface Drawing {
	readonly name: string
	readonly type: string
	readonly bytes: Uint8Array<ArrayBuffer>
	/** False when the animation was cut short, by the mode or by the pixel cap. */
	readonly seamless: boolean
	/** How long the drawing plays, and how long the whole animation would, in seconds. */
	readonly seconds: number
	readonly loopSeconds: number
}

/**
 * The recipe drawn by `mode` as a WebP. A worker of its own draws the frames (see draw.worker.mts), so that the bot
 * keeps answering other interactions meanwhile; sharp then encodes them on its own threads.
 */
export async function drawRecipe(image: Image, stills: StillTable, mode: ModeName = "cycle"): Promise<Drawing> {
	// A few kilobytes each, read here since the pak's file handle cannot go to the worker.
	const files = new Map<number, StillFile>()
	for (const layer of image.layers) {
		for (const id of layer.f) {
			if (!files.has(id)) files.set(id, { webp: stills.webp(id), width: stills.width(id), height: stills.height(id) })
		}
	}
	const job: Job = { image, mode, frameMillis: dumpMeta.frameMillis ?? 50, stills: files }

	const worker = new Worker(new URL("./draw.worker.mts", import.meta.url), { type: "module" })
	let drawn: Drawn
	try {
		drawn = await new Promise<Drawn>((resolve, reject) => {
			worker.onmessage = ({ data }: MessageEvent<Reply>) => "error" in data ? reject(data.error) : resolve(data)
			worker.onerror = (event) => {
				// Otherwise the error goes on up and takes the bot down with it.
				event.preventDefault()
				reject(new Error(`The drawing worker failed: ${event.message}`))
			}
			worker.postMessage(job, [...files.values()].map(({ webp }) => webp.buffer))
		})
	} finally {
		worker.terminate()
	}
	return {
		name: "recipe.webp",
		type: "image/webp",
		bytes: await encodeWebp(drawn.frames, drawn.loops),
		seamless: drawn.seamless,
		seconds: drawn.seconds,
		loopSeconds: drawn.loopSeconds,
	}
}

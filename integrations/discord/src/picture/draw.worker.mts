/**
 * Draws one recipe's frames with Skia, off the bot's thread: CanvasKit is wasm, and it runs on whichever thread calls it.
 * drawRecipe in draw.mts starts a worker per drawing and terminates it once the frames are back, which frees the whole wasm
 * heap, so nothing here deletes what it makes. Nothing here may import the dump either: a worker has no `Deno.args`.
 *
 * @module
 */
import { type CanvasKit, default as canvasKitModule, type Image as Still } from "canvaskit-wasm"
import { entryAt, timeline } from "./timeline.mts"
import * as modes from "./modes.mts"
import type { Image } from "../dump/images.mts"
import type { Frames } from "./codec.mts"

/**
 * The most pixels a drawing holds, over all its frames, before sharp encodes them: 100 MB of RGBA. An animation with
 * more is cut short. In 0.13.8 that is 4% of the pictures; 1% have over 50 million, and the most, 864 million.
 */
const MAX_PIXELS = 25_000_000

export type ModeName = keyof typeof modes

/** A still as the worker gets it: the WebP file, and the size stills.json gives it. */
export interface StillFile {
	readonly webp: Uint8Array<ArrayBuffer>
	readonly width: number
	readonly height: number
}

export interface Job {
	readonly image: Image
	readonly mode: ModeName
	readonly frameMillis: number
	/** Every still the image's layers show, by id. */
	readonly stills: ReadonlyMap<number, StillFile>
}

export interface Drawn {
	readonly frames: Frames
	readonly loops: number
	/** False when the animation was cut short, by the mode or by {@link MAX_PIXELS}. */
	readonly seamless: boolean
	/** How long the drawing plays, and how long the whole animation would, in seconds. */
	readonly seconds: number
	readonly loopSeconds: number
}

export type Reply = Drawn | { readonly error: unknown }

/** The worker's own global scope: Deno's types give every module a window's, since most of this program runs in one. */
const scope = self as unknown as Pick<Worker, "onmessage" | "postMessage">

// Set before anything is awaited, so that no job comes in with nothing listening for it.
const canvasKit = canvasKitModule.default()
scope.onmessage = async ({ data }: MessageEvent<Job>) => {
	let reply: Reply
	try {
		reply = draw(await canvasKit, data)
	} catch (error) {
		scope.postMessage({ error } satisfies Reply)
		return
	}
	scope.postMessage(reply, [reply.frames.data.buffer])
}

/**
 * Skia draws each frame: layer 0 is copied, alpha and all, and every later layer goes over it "source atop", which
 * keeps the canvas alpha, as dumper/FORMAT.md has it. Read back unpremultiplied, that gives FORMAT.md's pixels exactly:
 * its Python reference drew 560 pictures of 0.13.8, and all 560 match.
 */
function draw(ck: CanvasKit, { image, mode, frameMillis, stills }: Job): Drawn {
	const plan = modes[mode](image)
	const whole = timeline(image.layers, plan.end, plan.steps)
	const count = Math.max(1, Math.min(whole.ticks.length, Math.floor(MAX_PIXELS / (image.w * image.h))))
	const [ticks, durations] = [whole.ticks.slice(0, count), whole.durations.slice(0, count)]
	const end = ticks.at(-1)! + durations.at(-1)!

	const surface = ck.MakeSurface(image.w, image.h)
	if (!surface) throw new Error(`Skia has no ${image.w}x${image.h} surface`)
	const canvas = surface.getCanvas()
	const paint = new ck.Paint()
	// One layer's still is often another's too, in this frame or the next: decode each once. Skia decodes them exactly as
	// dwebp does: all 371,972 stills of 0.13.8 checked, 1,221 of them semi-transparent.
	const decoded = new Map<number, Still>()
	const still = (id: number) => {
		let picture = decoded.get(id)
		if (!picture) decoded.set(id, picture = decodeStill(ck, id, stills.get(id)))
		return picture
	}

	const size = image.w * image.h * 4
	const data = new Uint8Array(size * ticks.length)
	for (const [i, tick] of ticks.entries()) {
		canvas.clear(ck.TRANSPARENT)
		for (const [n, layer] of image.layers.entries()) {
			const pose = plan.pose(n, tick)
			if (!pose) continue
			const picture = still(layer.f[entryAt(layer, tick)])
			const [width, height] = [picture.width(), picture.height()]
			const scale = pose.scale ?? 1
			paint.setBlendMode(n === 0 ? ck.BlendMode.Src : ck.BlendMode.SrcATop)
			paint.setAlphaf(pose.alpha ?? 1)
			canvas.save()
			canvas.translate(layer.x + width / 2 + (pose.dx ?? 0), layer.y + height / 2 + (pose.dy ?? 0))
			canvas.rotate(pose.turn ?? 0, 0, 0)
			canvas.scale(scale, scale)
			// Nearest, so that a moved still stays pixel art rather than a blur of it.
			canvas.drawImageOptions(picture, -width / 2, -height / 2, ck.FilterMode.Nearest, ck.MipmapMode.None, paint)
			canvas.restore()
		}
		const pixels = canvas.readPixels(0, 0, {
			width: image.w,
			height: image.h,
			colorType: ck.ColorType.RGBA_8888,
			alphaType: ck.AlphaType.Unpremul,
			colorSpace: ck.ColorSpace.SRGB,
		})
		data.set(pixels as Uint8Array, i * size)
	}
	return {
		frames: { width: image.w, height: image.h, data, millis: durations.map((d) => d * frameMillis) },
		loops: plan.loops,
		seamless: plan.uncut === undefined && end === plan.end,
		seconds: end * frameMillis / 1000,
		loopSeconds: (plan.uncut ?? plan.end) * frameMillis / 1000,
	}
}

function decodeStill(ck: CanvasKit, id: number, file: StillFile | undefined): Still {
	if (!file) throw new Error(`Still ${id} was not sent along`)
	const picture = ck.MakeImageFromEncoded(file.webp)
	if (!picture) throw new Error(`Still ${id} does not decode`)
	if (picture.width() !== file.width || picture.height() !== file.height) {
		throw new Error(`Still ${id} decodes to ${picture.width()}x${picture.height()}, but stills.json says ${file.width}x${file.height}`)
	}
	return picture
}

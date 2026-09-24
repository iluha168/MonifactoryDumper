import { type CanvasKit, default as canvasKitModule, type Image as Still } from "canvaskit-wasm"
import { entryAt, timeline } from "./timeline.mts"
import { encodeWebp } from "./codec.mts"
import { cycle, type Mode } from "./modes.mts"
import type { StillTable } from "../dump/stills.mts"
import type { Image } from "../dump/images.mts"
import { dumpMeta } from "../dump/meta.mts"

let canvasKit: CanvasKit = await canvasKitModule.default()
async function restartCanvasKit(): Promise<void> {
	canvasKit = await canvasKitModule.default()
}

/**
 * The most pixels a drawing holds, over all its frames, before sharp encodes them: 100 MB of RGBA. An animation with
 * more is cut short. In 0.13.8 that is 4% of the pictures; 1% have over 50 million, and the most, 864 million.
 */
const MAX_PIXELS = 25_000_000

export interface Drawing {
	readonly name: string
	readonly type: string
	readonly bytes: Uint8Array<ArrayBuffer>
	/** False when the animation was cut short, by the mode or by {@link MAX_PIXELS}. */
	readonly seamless: boolean
	/** How long the drawing plays, and how long the whole animation would, in seconds. */
	readonly seconds: number
	readonly loopSeconds: number
}

/**
 * The recipe drawn by `mode` as a WebP. Skia draws each frame: layer 0 is copied, alpha and all, and every later layer
 * goes over it "source atop", which keeps the canvas alpha, as dumper/FORMAT.md has it. Read back unpremultiplied,
 * that gives FORMAT.md's pixels exactly: its Python reference drew 560 pictures of 0.13.8, and all 560 match.
 */
export async function drawRecipe(image: Image, stills: StillTable, mode: Mode = cycle): Promise<Drawing> {
	const plan = mode(image)
	const frameMillis = dumpMeta.frameMillis ?? 50
	const whole = timeline(image.layers, plan.end, plan.steps)
	const count = Math.max(1, Math.min(whole.ticks.length, Math.floor(MAX_PIXELS / (image.w * image.h))))
	const [ticks, durations] = [whole.ticks.slice(0, count), whole.durations.slice(0, count)]
	const end = ticks.at(-1)! + durations.at(-1)!

	const ck = canvasKit
	const surface = ck.MakeSurface(image.w, image.h)
	if (!surface) throw new Error(`Skia has no ${image.w}x${image.h} surface`)
	const canvas = surface.getCanvas()
	const paint = new ck.Paint()
	// One layer's still is often another's too, in this frame or the next: decode each once. Skia decodes them exactly as
	// dwebp does: all 371,972 stills of 0.13.8 checked, 1,221 of them semi-transparent.
	const decoded = new Map<number, Still>()
	const still = (id: number) => {
		let picture = decoded.get(id)
		if (!picture) decoded.set(id, picture = decodeStill(ck, stills, id))
		return picture
	}

	const size = image.w * image.h * 4
	const frames = new Uint8Array(size * ticks.length)
	try {
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
			frames.set(pixels as Uint8Array, i * size)
		}
		return {
			name: "recipe.webp",
			type: "image/webp",
			bytes: await encodeWebp({ width: image.w, height: image.h, data: frames, millis: durations.map((d) => d * frameMillis) }, plan.loops),
			seamless: plan.uncut === undefined && end === plan.end,
			seconds: end * frameMillis / 1000,
			loopSeconds: (plan.uncut ?? plan.end) * frameMillis / 1000,
		}
	} finally {
		for (const picture of decoded.values()) picture.delete()
		paint.delete()
		// delete() would leave the surface's pixels behind in the wasm memory.
		surface.dispose()
		await restartCanvasKit()
	}
}

function decodeStill(ck: CanvasKit, stills: StillTable, id: number): Still {
	const picture = ck.MakeImageFromEncoded(stills.webp(id))
	if (!picture) throw new Error(`Still ${id} does not decode`)
	if (picture.width() !== stills.width(id) || picture.height() !== stills.height(id)) {
		const size = `${picture.width()}x${picture.height()}`
		picture.delete()
		throw new Error(`Still ${id} decodes to ${size}, but stills.json says ${stills.width(id)}x${stills.height(id)}`)
	}
	return picture
}

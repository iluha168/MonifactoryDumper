import z from "zod"
import type { Layer } from "../picture/timeline.mts"

/** A record's `image`, as dumper/FORMAT.md describes it. */
export const imageSchema = z.object({
	w: z.int().positive(),
	h: z.int().positive(),
	layers: z.array(
		z.object({
			x: z.int().nonnegative(),
			y: z.int().nonnegative(),
			f: z.array(z.int().nonnegative()).min(1),
			d: z.array(z.int().positive()).min(1),
		}).refine(({ f, d }) => f.length === d.length, "f and d differ in length"),
	).min(1),
})

export interface Image {
	readonly w: number
	readonly h: number
	readonly layers: readonly Layer[]
}

/**
 * Images per deflate block. A lookup inflates one block, some 120 kB here; all of 0.13.8's images come to 1.6 MB.
 */
const IMAGES_PER_BLOCK = 1024

/**
 * Every record's image, packed. As objects, 0.13.8's 1.7 million layers take some 330 MB of heap, most of it in the
 * two one-element arrays of each static layer. Packed, an image is its size, its layer count, and each layer's
 * position, loop length, still ids and durations as unsigned LEB128 varints. A still id is written as the zigzagged
 * difference from the one before it in the image, since the dumper numbers stills in the order recipes first use them.
 * A static layer's single duration of 1 is left out. That is 14.3 MB for 0.13.8, and deflated in blocks of
 * {@link IMAGES_PER_BLOCK} images, 1.6 MB.
 */
export class ImageTable {
	private building: { bytes: Uint8Array; length: number } | null = { bytes: new Uint8Array(1 << 20), length: 0 }
	/** Where each image starts in the packed bytes before they were deflated. */
	private starts: number[] | Uint32Array = []
	/** Every block's raw deflate stream, back to back. */
	private blocks = new Uint8Array()
	/** Where each block's stream ends in {@link blocks}. */
	private blockEnds = new Uint32Array()
	/** The block {@link read} inflated last, so that reading images in order inflates each block once. */
	private inflated: { block: number; bytes: Promise<Uint8Array> } | null = null

	/** Packs `image` and returns its handle. Only valid until {@link seal}. */
	add(image: Image): RecipeImage {
		if (!this.building || this.starts instanceof Uint32Array) throw new Error("The table is sealed")
		const start = this.building.length
		this.varint(image.w)
		this.varint(image.h)
		this.varint(image.layers.length)
		let previous = 0
		for (const { x, y, f, d } of image.layers) {
			this.varint(x)
			this.varint(y)
			this.varint(f.length)
			for (const still of f) {
				const delta = still - previous
				this.varint(delta >= 0 ? delta * 2 : -delta * 2 - 1)
				previous = still
			}
			if (f.length > 1) { for (const ticks of d) this.varint(ticks) }
		}
		this.starts.push(start)
		return new RecipeImage(this, this.starts.length - 1)
	}

	/** Deflates what {@link add} packed. The table only reads from then on. */
	async seal(): Promise<void> {
		if (!this.building || this.starts instanceof Uint32Array) throw new Error("The table is sealed")
		const { bytes, length } = this.building
		const starts = Uint32Array.from(this.starts)
		const blocks = []
		for (let first = 0; first < starts.length; first += IMAGES_PER_BLOCK) {
			const end = starts[first + IMAGES_PER_BLOCK] ?? length
			blocks.push(await through(bytes.subarray(starts[first], end), new CompressionStream("deflate-raw")))
		}
		this.blockEnds = new Uint32Array(blocks.length)
		let size = 0
		blocks.forEach((block, i) => this.blockEnds[i] = size += block.length)
		this.blocks = new Uint8Array(size)
		blocks.forEach((block, i) => this.blocks.set(block, i ? this.blockEnds[i - 1] : 0))
		this.starts = starts
		this.building = null
	}

	/** Image `index`, unpacked. */
	async read(index: number): Promise<Image> {
		if (!(this.starts instanceof Uint32Array)) throw new Error("The table is not sealed yet")
		const block = Math.floor(index / IMAGES_PER_BLOCK)
		if (this.inflated?.block !== block) {
			const stream = this.blocks.subarray(block ? this.blockEnds[block - 1] : 0, this.blockEnds[block])
			this.inflated = { block, bytes: through(stream, new DecompressionStream("deflate-raw")) }
		}
		const bytes = await this.inflated.bytes
		let at = this.starts[index] - this.starts[block * IMAGES_PER_BLOCK]
		const varint = () => {
			let value = 0
			for (let shift = 1;; shift *= 128) {
				const byte = bytes[at++]
				value += (byte & 0x7F) * shift
				if (byte < 0x80) return value
			}
		}
		const w = varint(), h = varint()
		let previous = 0
		const layers = Array.from({ length: varint() }, () => {
			const x = varint(), y = varint()
			const f = Array.from({ length: varint() }, () => {
				const zigzag = varint()
				return previous += zigzag % 2 ? -(zigzag + 1) / 2 : zigzag / 2
			})
			const d = f.length > 1 ? f.map(varint) : [1]
			return { x, y, f, d }
		})
		return { w, h, layers }
	}

	private varint(value: number): void {
		const building = this.building!
		if (building.length + 8 > building.bytes.length) {
			const grown = new Uint8Array(building.bytes.length * 2)
			grown.set(building.bytes)
			building.bytes = grown
		}
		while (value >= 0x80) {
			building.bytes[building.length++] = value & 0x7F | 0x80
			value = Math.floor(value / 128)
		}
		building.bytes[building.length++] = value
	}
}

/**
 * A record's image, held as its place in an {@link ImageTable}: 40 bytes of heap a record. A Uint8Array of its own would
 * take some 300, a view into a shared one 64, and a closure 96.
 */
export class RecipeImage {
	constructor(private readonly table: ImageTable, private readonly index: number) {}

	read(): Promise<Image> {
		return this.table.read(this.index)
	}
}

async function through(bytes: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array<ArrayBuffer>> {
	return await new Response(new Blob([bytes.slice()]).stream().pipeThrough(stream)).bytes()
}

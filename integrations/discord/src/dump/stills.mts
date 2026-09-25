import { join } from "node:path"
import { dumpDir } from "./path.mts"
import { dumpMeta } from "./meta.mts"
import { exit, ExitCodes } from "../cli.mts"

/**
 * Where each still of stills.pak is, and its size. The pak itself stays on disk: it is lossless WebP already, 174 MB in
 * 0.13.8 that no block compression shrinks, so each still is read when a picture needs it, a few kilobytes at a time.
 */
export class StillTable {
	private constructor(
		/** Still `i` is bytes `offsets[i]` to `offsets[i + 1]` of the pak. */
		private readonly offsets: Uint32Array,
		private readonly widths: Uint16Array,
		private readonly heights: Uint16Array,
		private readonly pak: Deno.FsFile,
	) {}

	/**
	 * Reads stills.json's numbers straight into the table. It holds nothing but rows of four integers, and JSON.parse
	 * would make an array of each row first: 371,972 of them in 0.13.8, which left the process 30 MB larger for good.
	 */
	static async load(directory: string, count: number): Promise<StillTable> {
		const text = await Deno.readFile(join(directory, "stills.json"))
		const offsets = new Uint32Array(count + 1)
		const widths = new Uint16Array(count)
		const heights = new Uint16Array(count)
		let numbers = 0, value = -1
		for (const byte of text) {
			if (byte >= 0x30 && byte <= 0x39) {
				value = (value === -1 ? 0 : value * 10) + byte - 0x30
				continue
			}
			if (value === -1) continue
			const row = numbers >> 2
			if (row >= count) exit(`stills.json has more than the ${count} rows meta.json says`, ExitCodes.MALFORMED_DUMP)
			switch (numbers++ & 3) {
				case 0:
					if (value !== offsets[row]) exit(`stills.json row ${row} starts at ${value}, not ${offsets[row]}`, ExitCodes.MALFORMED_DUMP)
					break
				case 1:
					if (offsets[row] + value > 0xFFFFFFFF) exit(`stills.json row ${row} ends beyond 4 GiB`, ExitCodes.MALFORMED_DUMP)
					offsets[row + 1] = offsets[row] + value
					break
				case 2:
					widths[row] = value
					break
				case 3:
					heights[row] = value
			}
			value = -1
		}
		if (numbers !== count * 4) exit(`stills.json has ${numbers / 4} rows, but meta.json says ${count}`, ExitCodes.MALFORMED_DUMP)
		return new StillTable(offsets, widths, heights, await Deno.open(join(directory, "stills.pak")))
	}

	get length(): number {
		return this.widths.length
	}

	width(id: number): number {
		return this.widths[this.check(id)]
	}

	height(id: number): number {
		return this.heights[this.check(id)]
	}

	/** Still `id`'s WebP file. */
	webp(id: number): Uint8Array<ArrayBuffer> {
		const bytes = new Uint8Array(this.offsets[this.check(id) + 1] - this.offsets[id])
		// Synchronous, so that no other read moves the file's position between the seek and the read.
		this.pak.seekSync(this.offsets[id], Deno.SeekMode.Start)
		for (let read = 0; read < bytes.length;) {
			const got = this.pak.readSync(bytes.subarray(read))
			if (!got) throw new Error(`stills.pak ends inside still ${id}`)
			read += got
		}
		return bytes
	}

	private check(id: number): number {
		if (!Number.isInteger(id) || id < 0 || id >= this.widths.length) {
			throw new RangeError(`No still ${id}: there are ${this.widths.length}`)
		}
		return id
	}
}

/** Null for a data-only dump, which has no pictures. */
export const stills = dumpMeta.images && dumpMeta.stills !== null ? await StillTable.load(dumpDir, dumpMeta.stills) : null

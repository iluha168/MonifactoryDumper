import z from "zod"
import { join } from "node:path"
import { dumpDir } from "./path.mts"
import { stills } from "./stills.mts"
import { matterNames, type MatterType, matterTypes } from "./matterNames.mts"
import { recipes } from "./recipes.mts"
import type { Layer } from "../picture/timeline.mts"

/** A matter type's list in a row of still_uses.json: `items` for `item`. */
const listOf = (type: MatterType) => `${type}s` as const

const rowSchema = z.object({
	...Object.fromEntries(matterTypes.map((type) => [listOf(type), z.array(z.string()).optional()])) as Record<ReturnType<typeof listOf>, z.ZodOptional<z.ZodArray<z.ZodString>>>,
	texts: z.array(z.string()).optional(),
})

/**
 * How a layer shows a matter, compared field by field, lower first: the most texts, other matter, and NBT any of its
 * stills has, then more distinct stills first, so that an animated slot wins over one frame of it.
 */
type Rank = readonly [texts: number, others: number, nbt: number, stills: number]

interface Pick {
	readonly layer: Layer
	readonly rank: Rank
}

/**
 * What still_uses.json says of every still, as far as widgets care: the matter it shows, each as an index into
 * {@link matters}, and how many texts it has. Held flat, since there are some 460,000 stills in 0.13.8.
 */
interface StillMatter {
	/** `type`, then a NUL, then the id: every matter any still shows. */
	readonly matters: readonly string[]
	/** Still `i`'s matter is entries `starts[i]` to `starts[i + 1]` of {@link shown} and {@link nbt}. */
	readonly starts: Uint32Array
	readonly shown: Int32Array
	/** 1 where the entry was drawn with NBT. */
	readonly nbt: Uint8Array
	readonly texts: Uint16Array
}

/** A matter of matter_names.json. */
export interface Matter {
	readonly type: MatterType
	readonly id: string
}

/** What a recipe's picture shows, over every frame of every layer. */
export interface DrawnMatter {
	/**
	 * Every matter its stills were drawn with. A still is one picture, stored once for every draw that came out the
	 * same, so where two things look alike (Stone and Infested Stone), a still names both.
	 */
	readonly every: readonly Matter[]
	/**
	 * The matter of those stills whose matter all has one name, such as Lava and Flowing Lava: what the picture shows for
	 * sure, as far as a player tells things apart.
	 */
	readonly sure: readonly Matter[]
}

/**
 * Indices into {@link WidgetTable}'s matters, for each recipe of recipes.json by its index: list `r` is `values[starts[r]]`
 * to `values[starts[r + 1] - 1]`.
 */
interface RecipeMatter {
	readonly starts: Uint32Array
	readonly values: Int32Array
}

function packRecipeMatter(lists: readonly (readonly number[] | null)[]): RecipeMatter {
	const starts = new Uint32Array(lists.length + 1)
	lists.forEach((list, r) => starts[r + 1] = starts[r] + (list?.length ?? 0))
	const values = new Int32Array(starts[lists.length])
	lists.forEach((list, r) => list && values.set(list, starts[r]))
	return { starts, values }
}

/**
 * For every matter of matter_names.json, the recipe layer that shows it the clearest: its slot, as some recipe drew it,
 * animated if the slot is. Every still of the layer shows the matter, so a slot cycling through a tag is no widget of
 * any one of its entries. See still_uses.json in dumper/FORMAT.md. Also what every recipe's picture shows.
 */
export class WidgetTable {
	private constructor(
		private readonly picks: Readonly<Record<MatterType, ReadonlyMap<string, Layer>>>,
		private readonly matters: readonly Matter[],
		/** 1 for each recipe with a picture. */
		private readonly pictured: Uint8Array,
		private readonly every: RecipeMatter,
		private readonly sure: RecipeMatter,
	) {}

	/** The layer showing `id` of `type` the clearest, if any recipe shows it at all. Its position is that in its recipe. */
	widget(type: MatterType, id: string): Layer | undefined {
		return this.picks[type].get(id)
	}

	/** What the picture of the recipe at `index` of recipes.json shows, if it has one. */
	drawn(index: number): DrawnMatter | undefined {
		if (!this.pictured[index]) return undefined
		const read = ({ starts, values }: RecipeMatter) => Array.from(values.subarray(starts[index], starts[index + 1]), (matter) => this.matters[matter])
		return { every: read(this.every), sure: read(this.sure) }
	}

	/** Goes through every layer of every recipe, keeping the one with the least {@link Rank} per matter, the first of equals. */
	static async load(path: string, count: number): Promise<WidgetTable> {
		const { matters, starts, shown, nbt, texts } = await readStillMatter(path, count)
		const nbtOf = (still: number, matter: number) => {
			let found = -1
			for (let e = starts[still]; e < starts[still + 1]; e++) {
				if (shown[e] === matter) found = found === -1 ? nbt[e] : Math.min(found, nbt[e])
			}
			return found
		}

		/** What a player tells matter number `matter` by. */
		const nameOf = (matter: number) => {
			const [type, id] = matters[matter].split("\0") as [MatterType, string]
			return `${type}\0${matterNames[type].get(id) ?? id}`
		}
		const best = new Map<number, Pick>()
		const every: (number[] | null)[] = []
		const sure: (number[] | null)[] = []
		for (const recipe of recipes) {
			if (!recipe.image) {
				every.push(null)
				sure.push(null)
				continue
			}
			const drawn = new Set<number>()
			const drawnSure = new Set<number>()
			for (const layer of (await recipe.image.read()).layers) {
				const distinct = [...new Set(layer.f)]
				for (const still of distinct) {
					const matter = new Set(shown.subarray(starts[still], starts[still + 1]))
					for (const one of matter) drawn.add(one)
					if (new Set(matter.values().map(nameOf)).size === 1) matter.forEach((one) => drawnSure.add(one))
				}
				const first = distinct[0]
				candidates: for (let e = starts[first]; e < starts[first + 1]; e++) {
					const matter = shown[e]
					let rank: Rank = [0, 0, 0, -distinct.length]
					for (const still of distinct) {
						const withNbt = nbtOf(still, matter)
						if (withNbt === -1) continue candidates
						rank = [Math.max(rank[0], texts[still]), Math.max(rank[1], starts[still + 1] - starts[still] - 1), Math.max(rank[2], withNbt), rank[3]]
					}
					const known = best.get(matter)
					if (!known || compare(rank, known.rank) < 0) best.set(matter, { layer, rank })
				}
			}
			every.push([...drawn])
			sure.push([...drawnSure])
		}

		const picks = Object.fromEntries(matterTypes.map((type) => [type, new Map<string, Layer>()])) as Record<MatterType, Map<string, Layer>>
		for (const [matter, { layer }] of best) {
			const [type, id] = matters[matter].split("\0") as [MatterType, string]
			picks[type].set(id, layer)
		}
		const decoded = matters.map((matter) => {
			const [type, id] = matter.split("\0") as [MatterType, string]
			return { type, id }
		})
		return new WidgetTable(
			picks,
			decoded,
			Uint8Array.from(recipes, (recipe) => recipe.image ? 1 : 0),
			packRecipeMatter(every),
			packRecipeMatter(sure),
		)
	}
}

/** Rows that name no matter are not parsed at all: most are backgrounds, arrows and text. */
async function readStillMatter(path: string, count: number): Promise<StillMatter> {
	const text = await Deno.readFile(path)
	const decoder = new TextDecoder()
	const needles = matterTypes.map((type) => `"${listOf(type)}"`)
	const indices = new Map<string, number>()
	const starts = new Uint32Array(count + 1)
	const shown: number[] = []
	const nbt: number[] = []
	const texts = new Uint16Array(count)
	let still = 0
	for (let start = 0; start < text.length;) {
		let end = text.indexOf(0x0A, start)
		if (end === -1) end = text.length
		const line = decoder.decode(text.subarray(start, end)).replace(/,$/, "")
		start = end + 1
		if (line === "[" || line === "]" || line === "") continue
		if (still >= count) throw new Error(`still_uses.json has more than the ${count} rows there are stills`)
		if (needles.some((needle) => line.includes(needle))) {
			let row: z.infer<typeof rowSchema>
			try {
				row = rowSchema.parse(JSON.parse(line))
			} catch (cause) {
				throw new Error(`Row ${still} of still_uses.json is malformed`, { cause })
			}
			texts[still] = Math.min(row.texts?.length ?? 0, 0xFFFF)
			for (const type of matterTypes) {
				// Matter with NBT is written as /give writes it: its id, then the NBT in braces.
				for (const matter of row[listOf(type)] ?? []) {
					const brace = matter.indexOf("{")
					const key = `${type}\0${brace === -1 ? matter : matter.slice(0, brace)}`
					let index = indices.get(key)
					if (index === undefined) indices.set(key, index = indices.size)
					shown.push(index)
					nbt.push(brace === -1 ? 0 : 1)
				}
			}
		}
		starts[++still] = shown.length
	}
	if (still !== count) throw new Error(`still_uses.json has ${still} rows, but there are ${count} stills`)
	return { matters: [...indices.keys()], starts, shown: Int32Array.from(shown), nbt: Uint8Array.from(nbt), texts }
}

function compare(a: Rank, b: Rank): number {
	for (const [i, value] of a.entries()) {
		if (value !== b[i]) return value - b[i]
	}
	return 0
}

/** Null for a data-only dump, and for one whose renderer predates still_uses.json. */
export const widgets = stills
	? await WidgetTable.load(join(dumpDir, "still_uses.json"), stills.length).catch((cause) => {
		if (cause instanceof Deno.errors.NotFound) return null
		throw new Error("Failed to parse dump still uses", { cause })
	})
	: null

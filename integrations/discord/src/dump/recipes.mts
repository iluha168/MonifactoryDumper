import z from "zod"
import { join } from "node:path"
import { dumpDir } from "./path.mts"
import { imageSchema, ImageTable } from "./images.mts"

/**
 * One value for every equal value it is handed: the first one, frozen. JSON.parse makes a new object of every
 * occurrence, and V8 shares only their hidden class and strings shorter than 10 characters. Most of a record's
 * ingredients occur in thousands of others (EMI's empty slot 83,163 times in 0.13.8), so sharing them is most of what
 * keeps the records small.
 */
function interner() {
	const strings = new Map<string, string>()
	const values = new Map<string, unknown>()
	return {
		string(value: string): string {
			const known = strings.get(value)
			if (known !== undefined) return known
			strings.set(value, value)
			return value
		},
		/** `value` must hold only values this interner has already handed out, or primitives. */
		value<T extends object | null>(value: T): T {
			const key = JSON.stringify(value)
			const known = values.get(key)
			if (known !== undefined) return known as T
			values.set(key, Object.freeze(value))
			return value
		},
		/** Forgets every value, so that only what holds on to them keeps them. */
		clear(): void {
			strings.clear()
			values.clear()
		},
	}
}

/** The records of recipes.json, parsed into the shape dumper/FORMAT.md describes, sharing every value they can. */
function recordSchema(images: ImageTable, intern: ReturnType<typeof interner>) {
	const string = z.string().transform(intern.string)
	const strings = z.array(string).transform(intern.value)
	const amount = { n: z.number(), c: z.number().optional() }
	const enchantments = z.array(z.tuple([string, z.int()]).readonly()).readonly()
	const ingredient = z.discriminatedUnion("k", [
		z.object({
			k: z.literal("s"),
			t: string,
			id: string,
			...amount,
			nbt: z.union([z.literal(0), z.literal(1)]),
			nbtk: strings.optional(),
			nbth: z.int().optional(),
			ench: z.object({ Enchantments: enchantments.optional(), StoredEnchantments: enchantments.optional() }).readonly().optional(),
			rem: string.optional(),
		}),
		z.object({ k: z.literal("t"), tag: string, reg: string, ...amount, matches: z.int() }),
		z.object({ k: z.literal("m"), cls: string, ...amount, count: z.int(), ids: strings, parts: z.int().optional() }),
	]).readonly().nullable().transform(intern.value)
	const ingredients = z.array(ingredient).readonly().transform(intern.value)
	return z.object({
		emiRecipeId: string.nullable(),
		underlyingRecipeId: string.nullable(),
		cat: string,
		cls: string,
		w: z.int(),
		h: z.int(),
		in: ingredients,
		cats: ingredients,
		out: ingredients,
		outFrom: z.literal("slots").optional(),
		image: imageSchema.transform((image) => images.add(image)).nullable(),
	}).readonly()
}

export type Recipe = z.infer<ReturnType<typeof recordSchema>>
export type Ingredient = Recipe["in"][number]

/**
 * Every record of recipes.json, in file order, as plain frozen objects to search through. Only the pictures are kept
 * packed, in an {@link ImageTable}: `image.read()` unpacks one. In 0.13.8 that is 47 MB of heap, where JSON.parse of
 * the whole file makes 655 MB.
 *
 * Loading takes some 6 seconds and several hundred megabytes, most of it garbage that V8 collects within a minute of
 * idling. Run with `MALLOC_MMAP_THRESHOLD_=65536`, or glibc keeps some 45 MB more of what the load freed.
 */
async function load(path: string): Promise<readonly Recipe[]> {
	const images = new ImageTable()
	const intern = interner()
	const schema = recordSchema(images, intern)
	const text = await Deno.readFile(path)
	const decoder = new TextDecoder()
	const recipes: Recipe[] = []
	for (let start = 0; start < text.length;) {
		let end = text.indexOf(0x0A, start)
		if (end === -1) end = text.length
		const line = decoder.decode(text.subarray(start, end)).replace(/,$/, "")
		start = end + 1
		if (line !== "[" && line !== "]" && line !== "") {
			try {
				recipes.push(schema.parse(JSON.parse(line)))
			} catch (cause) {
				throw new Error(`Record ${recipes.length} of recipes.json is malformed`, { cause })
			}
		}
	}
	intern.clear()
	await images.seal()
	return Object.freeze(recipes)
}

export const recipes = await load(join(dumpDir, "recipes.json"))

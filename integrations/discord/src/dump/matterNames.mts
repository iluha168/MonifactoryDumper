import { join } from "node:path"
import { dumpDir } from "./path.mts"
import z from "zod"

const names = z.record(z.string(), z.string())
	.transform((table): ReadonlyMap<string, string> => new Map(Object.entries(table)))

const schema = z.strictObject({
	item: names,
	fluid: names,
})

/**
 * The English name of every registered item and fluid, by id, as the game shows it. A stack's `t` in recipes.json names
 * the table its `id` is in. See matter_names.json in dumper/FORMAT.md.
 */
export const matterNames = await Deno
	.readTextFile(join(dumpDir, "matter_names.json"))
	.then(JSON.parse)
	.then(schema.parseAsync.bind(schema))
	.catch((cause) => {
		throw new Error("Failed to parse dump matter names", { cause })
	})

export type MatterType = keyof typeof matterNames
/** Every table of matter_names.json: the kinds of matter the dump knows. */
export const matterTypes = Object.keys(matterNames) as MatterType[]

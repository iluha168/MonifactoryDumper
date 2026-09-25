import { join } from "node:path"
import { dumpDir } from "./path.mts"
import z from "zod"

const registry = z.record(z.string(), z.array(z.string()))
	.transform((table): ReadonlyMap<string, readonly string[]> => new Map(Object.entries(table)))

const schema = z.looseObject({
	"minecraft:item": registry,
	"minecraft:fluid": registry,
})

/**
 * The item and fluid tags, by tag id, with their entries. A tag ingredient in recipes.json names its registry in `reg`.
 * See tags.json in dumper/FORMAT.md. The other registries' tags are dropped.
 */
export const tags = await Deno
	.readTextFile(join(dumpDir, "tags.json"))
	.then(JSON.parse)
	.then(schema.parseAsync.bind(schema))
	.then((parsed) => ({
		item: parsed["minecraft:item"],
		fluid: parsed["minecraft:fluid"],
	}))
	.catch((cause) => {
		throw new Error("Failed to parse dump tags", { cause })
	})

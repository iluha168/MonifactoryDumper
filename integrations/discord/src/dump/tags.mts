import { join } from "node:path"
import { dumpDir } from "./path.mts"
import { type MatterType, matterTypes } from "./matterNames.mts"
import z from "zod"

/** A matter type's registry in tags.json: `minecraft:item` for `item`. */
export const registryOf = (type: MatterType) => `minecraft:${type}` as const

const registry = z.record(z.string(), z.array(z.string()))
	.transform((table): ReadonlyMap<string, readonly string[]> => new Map(Object.entries(table)))

const schema = z.looseObject(
	Object.fromEntries(matterTypes.map((type) => [registryOf(type), registry])) as Record<ReturnType<typeof registryOf>, typeof registry>,
)

/**
 * The tags of every matter type, by tag id, with their entries. A tag ingredient in recipes.json names its registry in
 * `reg`. See tags.json in dumper/FORMAT.md. The other registries' tags are dropped.
 */
export const tags = await Deno
	.readTextFile(join(dumpDir, "tags.json"))
	.then(JSON.parse)
	.then(schema.parseAsync.bind(schema))
	.then((parsed) => Object.fromEntries(matterTypes.map((type) => [type, parsed[registryOf(type)]])) as Record<MatterType, ReadonlyMap<string, readonly string[]>>)
	.catch((cause) => {
		throw new Error("Failed to parse dump tags", { cause })
	})

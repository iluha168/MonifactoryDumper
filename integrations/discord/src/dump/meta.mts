import { join } from "node:path"
import { dumpDir } from "./path.mts"
import z from "zod"

const schema = z.object({
	format: z.literal(2),
	images: z.boolean(),
	frameMillis: z.int().positive().nullable(),
	stills: z.int().nonnegative().nullable(),
	pack: z.object({
		name: z.string(),
		version: z.string(),
		mode: z.string(),
	}),
})

export const dumpMeta = await Deno
	.readTextFile(join(dumpDir, "meta.json"))
	.then(JSON.parse)
	.then(schema.parseAsync.bind(schema))
	.catch((cause) => {
		throw new Error("Failed to parse dump meta", { cause })
	})

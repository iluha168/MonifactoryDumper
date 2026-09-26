import { join } from "node:path"
import { dumpDir } from "./path.mts"
import z from "zod"
import { unformatted } from "./formatting.mts"

const schema = z.record(z.string(), z.string().transform(unformatted))
	.transform((table): ReadonlyMap<string, string> => new Map(Object.entries(table)))

export function readLang(): Promise<ReadonlyMap<string, string>> {
	return Deno
		.readTextFile(join(dumpDir, "lang.json"))
		.then(JSON.parse)
		.then(schema.parseAsync.bind(schema))
		.catch((cause) => {
			throw new Error("Failed to parse dump translations", { cause })
		})
}

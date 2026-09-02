import { exit, ExitCodes } from "./cli.mts"
import { join } from "node:path"

const dumpDir = Deno.args[0]
	?? exit("No dump path specified", ExitCodes.MALFORMED_DUMP)

// TODO use
const token = Deno.env.get("DISCORD_BOT_TOKEN")
	?? exit("No discord bot token set", ExitCodes.INCORRECT_ENV)

const recipesFile = "recipes.json"
const recipes: unknown[] = await Deno.readTextFile(join(dumpDir, recipesFile))
	.catch(() => exit(recipesFile + " is not a readable file", ExitCodes.MALFORMED_DUMP))
	.then(JSON.parse)
	.catch(() => exit(recipesFile + " is not valid JSON", ExitCodes.MALFORMED_DUMP))

console.log(`${recipes.length} recipes`)

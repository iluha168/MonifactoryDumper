import { exit, ExitCodes } from "../cli.mts"

export const dumpDir = Deno.args[0]
	?? exit("No dump path specified", ExitCodes.MALFORMED_DUMP)

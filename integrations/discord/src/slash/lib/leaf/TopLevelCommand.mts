import type { CreateApplicationCommand } from "discordeno"
import type { ZodType } from "zod"
import type { Interaction } from "../BaseCommand.mts"
import { LeafCommand } from "./LeafCommand.mts"

export class TopLevelCommand<T> extends LeafCommand<T> {
	constructor(
		public readonly payload: CreateApplicationCommand,
		schema: ZodType<T, unknown>,
		run: (interaction: Interaction, args: T) => Promise<unknown>,
	) {
		super(schema, run)
	}
}

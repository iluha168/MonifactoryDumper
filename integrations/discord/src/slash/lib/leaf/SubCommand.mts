import { ApplicationCommandOptionTypes, CreateSlashApplicationCommand } from "discordeno"
import type { Interaction } from "../BaseCommand.mts"
import type { ZodType } from "zod"
import { LeafCommand } from "./LeafCommand.mts"

type CreateApplicationSubCommand = NonNullable<CreateSlashApplicationCommand["options"]>[0]

export class SubCommand<T> extends LeafCommand<T> {
	public readonly payload: CreateApplicationSubCommand

	constructor(
		payload: Omit<CreateApplicationSubCommand, "type">,
		schema: ZodType<T, unknown>,
		run: (interaction: Interaction, options: T) => Promise<unknown>,
	) {
		super(schema, run)
		this.payload = {
			...payload,
			type: ApplicationCommandOptionTypes.SubCommand,
		}
	}
}

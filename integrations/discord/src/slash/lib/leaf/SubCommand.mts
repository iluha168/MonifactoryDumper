import { ApplicationCommandOptionTypes, CreateSlashApplicationCommand } from "discordeno"
import type { z } from "zod"
import { LeafCommand } from "./LeafCommand.mts"

type CreateApplicationSubCommand = NonNullable<CreateSlashApplicationCommand["options"]>[0]

export class SubCommand<T extends z.core.$ZodLooseShape> extends LeafCommand<T> {
	public readonly payload: CreateApplicationSubCommand

	constructor(
		payload: Omit<CreateApplicationSubCommand, "type">,
		...args: ConstructorParameters<typeof LeafCommand<T>>
	) {
		super(...args)
		this.payload = {
			...payload,
			type: ApplicationCommandOptionTypes.SubCommand,
		}
	}
}

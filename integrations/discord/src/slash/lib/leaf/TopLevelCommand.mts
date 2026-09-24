import type { CreateApplicationCommand } from "discordeno"
import type { z } from "zod"
import { LeafCommand } from "./LeafCommand.mts"
import type { TopLevelLikeCommand } from "../CommandRegistry.mts"

export class TopLevelCommand<T extends z.core.$ZodLooseShape> extends LeafCommand<T> implements TopLevelLikeCommand {
	constructor(
		public readonly payload: CreateApplicationCommand,
		...args: ConstructorParameters<typeof LeafCommand<T>>
	) {
		super(...args)
	}
}

import type { ZodType } from "zod"
import { BaseCommand, type Interaction } from "../BaseCommand.mts"

export abstract class LeafCommand<T> extends BaseCommand {
	constructor(
		private readonly schema: ZodType<T, unknown>,
		private readonly run: (interaction: Interaction, args: T) => Promise<unknown>,
	) {
		super()
	}

	override async handle(interaction: Interaction, options: unknown): Promise<void> {
		await this.run(interaction, await this.schema.parseAsync(options))
	}
}

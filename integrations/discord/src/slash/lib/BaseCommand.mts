import type { bot } from "../../index.mts"

export type Interaction = typeof bot.transformers.$inferredTypes.interaction

export abstract class BaseCommand {
	abstract handle(interaction: Interaction, options: unknown): Promise<void>
}

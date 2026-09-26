import type { bot } from "../../index.mts"

export type Interaction = typeof bot.transformers.$inferredTypes.interaction
export type InteractionData = NonNullable<Interaction["data"]>
export type InteractionMessage = NonNullable<Interaction["message"]>

export abstract class BaseCommand {
	abstract handleApplicationCommand(interaction: Interaction, data: InteractionData): Promise<void>
	abstract handleApplicationCommandAutocomplete(interaction: Interaction, data: InteractionData): Promise<void>
	abstract handleMessageComponent(interaction: Interaction, data: InteractionData, message: InteractionMessage): Promise<void>
}

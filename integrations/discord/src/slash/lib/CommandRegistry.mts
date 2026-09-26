import { type CreateApplicationCommand, InteractionTypes } from "discordeno"
import type { BaseCommand, Interaction, InteractionData } from "./BaseCommand.mts"

export type TopLevelLikeCommand = BaseCommand & {
	readonly payload: CreateApplicationCommand
}

export class CommandRegistry {
	private readonly handlers: Map<string, TopLevelLikeCommand> = new Map()

	constructor(...commands: readonly TopLevelLikeCommand[]) {
		this.handlers = new Map(commands.map(
			(command) => [command.payload.name, command],
		))
	}

	get payload(): CreateApplicationCommand[] {
		return Array.from(
			this.handlers.values(),
			({ payload }) => payload,
		)
	}

	async handle(interaction: Interaction, data: InteractionData): Promise<void> {
		switch (interaction.type) {
			case InteractionTypes.Ping:
				// TODO
				break
			case InteractionTypes.ApplicationCommand:
				try {
					const handler = this.handlers.get(data.name)
					if (!handler) {
						return console.warn(`Unknown command "${data.name}"`, data)
					}
					await handler.handleApplicationCommand(interaction, data)
				} catch (e) {
					console.error(`Application command "${data.name}" failed`, e, data)
				}
				break
			case InteractionTypes.ApplicationCommandAutocomplete:
				try {
					const handler = this.handlers.get(data.name)
					if (!handler) {
						return console.warn(`Unknown command "${data.name}"`, data)
					}
					await handler.handleApplicationCommandAutocomplete(interaction, data)
				} catch (e) {
					console.error(`Application command "${data.name}" autocomplete failed`, e, data)
				}
				break
			case InteractionTypes.MessageComponent:
				try {
					if (!interaction.message?.interaction) {
						return console.warn(`Message component outside an interaction response is not supported`, interaction.message, data)
					}
					const separator = " "
					const subName = interaction.message.interaction.name.split(separator, 1)[0]
					if (interaction.message?.applicationId !== interaction.bot.applicationId) {
						return // Not targeted at us, somehow.
					}
					const handler = this.handlers.get(subName)
					if (!handler) {
						return console.warn(`Unknown command "${subName}"`, data)
					}
					await handler.handleMessageComponent(interaction, data, interaction.message)
				} catch (e) {
					console.error(`Application command message component failed`, e, interaction.message, data)
				}
				break
			case InteractionTypes.ModalSubmit:
				break // TODO
		}
	}
}

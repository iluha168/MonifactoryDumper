import type { CreateApplicationCommand } from "discordeno"
import type { BaseCommand, Interaction } from "./BaseCommand.mts"

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

	async handle(interaction: Interaction, options: unknown, subCommandName: string): Promise<void> {
		try {
			const handler = this.handlers.get(subCommandName)
			if (!handler) {
				return console.warn(`Unknown command "${subCommandName}"`)
			}
			await handler.handle(interaction, options)
		} catch (e) {
			console.error(`Application command "${subCommandName}" failed`, e)
		}
	}
}

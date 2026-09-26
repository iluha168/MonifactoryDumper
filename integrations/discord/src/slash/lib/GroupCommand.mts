import { ApplicationCommandTypes, CreateApplicationCommand } from "discordeno"
import { SubCommand } from "./leaf/SubCommand.mts"
import { BaseCommand, Interaction, InteractionData, InteractionMessage } from "./BaseCommand.mts"
import type { TopLevelLikeCommand } from "./CommandRegistry.mts"

export class GroupCommand extends BaseCommand implements TopLevelLikeCommand {
	public readonly payload: CreateApplicationCommand
	// deno-lint-ignore no-explicit-any
	private readonly handlers: Map<string, SubCommand<any>> = new Map()

	constructor(
		payload: CreateApplicationCommand & {
			readonly options?: never
			readonly type?: never
		},
		// deno-lint-ignore no-explicit-any
		...commands: readonly SubCommand<any>[]
	) {
		super()
		this.payload = {
			...payload,
			type: ApplicationCommandTypes.ChatInput,
			options: commands.map(({ payload }) => payload),
		}
		this.handlers = new Map(commands.map(
			(command) => [command.payload.name, command],
		))
	}

	private getSubCommand(name: string) {
		const handler = this.handlers.get(name)
		if (!handler) {
			throw new Error(`Unknown sub-command "${name}"`)
		}
		return handler
	}

	async handleApplicationCommand(interaction: Interaction, data: InteractionData): Promise<void> {
		const cmd = data.options?.at(0)
		if (!cmd) {
			throw new Error("No sub-command provided")
		}
		await this.getSubCommand(cmd.name).handleApplicationCommand(interaction, { ...data, options: cmd.options ?? [] })
	}

	async handleApplicationCommandAutocomplete(interaction: Interaction, data: InteractionData): Promise<void> {
		const cmd = data.options?.at(0)
		if (!cmd) {
			throw new Error("No sub-command provided")
		}
		await this.getSubCommand(cmd.name).handleApplicationCommandAutocomplete(interaction, { ...data, options: cmd.options ?? [] })
	}

	override async handleMessageComponent(interaction: Interaction, data: InteractionData, message: InteractionMessage): Promise<void> {
		const cmd = this.getSubCommand(message.interaction!.name.split(" ", 2)[1])
		await cmd.handleMessageComponent(interaction, data, message)
	}
}

import { ApplicationCommandTypes, CreateApplicationCommand, InteractionDataOption } from "discordeno"
import { SubCommand } from "./leaf/SubCommand.mts"
import { BaseCommand, Interaction } from "./BaseCommand.mts"
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

	async handle(interaction: Interaction, options: InteractionDataOption[]): Promise<void> {
		const cmd = options.at(0)
		if (!cmd) {
			throw new Error("No sub-command provided")
		}
		const handler = this.handlers.get(cmd.name)
		if (!handler) {
			throw new Error(`Unknown sub-command "${cmd.name}"`)
		}
		await handler.handle(interaction, cmd.options ?? [])
	}
}

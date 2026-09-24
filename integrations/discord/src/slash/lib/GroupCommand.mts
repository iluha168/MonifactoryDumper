import { ApplicationCommandTypes, CreateApplicationCommand } from "discordeno"
import { SubCommand } from "./leaf/SubCommand.mts"
import { BaseCommand, Interaction } from "./BaseCommand.mts"
import z from "zod"
import type { TopLevelLikeCommand } from "./CommandRegistry.mts"

/**
 * They payload is an object containing one key - the name of the subcommand, and its options.
 */
const schema = z
	.looseObject({})
	.transform(Object.entries)
	.pipe(
		z.tuple([
			z.tuple([z.string(), z.unknown()]),
		]).transform((entries) => entries[0]),
	)

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

	async handle(interaction: Interaction, options: unknown): Promise<void> {
		const [subCommandName, subOptions] = await schema.parseAsync(options)
		try {
			const handler = this.handlers.get(subCommandName)
			if (!handler) {
				return console.warn(`Unknown sub-command "${subCommandName}"`)
			}
			await handler.handle(interaction, subOptions)
		} catch (e) {
			console.error(`Application sub-command "${subCommandName}" failed`, e)
		}
	}
}

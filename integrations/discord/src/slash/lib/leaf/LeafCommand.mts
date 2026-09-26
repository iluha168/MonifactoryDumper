import z from "zod"
import { BaseCommand, type Interaction, InteractionData, InteractionMessage } from "../BaseCommand.mts"
import { commandOptionsParser, InteractionCallbackData } from "discordeno"

/** TS trick to not write the field type. */
const exactPartial = <T extends z.core.$ZodLooseShape>(schema: z.ZodObject<T, z.core.$strict>) => schema.exactPartial()

type Resolvable<T> = T | PromiseLike<T>

export abstract class LeafCommand<T extends z.core.$ZodLooseShape> extends BaseCommand {
	private readonly schema: ReturnType<typeof z.strictObject<T>>
	private readonly schemaPartial: ReturnType<typeof exactPartial<T>>

	constructor(
		shape: T,
		private readonly handlers: NoInfer<
			{
				readonly run: (
					this: LeafCommand<T>,
					interaction: Interaction,
					args: z.infer<LeafCommand<T>["schema"]>,
				) => Resolvable<unknown>
				readonly autocomplete?: (
					this: LeafCommand<T>,
					args: z.infer<LeafCommand<T>["schemaPartial"]>,
					focus: keyof T,
				) => Resolvable<NonNullable<InteractionCallbackData["choices"]>>
				readonly component?: (
					this: LeafCommand<T>,
					...rest: Parameters<BaseCommand["handleMessageComponent"]>
				) => Resolvable<unknown>
			}
		>,
	) {
		super()
		this.schema = z.strictObject(shape)
		this.schemaPartial = exactPartial(this.schema)
	}

	override async handleApplicationCommand(interaction: Interaction, data: InteractionData): Promise<void> {
		await this.handlers.run.call(
			this,
			interaction,
			await this.schema.parseAsync(
				commandOptionsParser(interaction, data.options),
			),
		)
	}

	override async handleApplicationCommandAutocomplete(interaction: Interaction, data: InteractionData): Promise<void> {
		if (!this.handlers.autocomplete) {
			throw new Error("Autocomplete not implemented for the command")
		}
		const focused = (data.options ?? []).filter(({ focused }) => focused)
		if (focused.length !== 1) {
			throw new Error(`Autocomplete has ${focused.length} focuses (not 1)`)
		}
		await interaction.respond({
			choices: await this.handlers.autocomplete.call(
				this,
				await this.schemaPartial.parseAsync(commandOptionsParser(interaction, data.options)),
				focused[0].name,
			),
		})
	}

	override async handleMessageComponent(interaction: Interaction, data: InteractionData, message: InteractionMessage): Promise<void> {
		if (!this.handlers.component) {
			throw new Error("Message components not implemented for the command")
		}
		await this.handlers.component.call(
			this,
			interaction,
			data,
			message,
		)
	}
}

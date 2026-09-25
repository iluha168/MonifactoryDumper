import z from "zod"
import { BaseCommand, type Interaction } from "../BaseCommand.mts"
import { commandOptionsParser, InteractionCallbackData, InteractionDataOption, InteractionTypes } from "discordeno"

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
				readonly run: (interaction: Interaction, args: z.infer<LeafCommand<T>["schema"]>) => Resolvable<unknown>
				readonly autocomplete?: (
					args: z.infer<LeafCommand<T>["schemaPartial"]>,
					focus: keyof T,
				) => Resolvable<NonNullable<InteractionCallbackData["choices"]>>
			}
		>,
	) {
		super()
		this.schema = z.strictObject(shape)
		this.schemaPartial = exactPartial(this.schema)
	}

	override async handle(interaction: Interaction, options: InteractionDataOption[]): Promise<void> {
		switch (interaction.type) {
			case InteractionTypes.ApplicationCommand:
				await this.handlers.run(
					interaction,
					await this.schema.parseAsync(
						commandOptionsParser(interaction, options),
					),
				)
				break
			case InteractionTypes.ApplicationCommandAutocomplete: {
				if (!this.handlers.autocomplete) {
					throw new Error("Autocomplete not implemented for the command")
				}
				const focused = options.filter(({ focused }) => focused)
				if (focused.length !== 1) {
					throw new Error(`Autocomplete has ${focused.length} focuses (not 1)`)
				}
				await interaction.respond({
					choices: await this.handlers.autocomplete(
						await this.schemaPartial.parseAsync(commandOptionsParser(interaction, options)),
						focused[0].name,
					),
				})
				break
			}
			case InteractionTypes.Ping:
			case InteractionTypes.MessageComponent:
			case InteractionTypes.ModalSubmit:
			default:
				throw new Error("Not implemented interaction type")
		}
	}
}

import z from "zod"
import { SubCommand } from "../../lib/leaf/SubCommand.mts"
import { ApplicationCommandOptionTypes } from "discordeno"

export const commandRecipeJson = new SubCommand(
	{
		name: "json",
		description: "View raw recipe json",
		options: [{
			type: ApplicationCommandOptionTypes.User,
			name: "test",
			description: "test",
		}],
	},
	z.unknown(),
	async (interaction, options) => {
		console.log(JSON.stringify(options, null, 2))
	},
)

import { commandOptionsParser, createBot } from "discordeno"
import { exit, ExitCodes } from "./cli.mts"
import { CommandRegistry } from "./slash/lib/CommandRegistry.mts"
import { commandHelp } from "./slash/command/help.mts"
import { commandRecipe } from "./slash/command/recipe/index.mts"

const token = Deno.env.get("DISCORD_BOT_TOKEN") || exit("No discord bot token set", ExitCodes.INCORRECT_ENV)

const commandRegistry = new CommandRegistry(
	commandHelp,
	commandRecipe,
)
export const bot = createBot({
	events: {
		ready() {
			console.debug("Bot started!")
		},
		interactionCreate(interaction) {
			if (!interaction.data) {
				throw new Error("No interaction data")
			}
			const options = commandOptionsParser(interaction)
			commandRegistry.handle(interaction, options, interaction.data.name)
		},
	},
	token,
	desiredProperties: {
		interaction: {
			id: true,
			type: true,
			data: true,
			token: true,
		},
	},
})

await bot.rest.upsertGlobalApplicationCommands(commandRegistry.payload)
await bot.start()

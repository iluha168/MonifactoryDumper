import z from "zod"
import { TopLevelCommand } from "../lib/leaf/TopLevelCommand.mts"
import { ApplicationCommandTypes } from "discordeno"
import { dumpMeta } from "../../dump/meta.mts"

export const commandHelp = new TopLevelCommand(
	{
		type: ApplicationCommandTypes.ChatInput,
		name: "help",
		description: "What even is this bot?",
	},
	z.strictObject({}),
	(interaction) => {
		const { pack: { name, ...fields } } = dumpMeta
		return interaction.respond({
			embeds: [{
				title: "Hello!1!! 👋",
				description: `I am a live encyclopedia for ${name}.`,
				color: 0x282E44,
				fields: Object
					.entries(fields)
					.map(([name, value]) => ({
						name: name[0].toUpperCase() + name.slice(1),
						value,
						inline: true,
					})),
			}],
		})
	},
)

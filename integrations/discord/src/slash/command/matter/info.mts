import z from "zod"
import { ApplicationCommandOptionTypes, DiscordEmbedField } from "discordeno"
import { SubCommand } from "../../lib/leaf/SubCommand.mts"
import { unique } from "../../../iterator/unique.mts"
import { matterNames, type MatterType, matterTypes } from "../../../dump/matterNames.mts"
import { emojis } from "../config.mts"
import { registryOf, tags } from "../../../dump/tags.mts"
import { Ingredient, recipes } from "../../../dump/recipes.mts"
import { stills } from "../../../dump/stills.mts"
import { widgets } from "../../../dump/stillUses.mts"
import { drawWidget, pictureToAttachment } from "../../../picture/draw.mts"
import { dominantColor } from "../../../picture/codec.mts"

export const commandMatterInfo = new SubCommand(
	{
		name: "info",
		description: "Information about an item/fluid, the kind of stuff AE2 can store.",
		options: [{
			type: ApplicationCommandOptionTypes.String,
			name: "type",
			description: "Which matter type?",
			required: true,
			choices: matterTypes.map((name) => ({ name, value: name })),
		}, {
			type: ApplicationCommandOptionTypes.String,
			name: "id",
			description: "Identifier of the matter. F3+H?",
			autocomplete: true,
		}, {
			type: ApplicationCommandOptionTypes.String,
			name: "name",
			description: "The English name of the matter.",
			autocomplete: true,
		}],
	},
	{
		type: z.literal(matterTypes),
		id: z.string().optional(),
		name: z.string().optional(),
	},
	{
		run(interaction, { type, id, name }) {
			const translations = matterNames[type]

			const respondFound = async (id: string, name: string) => {
				const tagsContaining = new Set(
					tags[type]
						.entries()
						.filter(([, entries]) => entries.includes(id))
						.map(([tag]) => tag),
				)
				const uses = countUses(type, id, tagsContaining)
				const usesTotal = uses.input + uses.output + uses.catalyst
				if (!usesTotal) {
					return interaction.respond({
						embeds: [{
							title: `${name} (\`${id}\`)`,
							description: `This ${type} is useless.`,
						}],
					})
				}

				const fields: DiscordEmbedField[] = [{
					name: "ID",
					value: `\`${id}\``,
					inline: true,
				}, {
					name: "Matter",
					value: type,
					inline: true,
				}, {
					name: "Uses",
					value: `${uses.input} as an input, ${uses.output} as an output, ${uses.catalyst} as a catalyst.`,
					inline: true,
				}, {
					name: "Tags",
					value: `${tagsContaining.size} uses` + (
						tagsContaining.size > 0
							? `: ${tagsContaining.values().take(10).map((tag) => `\`#${tag}\``).toArray().join(", ")}${tagsContaining.size > 10 ? ` and ${tagsContaining.size - 10} more!` : "."}`
							: "."
					),
					inline: true,
				}]

				const widget = widgets?.widget(type, id)
				if (widget && stills) {
					await interaction.defer()
					const picture = await drawWidget(widget, stills, 4)
					return interaction.edit({
						files: [pictureToAttachment(picture)],
						embeds: [{
							title: name,
							color: await dominantColor(picture.bytes),
							image: { url: `attachment://${picture.name}` },
							fields,
						}],
					})
				}
				return interaction.respond({
					embeds: [{
						title: name,
						fields,
					}],
				})
			}

			if (id !== undefined) {
				const actualName = translations.get(id)
				if (actualName === undefined) {
					return interaction.respond(`${emojis.errorUser} No ${type} has the ID \`${id}\`.`, { isPrivate: true })
				}
				return respondFound(id, actualName)
			}
			const idsByName = translations
				.entries()
				.filter(([, v]) => v === name)
				.toArray()
			if (idsByName.length === 1) {
				return respondFound(...idsByName[0])
			}
			if (idsByName.length <= 0) {
				return interaction.respond(`${emojis.errorUser} No ${type} has this name!`, { isPrivate: true })
			}
			return interaction.respond(
				`${emojis.errorUser} Found ${idsByName.length} ${type}s by the name \`${name}\`:\n${idsByName.slice(0, 10).map(([id]) => "`" + id + "`").join(", ")}${
					idsByName.length > 10 ? ` and ${idsByName.length - 10} more!` : "."
				}`,
				{ isPrivate: true },
			)
		},
		autocomplete({ type, id, name }, focus) {
			if (focus === "type") {
				return matterTypes.map((k) => ({ name: k, value: k }))
			}
			if (type === undefined) {
				return [] // Yeah no, not searching all categories.
			}

			const strings = matterNames[type]
				.entries()
				.filter(([k, v]) =>
					(!id || k.includes(id.toLowerCase()))
					&& (!name || v.toLowerCase().includes(name.toLowerCase()))
				)
				.map(([k, v]) => focus === "id" ? k : v)
				.filter((suggestion) => suggestion.length < 100) // Discord limit

			return unique(strings)
				.map((suggestion) => ({
					name: suggestion,
					value: suggestion,
				}))
				.take(25) // Discord limit
				.toArray()
		},
	},
)

function countUses(type: MatterType, id: string, tagsContaining: ReadonlySet<string>) {
	const reg = registryOf(type)
	const matches = (ingredient: Ingredient) => {
		switch (ingredient?.k) {
			case "s":
				return ingredient.t === type && ingredient.id === id
			case "t":
				return ingredient.reg === reg && tagsContaining.has(ingredient.tag)
			case "m":
				return ingredient.ids.includes(id)
			default:
				return false
		}
	}

	const uses = { input: 0, output: 0, catalyst: 0 }
	for (const recipe of recipes) {
		const input = recipe.in.some(matches)
		const output = recipe.out.some(matches)
		const catalyst = recipe.cats.some(matches)
		if (input) uses.input++
		if (output) uses.output++
		if (catalyst) uses.catalyst++
	}
	return uses
}

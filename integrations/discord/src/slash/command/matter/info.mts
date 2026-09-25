import z from "zod"
import { ApplicationCommandOptionTypes } from "discordeno"
import { SubCommand } from "../../lib/leaf/SubCommand.mts"
import { unique } from "../../../iterator/unique.mts"
import { matterNames } from "../../../dump/matterNames.mts"
import { emojis } from "../config.mts"
import { tags } from "../../../dump/tags.mts"
import { Ingredient, recipes } from "../../../dump/recipes.mts"

export const commandMatterInfo = new SubCommand(
	{
		name: "info",
		description: "Information about an item/fluid, the kind of stuff AE2 can store.",
		options: [{
			type: ApplicationCommandOptionTypes.String,
			name: "type",
			description: "Which matter type?",
			required: true,
			choices: Object.keys(matterNames).map((name) => ({ name, value: name })),
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
		type: z.literal(Object.keys(matterNames) as (keyof typeof matterNames)[]),
		id: z.string().optional(),
		name: z.string().optional(),
	},
	{
		run(interaction, { type, id, name }) {
			const translations = matterNames[type]

			const respondFound = async (id: string, name: string) => {
				const uses = countUses(type, id)
				const usesTotal = uses.input + uses.output + uses.catalyst
				if (!usesTotal) {
					return interaction.respond({
						embeds: [{
							title: `${name} (\`${id}\`)`,
							description: `This ${type} is useless.`,
						}],
					})
				}
				await interaction.respond({
					embeds: [{
						title: `${name} (\`${id}\`)`,
						description: `This ${type} has ${usesTotal} uses in recipes (${uses.input} as an input, ${uses.output} as an output, ${uses.catalyst} as a catalyst).`,
						footer: { text: "Image coming soon!" },
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
				`Found ${idsByName.length} ${type}s by the name \`${name}\`:\n${idsByName.slice(0, 10).map(([id]) => "`" + id + "`").join(", ")}${
					idsByName.length > 10 ? `... and ${idsByName.length - 10} more!` : ""
				}`,
			)
		},
		autocomplete({ type, id, name }, focus) {
			if (focus === "type") {
				return Object.keys(matterNames).map((k) => ({ name: k, value: k }))
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

function countUses(type: keyof typeof matterNames, id: string) {
	const reg = `minecraft:${type}`
	const holdingTags = new Set(
		tags[type]
			.entries()
			.filter(([, entries]) => entries.includes(id))
			.map(([tag]) => tag),
	)
	const matches = (ingredient: Ingredient) => {
		switch (ingredient?.k) {
			case "s":
				return ingredient.t === type && ingredient.id === id
			case "t":
				return ingredient.reg === reg && holdingTags.has(ingredient.tag)
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

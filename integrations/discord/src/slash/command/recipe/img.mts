import z from "zod"
import { ApplicationCommandOptionTypes } from "discordeno"
import { SubCommand } from "../../lib/leaf/SubCommand.mts"
import { recipes } from "../../../dump/recipes.mts"
import { stills } from "../../../dump/stills.mts"
import { drawRecipe } from "../../../picture/draw.mts"
import { unique } from "../../../iterator/unique.mts"
import { emojis } from "../config.mts"

export const commandRecipeImg = new SubCommand(
	{
		name: "img",
		description: "Draw a recipe as EMI shows it.",
		options: [{
			type: ApplicationCommandOptionTypes.String,
			name: "id",
			description: "EMI's ID of the recipe.",
			required: true,
			autocomplete: true,
		}],
	},
	{
		id: z.string(),
	},
	{
		async run(interaction, { id }) {
			if (!stills) {
				throw new Error("Not implemented.")
			}
			const matches = recipes.filter((recipe) => recipe.emiRecipeId === id)
			if (!matches.length) {
				return interaction.respond(`${emojis.errorUser} No recipe has the ID \`${id}\`.`, { isPrivate: true })
			}
			const foundWithImage = matches.filter(({ image }) => image)
			const image = foundWithImage[0]?.image
			if (!image) {
				return interaction.respond(`${emojis.errorExpected} This recipe is not renderable.`, { isPrivate: true })
			}

			await interaction.defer()
			try {
				const drawn = await drawRecipe(await image.read(), stills)

				const notes = []
				if (matches.length > 1) notes.push(`${matches.length} recipes share this ID.`)
				if (!drawn.seamless) notes.push(`The render was limited to ${drawn.seconds}s out of ${drawn.loopSeconds}s.`)

				await interaction.edit({
					content: notes.map((l) => `${emojis.info} ${l}`).join("\n"),
					files: [{ name: drawn.name, blob: new Blob([drawn.bytes], { type: drawn.type }) }],
				})
			} catch (e) {
				await interaction.edit(`${emojis.errorInternal} My bad, render failed.`)
				throw e
			}
		},
		autocomplete({ id }, focus) {
			const strings = recipes
				.values()
				.filter((recipe) => (!id || recipe.emiRecipeId?.includes(id.toLowerCase())))
				.map((recipe) => focus === "id" ? recipe.emiRecipeId : recipe.cat)
				.filter((id) => id !== null)
				.filter((id) => id.length < 100) // Discord limit

			return unique(strings)
				.map((id) => ({
					name: id,
					value: id,
				}))
				.take(25) // Discord limit
				.toArray()
		},
	},
)

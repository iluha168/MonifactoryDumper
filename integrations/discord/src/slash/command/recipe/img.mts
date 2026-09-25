import z from "zod"
import { ApplicationCommandOptionTypes } from "discordeno"
import { SubCommand } from "../../lib/leaf/SubCommand.mts"
import { recipes } from "../../../dump/recipes.mts"
import { stills } from "../../../dump/stills.mts"
import { drawRecipe } from "../../../picture/draw.mts"
import { unique } from "../../../iterator/unique.mts"

export const commandRecipeImg = new SubCommand(
	{
		name: "img",
		description: "Draw a recipe as EMI shows it",
		options: [{
			type: ApplicationCommandOptionTypes.String,
			name: "id",
			description: "EMI's ID of the recipe.",
			required: true,
			autocomplete: true,
		}, {
			type: ApplicationCommandOptionTypes.String,
			name: "category",
			description: "EMI category. Use this to scope the autocomplete of other fields.",
			autocomplete: true,
			required: false,
		}],
	},
	{
		id: z.string(),
		category: z.string().optional(),
	},
	{
		async run(interaction, { id, category }) {
			if (!stills) {
				throw new Error("Not implemented.")
			}
			const matches = recipes.filter((recipe) => recipe.emiRecipeId === id && (!category || recipe.cat === category))
			if (!matches.length) {
				return interaction.respond(`<:huh:1527117723681820882> No recipe has the ID \`${id}\`.`, { isPrivate: true })
			}
			const foundWithImage = matches.filter(({ image }) => image)
			const image = foundWithImage[0]?.image
			if (!image) {
				return interaction.respond(`<a:thevoices:1524910664265760940> This recipe is not renderable.`, { isPrivate: true })
			}

			await interaction.defer()
			try {
				const drawn = await drawRecipe(await image.read(), stills)

				const notes = []
				if (matches.length > 1) notes.push(`${matches.length} recipes share this ID.`)
				if (!drawn.seamless) notes.push(`The render was limited to ${drawn.seconds}s out of ${drawn.loopSeconds}s.`)

				await interaction.edit({
					content: notes.map((l) => `<a:blobnote:1534253408683294800> ${l}`).join("\n"),
					files: [{ name: drawn.name, blob: new Blob([drawn.bytes], { type: drawn.type }) }],
				})
			} catch (e) {
				await interaction.edit(`<a:hyperspeedvoices:1526010011665305791> My bad, render failed.`)
				throw e
			}
		},
		autocomplete({ id, category }, focus) {
			const strings = recipes
				.values()
				.filter((recipe) =>
					(!id || recipe.emiRecipeId?.includes(id.toLowerCase()))
					&& (!category || recipe.cat.includes(category.toLowerCase()))
				)
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

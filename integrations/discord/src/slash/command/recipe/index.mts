import { GroupCommand } from "../../lib/GroupCommand.mts"
import { commandRecipeJson } from "./json.mts"

export const commandRecipe = new GroupCommand(
	{
		name: "recipe",
		description: "View recipes",
	},
	commandRecipeJson,
)

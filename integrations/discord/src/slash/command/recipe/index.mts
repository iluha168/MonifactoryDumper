import { stills } from "../../../dump/stills.mts"
import { GroupCommand } from "../../lib/GroupCommand.mts"
import { commandRecipeImg } from "./img.mts"

export const commandRecipe = new GroupCommand(
	{
		name: "recipe",
		description: "View recipes.",
	},
	...stills ? [commandRecipeImg] : [],
)

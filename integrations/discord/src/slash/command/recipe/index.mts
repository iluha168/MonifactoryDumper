import { stills } from "../../../dump/stills.mts"
import { GroupCommand } from "../../lib/GroupCommand.mts"
import { commandRecipeAkinator } from "./akinator.mts"
import { commandRecipeImg } from "./img.mts"

export const commandRecipe = new GroupCommand(
	{
		name: "recipe",
		description: "View recipes.",
	},
	...stills ? [commandRecipeImg] : [],
	commandRecipeAkinator,
)

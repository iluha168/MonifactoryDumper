import { GroupCommand } from "../../lib/GroupCommand.mts"
import { commandMatterInfo } from "./info.mts"

export const commandMatter = new GroupCommand(
	{
		name: "matter",
		description: "View and search through matter types.",
	},
	commandMatterInfo,
)

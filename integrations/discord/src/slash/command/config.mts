import { ButtonComponent } from "discordeno"

type EmojiButton = Readonly<Required<NonNullable<ButtonComponent["emoji"]>>>

export const emojis = {
	info: "<a:blobnote:1534253408683294800>",
	errorUser: "<:huh:1527117723681820882>",
	errorInternal: "<a:hyperspeedvoices:1526010011665305791>",
	errorExpected: "<a:thevoices:1524910664265760940>",
	btnYes: {
		animated: true,
		name: "catnod",
		id: 1547698565592252456n,
	} satisfies EmojiButton,
	btnNo: {
		animated: true,
		name: "catnope",
		id: 1547698618205474926n,
	} satisfies EmojiButton,
	btnNotSure: {
		animated: false,
		name: "huh",
		id: 1527117723681820882n,
	} satisfies EmojiButton,
} as const

/**
 * Game text without Minecraft's formatting codes: `§` and the character after it, such as the colour in GregTech's
 * `"Basic Macerator §r"`. The dump keeps them, for readers that show colour; the bot shows none. Text without any is
 * returned as is, so that it stays shared with the rest of the dump's.
 */
export const unformatted = (text: string): string => text.includes("§") ? text.replaceAll(/§[\s\S]?/gu, "") : text

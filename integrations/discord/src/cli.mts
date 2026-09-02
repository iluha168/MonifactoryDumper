export const enum ExitCodes {
	MALFORMED_DUMP = 1,
	INCORRECT_ENV,
}

export function exit(message: string, code: ExitCodes): never {
	console.error(message)
	Deno.exit(code)
}

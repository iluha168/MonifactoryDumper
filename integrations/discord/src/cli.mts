export const enum ExitCodes {
	INCORRECT_ENV = 1,
	MALFORMED_DUMP,
}

export function exit(message: string, code: ExitCodes): never {
	console.error(message)
	Deno.exit(code)
}

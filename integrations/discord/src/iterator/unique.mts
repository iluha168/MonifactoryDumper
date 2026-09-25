export const unique = <T, TReturn, TNext>(iter: IteratorObject<T, TReturn, TNext>): IteratorObject<T, undefined, unknown> => {
	const seen = new Set<T>()
	return iter
		.filter((item) => !seen.has(item))
		.map((item) => {
			seen.add(item)
			return item
		})
}

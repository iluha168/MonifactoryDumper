import { type Ingredient, type Recipe, recipes } from "../dump/recipes.mts"
import { matterNames, type MatterType, matterTypes } from "../dump/matterNames.mts"
import { registryOf, tags } from "../dump/tags.mts"
import { readLang } from "../dump/lang.mts"
import { type Matter, widgets } from "../dump/stillUses.mts"

/** A yes-or-no question about a recipe: "Does your recipe {@link description}?" */
export interface AkinatorQuestion {
	/** A verb phrase in Discord markdown. Two questions with one description are one question. */
	readonly description: string
	/**
	 * The answer for the candidate numbered `candidate` in {@link akinatorCatalog}. Undefined where a player could
	 * honestly give either, such as of an item that a slot shows only now and then, among other options.
	 */
	answer(candidate: number): boolean | undefined
}

/** A question of {@link AkinatorCatalog.questions}, whose answer for every candidate is known ahead. */
export interface CatalogQuestion extends AkinatorQuestion {
	/** Its index in {@link AkinatorCatalog.questions}. */
	readonly id: number
	/** Which question to ask when two split the candidates equally well: the lower. Plainer ones are lower. */
	readonly priority: number
}

/**
 * Of some candidates, how much of their weight a question's answer is "yes" for, and how much either answer is honest
 * for. With every weight 1, how many candidates.
 */
export interface Tally {
	readonly yes: number
	readonly maybe: number
}

/** A recipe the game can name when it finds it. */
export type Candidate = Recipe & { readonly emiRecipeId: string }
const isCandidate = (recipe: Recipe): recipe is Candidate => recipe.emiRecipeId !== null

/**
 * Categories whose recipes are pages listing things rather than slots to fill: EMI's tag pages, GregTech's diagrams.
 * Asking of their input slots, amounts or outputs has no honest answer.
 */
const slotless = new Set([
	"emi:tag",
	"gtceu:multiblock_info",
	"gtceu:ore_processing_diagram",
	"gtceu:ore_vein_diagram",
	"gtceu:bedrock_fluid_diagram",
	"gtceu:programmed_circuit",
	"nuclearcraft:particle_info",
])

/** Where a player sees a thing on a recipe. */
type Place = "in" | "cats" | "out"

/** What a player sees in one slot of a recipe: every option it cycles through, of one matter type if known. */
interface Slot {
	readonly type: MatterType | null
	readonly ids: readonly string[]
	/** The tag the options come from, with `ids` its entries. */
	readonly tag: string | null
	readonly ingredient: NonNullable<Ingredient>
}

function slotOf(ingredient: Ingredient): Slot | null {
	switch (ingredient?.k) {
		case "s":
			if (ingredient.id === "emi:empty") return null
			return { type: matterTypes.find((type) => type === ingredient.t) ?? null, ids: [ingredient.id], tag: null, ingredient }
		case "t": {
			const type = matterTypes.find((type) => registryOf(type) === ingredient.reg) ?? null
			return { type, ids: type ? tags[type].get(ingredient.tag) ?? [] : [], tag: ingredient.tag, ingredient }
		}
		case "m":
			return { type: matterTypes.find((type) => matterNames[type].has(ingredient.ids[0])) ?? null, ids: ingredient.ids, tag: null, ingredient }
		default:
			return null
	}
}

/** Plain text as Discord markdown that formats nothing. */
const escaped = (text: string) => text.replaceAll(/[\\*_~`|]/g, "\\$&")
const plural = (n: number, noun: string) => `${n} ${noun}${n === 1 ? "" : "s"}`

/** Words of a name to ask about: not the ones nearly every name has, nor ones too short to mean much. */
const wordsOf = (name: string) => name.split(/[^\p{L}\p{N}]+/u).filter((word) => word.length >= 3 && !commonWords.has(word.toLowerCase()))
const commonWords = new Set(["the", "and", "with", "for"])

/** Every word of names as a player reads it: in lower case, without accents, however short. */
const spelledWordsOf = (names: Iterable<string>): Set<string> =>
	new Set([...names].flatMap((name) => name.normalize("NFD").replaceAll(/\p{M}/gu, "").toLowerCase().split(/[^\p{L}\p{N}]+/u)).filter((word) => word !== ""))

/** Amounts to ask "more than" of: whole items, and millibuckets of a fluid. */
const amountSteps: Record<MatterType, readonly number[]> = {
	item: [1, 2, 3, 4, 8, 16, 32, 64],
	fluid: [100, 144, 250, 500, 1000, 2000, 4000, 8000, 16000, 64000],
}
const amountUnit: Record<MatterType, (n: number) => string> = {
	item: (n) => `${n} of the same item in total, adding up all its slots`,
	fluid: (n) => `${n} mB of the same fluid in total, adding up all its tanks`,
}

/**
 * The names a player glances at to tell apart recipes that differ in nothing else, such as one recipe per material, by
 * how their words start: what a question about each list asks of. `tag` is the name of the tag an EMI tag page shows,
 * if it is translated, and `listed` what the page lists.
 */
const nameLists = {
	out: "produce anything",
	in: "take any input",
	tag: "list a tag",
	listed: "list anything",
} as const
type NameList = keyof typeof nameLists
/** The longest start of a word to ask about: a few letters are still read at a glance. */
const longestWordStart = 3

/** Of one candidate and one list, the words a player sees for sure, and those only some options of a slot have. */
interface ListWords {
	readonly sure: readonly string[]
	readonly maybe: readonly string[]
}

/** Of {@link ListWords}, every start of a word to ask about, by the words it starts: sure ones, then the others. */
const wordStarts = new WeakMap<ListWords, { sure: ReadonlySet<string>; maybe: ReadonlySet<string> }>()
function startsOf(words: ListWords): { sure: ReadonlySet<string>; maybe: ReadonlySet<string> } {
	let starts = wordStarts.get(words)
	if (!starts) {
		const of = (words: readonly string[]) => new Set(words.flatMap((word) => [1, 2, longestWordStart].map((length) => word.slice(0, length))))
		const sure = of(words.sure)
		starts = { sure, maybe: of(words.maybe).difference(sure) }
		wordStarts.set(words, starts)
	}
	return starts
}

/** Whether a crafting table recipe could be made in the inventory's 2×2 grid, as a player can tell by looking at it. */
function fitsInventoryGrid(recipe: Recipe): boolean | undefined {
	const cells = recipe.in.keys().filter((cell) => slotOf(recipe.in[cell]) !== null).toArray()
	if (cells.length > 4) return false
	switch (recipe.cls) {
		case "dev.emi.emi.recipe.EmiShapedRecipe": {
			// Its 3×3 grid, row by row, empty cells included.
			if (recipe.in.length !== 9) return undefined
			const rows = cells.map((cell) => Math.floor(cell / 3))
			const columns = cells.map((cell) => cell % 3)
			return Math.max(...rows) - Math.min(...rows) < 2 && Math.max(...columns) - Math.min(...columns) < 2
		}
		case "dev.emi.emi.recipe.EmiShapelessRecipe":
			// EMI lays them out 3 to a row, so 3 or 4 of them look like they would not fit.
			return cells.length <= 2 ? true : undefined
		default:
			return undefined
	}
}

/**
 * Whether EMI draws its shapeless icon, two crossing arrows, on a recipe's card. It does on every shapeless crafting
 * recipe and on some of the crafting recipes a mod made, which the dump cannot tell apart.
 */
function showsShapelessIcon(recipe: Recipe): boolean | undefined {
	if (recipe.cat !== "minecraft:crafting") return false
	switch (recipe.cls) {
		case "dev.emi.emi.recipe.EmiShapelessRecipe":
			return true
		case "dev.emi.emi.recipe.EmiShapedRecipe":
			return false
		default:
			return undefined
	}
}

class IndexedQuestion implements CatalogQuestion {
	constructor(
		private readonly catalog: AkinatorCatalog,
		readonly id: number,
		readonly description: string,
		readonly priority: number,
	) {}

	answer(candidate: number): boolean | undefined {
		return this.catalog.answerById(this.id, candidate)
	}
}

/** Lists of numbers, one per candidate, packed: list `c` is `values[offsets[c]]` to `values[offsets[c + 1] - 1]`. */
class PackedLists {
	private readonly offsets: Uint32Array
	private readonly values: Uint32Array

	constructor(lists: readonly (readonly number[])[]) {
		this.offsets = new Uint32Array(lists.length + 1)
		lists.forEach((list, c) => this.offsets[c + 1] = this.offsets[c] + list.length)
		this.values = new Uint32Array(this.offsets[lists.length])
		lists.forEach((list, c) => this.values.set(list, this.offsets[c]))
	}

	/** Whether list `c`, ascending, has `value`. */
	has(c: number, value: number): boolean {
		let low = this.offsets[c]
		const end = this.offsets[c + 1]
		let high = end
		while (low < high) {
			const middle = (low + high) >>> 1
			if (this.values[middle] < value) low = middle + 1
			else high = middle
		}
		return low < end && this.values[low] === value
	}

	/** Adds `weights[c]` to `sums` at every value of list `c`, for every `c` of `lists`. */
	tally(lists: Uint32Array, weights: Float64Array, sums: Float64Array): void {
		for (const c of lists) {
			const weight = weights[c]
			const end = this.offsets[c + 1]
			for (let i = this.offsets[c]; i < end; i++) sums[this.values[i]] += weight
		}
	}
}

/**
 * Every question the game can ask, and what the answer to each is for every recipe. The recipes are those with an EMI
 * id, the only ones the game can name when it finds one: the candidates, numbered by their order in recipes.json.
 */
class AkinatorCatalog {
	readonly questions: readonly CatalogQuestion[]
	private readonly candidates: readonly Candidate[]
	/** Of each candidate, the questions whose answer is "yes", and those either answer is honest for. */
	private readonly yes: PackedLists
	private readonly maybe: PackedLists
	/**
	 * For each question, whether a candidate of a {@link slotless} category answers it otherwise: 1 for a layout question
	 * (of slots, amounts and places), either answer to which is honest; 2 for a sighting one ("involve X at all"), either
	 * answer to which is honest unless the page lists X. 1 for each candidate of a slotless category.
	 */
	private readonly kinds: Uint8Array
	private readonly slotless: Uint8Array
	/** Of each candidate, the {@link ListWords} of its names in each list. */
	private readonly words: Readonly<Record<NameList, readonly ListWords[]>>

	private constructor(
		questions: readonly Omit<CatalogQuestion, "id" | "answer">[],
		candidates: readonly Candidate[],
		yes: readonly (readonly number[])[],
		maybe: readonly (readonly number[])[],
		kinds: ReadonlyMap<number, "layout" | "sighting">,
		words: AkinatorCatalog["words"],
	) {
		this.questions = questions.map(({ description, priority }, id) => new IndexedQuestion(this, id, description, priority))
		this.candidates = candidates
		const recipeIds = [...new Set(candidates.map(({ emiRecipeId }) => emiRecipeId))]
		const numbers = new Map(recipeIds.map((id, number) => [id, number]))
		this.recipeIds = recipeIds
		this.recipeNumbers = Uint32Array.from(candidates, ({ emiRecipeId }) => numbers.get(emiRecipeId)!)
		this.yes = new PackedLists(yes)
		this.maybe = new PackedLists(maybe)
		this.kinds = Uint8Array.from(questions, (_, id) => ({ layout: 1, sighting: 2, none: 0 })[kinds.get(id) ?? "none"])
		this.slotless = Uint8Array.from(candidates, (recipe) => slotless.has(recipe.cat) ? 1 : 0)
		this.words = words
	}

	get size(): number {
		return this.candidates.length
	}

	/** Of each candidate, the number of its recipe id among {@link recipeIds}: several candidates may share an id. */
	readonly recipeNumbers: Uint32Array
	/** Every distinct recipe id of the candidates. */
	readonly recipeIds: readonly string[]

	/** The recipe that `candidate` is. */
	recipe(candidate: number): Candidate {
		return this.candidates[candidate]
	}

	/** The answer to question number `question` of {@link questions} for `candidate`, as {@link AkinatorQuestion.answer}. */
	answerById(question: number, candidate: number): boolean | undefined {
		if (this.slotless[candidate] && this.kinds[question] === 1) return undefined
		if (this.yes.has(candidate, question)) return true
		if (this.slotless[candidate] && this.kinds[question] === 2) return undefined
		if (this.maybe.has(candidate, question)) return undefined
		return false
	}

	/** For every question of {@link questions}, by id, its {@link Tally} of `candidates`, each weighing `weights[candidate]`. */
	count(candidates: Uint32Array, weights: Float64Array): { yes: Float64Array; maybe: Float64Array } {
		const yes = new Float64Array(this.questions.length)
		const maybe = new Float64Array(this.questions.length)
		this.yes.tally(candidates, weights, yes)
		this.maybe.tally(candidates, weights, maybe)
		// Slotless candidates record no answer to the questions they answer otherwise, but a "yes" to a sighting one.
		const slotless = candidates.filter((candidate) => this.slotless[candidate])
		if (slotless.length) {
			const slotlessWeight = slotless.reduce((sum, candidate) => sum + weights[candidate], 0)
			const slotlessYes = new Float64Array(this.questions.length)
			this.yes.tally(slotless, weights, slotlessYes)
			for (let question = 0; question < maybe.length; question++) {
				if (this.kinds[question] === 1) maybe[question] += slotlessWeight
				else if (this.kinds[question] === 2) maybe[question] += slotlessWeight - slotlessYes[question]
			}
		}
		return { yes, maybe }
	}

	/**
	 * For each list of names, the question "does a word of one of them start with C, or with Co?" that `score` rates the
	 * highest, with its {@link Tally} of `candidates` weighing `weights`, skipping the questions `skip` describes. Made up
	 * for these candidates, so not one of {@link questions}.
	 */
	byWordStart(
		candidates: Uint32Array,
		weights: Float64Array,
		skip: ReadonlySet<string>,
		score: (tally: Tally) => number,
	): (Tally & { question: AkinatorQuestion })[] {
		return Object.entries(nameLists).flatMap(([list, verb]) => {
			const words = this.words[list as NameList]
			const tallies = new Map<string, { yes: number; maybe: number }>()
			for (const candidate of candidates) {
				const weight = weights[candidate]
				const { sure, maybe } = startsOf(words[candidate])
				for (const start of sure) {
					const tally = tallies.get(start) ?? { yes: 0, maybe: 0 }
					tally.yes += weight
					tallies.set(start, tally)
				}
				for (const start of maybe) {
					const tally = tallies.get(start) ?? { yes: 0, maybe: 0 }
					tally.maybe += weight
					tallies.set(start, tally)
				}
			}
			const describe = (start: string) => `${verb} with any word matching "**${start.charAt(0).toUpperCase() + start.slice(1)}**..."`
			let best: [string, Tally, number] | undefined
			for (const [start, tally] of tallies) {
				const rating = score(tally)
				if (rating > (best?.[2] ?? 0) && !skip.has(describe(start))) best = [start, tally, rating]
			}
			if (!best) return []
			const [start, tally] = best
			return [{
				question: {
					description: describe(start),
					answer: (candidate: number) => {
						const { sure, maybe } = words[candidate]
						if (sure.some((word) => word.startsWith(start))) return true
						return maybe.some((word) => word.startsWith(start)) ? undefined : false
					},
				},
				...tally,
			}]
		})
	}

	static async load(): Promise<AkinatorCatalog> {
		const lang = await readLang()
		/** The first translation of `keys` there is, if any: a name only ever comes from the game, never from an id. */
		const translation = (...keys: string[]) => keys.values().map((key) => lang.get(key)).find((text) => text !== undefined)
		/** The name EMI shows a category by, where the key it takes it from is known. */
		const categoryName = (category: string) => {
			const [namespace, path] = category.split(":", 2)
			return translation(
				`emi.category.${namespace}.${path.replaceAll("/", ".")}`,
				...namespace === "gtceu" ? [`gtceu.${path}`, `gtceu.jei.${path}`] : [], // GregTech's recipe types and pages
			)
		}
		/** The name EMI shows a tag by, `registry` being the last part of its registry's id. */
		const tagName = (registry: string, tag: string) => {
			const [namespace, path] = tag.split(":", 2)
			return translation(`tag.${registry}.${namespace}.${path.replaceAll("/", ".")}`)
		}

		const questions: Omit<CatalogQuestion, "id" | "answer">[] = []
		const byKey = new Map<string, number>()
		/** The questions of a kind a {@link slotless} recipe answers otherwise, as {@link AkinatorCatalog.kinds} says. */
		const kinds = new Map<number, "layout" | "sighting">()
		const candidates: Candidate[] = []
		const yesLists: number[][] = []
		const maybeLists: number[][] = []
		const nameWords: Record<NameList, ListWords[]> = { out: [], in: [], tag: [], listed: [] }
		/** One {@link ListWords} per distinct one, since many candidates have the same names. */
		const sharedListWords = new Map<string, ListWords>()
		const idOf = (key: string, priority: number, describe: () => string, kind?: "layout" | "sighting") => {
			let id = byKey.get(key)
			if (id === undefined) {
				id = questions.length
				byKey.set(key, id)
				questions.push({ description: describe(), priority })
				if (kind) kinds.set(id, kind)
			}
			return id
		}

		for (const [index, recipe] of recipes.entries()) {
			if (!isCandidate(recipe)) continue
			const isSlotless = slotless.has(recipe.cat)
			const yes = new Set<number>()
			const maybe = new Set<number>()
			/**
			 * Records `value` as the answer to the question of `key`, made by `describe` the first time. "Maybe" never
			 * overrides a "yes". Of a slotless recipe, a layout question is "maybe" whatever `value` is, and so is a
			 * sighting one unless `value` is "yes", which the catalog knows without a record.
			 */
			const answer = (key: string, priority: number, describe: () => string, value: boolean | undefined, kind?: "layout" | "sighting") => {
				const id = idOf(key, priority, describe, kind)
				if (isSlotless && (kind === "layout" || (kind === "sighting" && value !== true))) return
				if (value === true) yes.add(id)
				else if (value === undefined) maybe.add(id)
			}
			const names: Record<NameList, { sure: string[]; maybe: string[] }> = {
				out: { sure: [], maybe: [] },
				in: { sure: [], maybe: [] },
				tag: { sure: [], maybe: [] },
				listed: { sure: [], maybe: [] },
			}
			/** How many input slots show each thing, by what a player tells it by. */
			const inputs = new Map<string, number>()
			const itemTotals = { in: 0, out: 0 }
			let cycles = false
			let chance = false

			const category = categoryName(recipe.cat)
			if (category !== undefined) {
				answer(`category ${category}`, 0, () => `belong to the **${escaped(category)}** category`, true)
			}
			answer("shapeless", 2, () => "show EMI's shapeless icon, two crossing arrows, in the corner of its crafting grid", showsShapelessIcon(recipe))
			if (recipe.cat === "minecraft:crafting") {
				answer("2x2", 2, () => "fit in the 2×2 crafting grid of the player's inventory", fitsInventoryGrid(recipe))
			}
			const shownTag = recipe.cat === "emi:tag" && /^emi:\/tag\/([^/]+)\/([^/]+)\/(.+)$/.exec(recipe.emiRecipeId)
			if (shownTag) {
				const [, registry, namespace, path] = shownTag
				const name = tagName(registry, `${namespace}:${path}`)
				if (name !== undefined) names.tag.sure.push(name)
			}

			const slotCounts = { in: { any: 0, item: 0, fluid: 0 }, cats: { any: 0, item: 0, fluid: 0 }, out: { any: 0, item: 0, fluid: 0 } }
			/** The total amount of each thing used up or produced, over every slot showing it, by what a player tells it by. */
			const amounts = { in: new Map<string, { type: MatterType; n: number }>(), out: new Map<string, { type: MatterType; n: number }>() }
			/**
			 * Every named thing the recipe lists, by `type name`: the places it is in, each with whether some slot there shows
			 * it alone rather than among options. An output is no sure input for being one option of an input's tag.
			 */
			const listed = new Map<string, { type: MatterType; name: string; places: Map<Place, boolean> }>()
			/** Words that every option of some slot has, so it shows them whichever option it is on, by what they are in lower case. */
			const slotWords = new Map<string, { word: string; places: Set<Place> }>()

			for (const role of ["in", "cats", "out"] as const) {
				for (const ingredient of recipe[role]) {
					const slot = slotOf(ingredient)
					if (!slot) continue
					const { type } = slot
					const optionNames = type ? slot.ids.values().map((id) => matterNames[type].get(id)).filter((name) => name !== undefined).toArray() : []
					/** Whether the slot shows one thing, rather than cycling through options. A tag page shows them all at once. */
					const alone = slot.ids.length === 1 || recipe.cat === "emi:tag"
					/** What a player tells the slot's contents by: its name if it shows one thing, else what it cycles through. */
					const identity = slot.ids.length === 1 ? `${type} ${optionNames[0] ?? slot.ids[0]}` : `${type} ${slot.tag ?? slot.ids.join(" ")}`

					slotCounts[role].any++
					if (type) slotCounts[role][type]++
					if (role === "in") inputs.set(identity, (inputs.get(identity) ?? 0) + 1)
					if (type && role !== "cats") {
						const amount = amounts[role].get(identity) ?? { type, n: 0 }
						amount.n += slot.ingredient.n
						amounts[role].set(identity, amount)
						if (type === "item") itemTotals[role] += slot.ingredient.n
					}
					if (slot.ids.length > 1 && recipe.cat !== "emi:tag") cycles = true
					if (role === "out" && slot.ingredient.c !== undefined && slot.ingredient.c < 1) chance = true
					if (type === "fluid") answer("fluid", 1, () => "involve any fluid at all", true)

					for (const name of optionNames) {
						const thing = listed.get(`${type} ${name}`) ?? { type: type!, name, places: new Map<Place, boolean>() }
						thing.places.set(role, thing.places.get(role) || alone)
						listed.set(`${type} ${name}`, thing)
						if (recipe.cat === "emi:tag") names.listed.sure.push(name)
					}
					if (!alone && optionNames.length && optionNames.length === slot.ids.length) {
						const [first, ...rest] = optionNames.map(wordsOf)
						const restWords = rest.map((words) => new Set(words.map((word) => word.toLowerCase())))
						for (const word of first.filter((word) => restWords.every((words) => words.has(word.toLowerCase())))) {
							const shared = slotWords.get(word.toLowerCase()) ?? { word, places: new Set<Place>() }
							shared.places.add(role)
							slotWords.set(word.toLowerCase(), shared)
						}
					}
					if (slot.ids.length > 1 && slot.tag && type && recipe.cat !== "emi:tag") {
						const tagged = tagName(type, slot.tag)
						if (tagged !== undefined) answer(`tag ${type} ${tagged}`, 5, () => `accept any ${type} tagged **${escaped(tagged)}**`, true)
					}
				}
			}

			// Where the dump has the recipe's picture, a player sees what it draws, which is not always what the recipe lists:
			// a machine's heating coil or the Blaze Powder of a brewing stand, drawn but not listed; a workstation listed but
			// not drawn. Either answer about those is honest.
			const drawn = widgets?.drawn(index)
			const nameOf = ({ type, id }: Matter) => {
				const name = matterNames[type].get(id)
				return name === undefined ? undefined : `${type} ${name}`
			}
			const drawnEvery = drawn && new Set(drawn.every.map(nameOf))
			for (const matter of drawn?.sure ?? []) {
				const name = matterNames[matter.type].get(matter.id)
				if (name === undefined || listed.has(`${matter.type} ${name}`)) continue
				listed.set(`${matter.type} ${name}`, { type: matter.type, name, places: new Map([["in", false], ["cats", false]]) })
			}

			/** The names in each place, as `type name`: all of them, and those a player sees for sure. */
			const shown = { any: { in: new Set<string>(), cats: new Set<string>(), out: new Set<string>() }, sure: { in: new Set<string>(), cats: new Set<string>(), out: new Set<string>() } }
			/** The answer to each question of words, by the word in lower case: "yes" wins over "maybe". */
			const wordAnswers = new Map<string, { word: string; any: boolean | undefined; out: boolean | undefined | null }>()
			const answerWord = (word: string, place: Place, value: boolean | undefined) => {
				const known = wordAnswers.get(word.toLowerCase()) ?? { word, any: undefined, out: null }
				if (value === true) known.any = true
				if (place === "out" && (value === true || known.out === null)) known.out = value
				wordAnswers.set(word.toLowerCase(), known)
			}
			for (const [key, { type, name, places }] of listed) {
				const drawnHere = !drawnEvery || drawnEvery.has(key)
				const matter = `the ${type} **${escaped(name)}**`
				answer(`involve ${key}`, 7, () => `involve ${matter} at all`, places.values().some((alone) => alone) && drawnHere ? true : undefined, "sighting")
				for (const [place, alone] of places) {
					const seen = alone && drawnHere ? true : undefined
					answer(
						`${place} ${key}`,
						8,
						() => {
							switch (place) {
								case "in":
									return `take ${matter} as an input`
								case "cats":
									return `need ${matter} without using it up`
								case "out":
									return `produce ${matter}`
							}
						},
						seen,
						"layout",
					)
					shown.any[place].add(key)
					if (seen) shown.sure[place].add(key)
					if (place !== "cats") names[place][seen ? "sure" : "maybe"].push(name)
					for (const word of wordsOf(name)) answerWord(word, place, seen)
				}
			}
			const drawnWords = drawnEvery && new Set(drawnEvery.values().filter((key) => key !== undefined).flatMap((key) => wordsOf(key)).map((word) => word.toLowerCase()))
			for (const { word, places } of slotWords.values()) {
				const seen = !drawnWords || drawnWords.has(word.toLowerCase()) ? true : undefined
				for (const place of places) answerWord(word, place, seen)
			}
			for (const [key, { word, any, out }] of wordAnswers) {
				answer(`word ${key}`, 6, () => `involve anything with the word **${escaped(word)}** in its name`, any, "sighting")
				if (out !== null) answer(`word out ${key}`, 6, () => `produce anything with the word **${escaped(word)}** in its name`, out, "layout")
			}

			answer("cycles", 2, () => "have a slot that cycles through several options", cycles, "layout")
			answer("chance", 2, () => "produce something only by chance", chance, "layout")

			for (const place of ["in", "cats", "out"] as const) {
				for (const [type, count] of Object.entries(slotCounts[place]) as [keyof typeof slotCounts.in, number][]) {
					if (place === "cats") continue
					for (let n = 0; n < count; n++) {
						answer(
							`slots ${place} ${type} ${n}`,
							1,
							() => {
								const side = place === "in" ? "input" : "output"
								if (n === 0) {
									const what = type === "any" ? "any" : `any ${type}`
									return place === "in" ? `have ${what} inputs at all` : `have ${what} outputs at all`
								}
								switch (type) {
									case "any":
										return `fill more than ${plural(n, `${side} slot`)}, counting fluid tanks`
									case "item":
										return `fill more than ${plural(n, `item ${side} slot`)}`
									case "fluid":
										return `have more than ${plural(n, `fluid ${side}`)}`
								}
							},
							true,
							"layout",
						)
					}
				}
			}

			// A catalyst the picture does not draw, like the Framing Saw, or something it draws but the recipe does not list,
			// like a machine's heating coil, may or may not count as one.
			const catalysts = listed.values().filter(({ places }) => places.has("cats")).toArray()
			const catalystSeen = catalysts.some(({ type, name }) => shown.sure.cats.has(`${type} ${name}`)) || (!drawn && slotCounts.cats.any > 0)
			answer(
				"slots cats any 0",
				1,
				() => "need something that it does not use up, such as a **Programmed Circuit**",
				catalystSeen ? true : slotCounts.cats.any || catalysts.length ? undefined : false,
				"layout",
			)

			for (const role of ["in", "out"] as const) {
				for (const type of matterTypes) {
					const most = Math.max(0, ...amounts[role].values().filter((amount) => amount.type === type).map(({ n }) => n))
					for (const step of amountSteps[type].filter((step) => step < most)) {
						answer(`amount ${role} ${type} ${step}`, 3, () => `${role === "in" ? "use up" : "produce"} more than ${amountUnit[type](step)}`, true, "layout")
					}
				}
			}
			for (let n = 1; n < inputs.size; n++) {
				answer(`kinds ${n}`, 1, () => `take more than ${plural(n, "different thing")} as inputs, fluids included`, true, "layout")
			}
			answer("repeated", 2, () => "have the same thing in more than one input slot", inputs.values().some((count) => count > 1), "layout")
			answer("more items", 2, () => "produce more items than it uses up, adding up the counts of all its item slots", itemTotals.out > itemTotals.in, "layout")

			const takenIn = (things: Record<Place, Set<string>>) => things.out.values().some((thing) => things.in.has(thing) || things.cats.has(thing))
			answer(
				"returned",
				2,
				() => "produce something that it also takes in, as an input or a catalyst",
				takenIn(shown.sure) ? true : takenIn(shown.any) ? undefined : false,
				"layout",
			)
			const sharesWord = (inputs: Iterable<string>, outputs: Iterable<string>) => !spelledWordsOf(inputs).isDisjointFrom(spelledWordsOf(outputs))
			answer(
				"shared word",
				2,
				() => "have an input and an output that share a word in their names, like **Iron Dust** and **Iron Ingot**",
				sharesWord(names.in.sure, names.out.sure) ? true : sharesWord([...names.in.sure, ...names.in.maybe], [...names.out.sure, ...names.out.maybe]) ? undefined : false,
				"layout",
			)

			candidates.push(recipe)
			yesLists.push(yes.values().toArray().sort((a, b) => a - b))
			maybeLists.push(maybe.difference(yes).values().toArray().sort((a, b) => a - b))
			for (const list of Object.keys(nameLists) as NameList[]) {
				// Of a slotless page, what it shows is not its inputs or its outputs, though a player might call it that.
				const unsure = isSlotless && (list === "in" || list === "out")
				const sure = unsure ? [] : [...spelledWordsOf(names[list].sure)]
				const maybe = [...spelledWordsOf(unsure ? [...names[list].sure, ...names[list].maybe] : names[list].maybe).difference(new Set(sure))]
				const key = `${sure.join(" ")}|${maybe.join(" ")}`
				if (!sharedListWords.has(key)) sharedListWords.set(key, { sure, maybe })
				nameWords[list].push(sharedListWords.get(key)!)
			}
		}

		return new AkinatorCatalog(questions, candidates, yesLists, maybeLists, kinds, nameWords)
	}
}

export type { AkinatorCatalog }
export const akinatorCatalog = await AkinatorCatalog.load()

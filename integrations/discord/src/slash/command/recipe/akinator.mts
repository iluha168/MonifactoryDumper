import { type ActionRow, type ButtonComponent, ButtonStyles, MessageComponentTypes } from "discordeno"
import { SubCommand } from "../../lib/leaf/SubCommand.mts"
import { emojis } from "../config.mts"
import z from "zod"
import { stills } from "../../../dump/stills.mts"
import { recipes } from "../../../dump/recipes.mts"
import { drawRecipe, pictureToAttachment } from "../../../picture/draw.mts"
import { akinatorCatalog, type AkinatorQuestion, type Tally } from "../../../akinator/questions.mts"

const schemaAnswers = z.literal(["yes", "no", "idk"])
type Answer = z.infer<typeof schemaAnswers>

const buttons = {
	yes: { style: ButtonStyles.Success, label: "Yes", emoji: emojis.btnYes },
	idk: { style: ButtonStyles.Secondary, label: "Not sure", emoji: emojis.btnNotSure },
	no: { style: ButtonStyles.Danger, label: "No", emoji: emojis.btnNo },
} as const satisfies Record<Answer, Pick<ButtonComponent, "style" | "label" | "emoji">>

/** A row of the buttons of `answers`. */
const answerRow = (...answers: Answer[]): ActionRow[] => [{
	type: MessageComponentTypes.ActionRow,
	components: answers.map((answer): ButtonComponent => ({
		type: MessageComponentTypes.Button,
		customId: schemaAnswers.encode(answer),
		...buttons[answer],
	})) as ActionRow["components"],
}]

/**
 * Removes the picture of an earlier guess: Discord keeps a message's attachments through edits unless told which to
 * keep. discordeno sends it along, but does not type it.
 */
const withoutPictures = { attachments: [] }

export const commandRecipeAkinator = new SubCommand(
	{
		name: "akinator",
		description: "Mini-game. I will guess your recipe with simple yes or no questions!",
		options: [],
	},
	{},
	{
		run(interaction) {
			const game = AkinatorGame.create(interaction.id, () =>
				interaction
					.edit({ content: `<@${interaction.user.id}> has left the game.`, components: [], ...withoutPictures })
					.catch(() => null))
			return interaction.respond({
				content: game.questionHumanReadable(),
				components: answerRow("yes", "idk", "no"),
			})
		},
		async component(interaction, { customId }, message) {
			if (interaction.user.id !== message.interaction?.user.id) {
				return interaction.respond(`This is not your game! Create your own with \`/${message.interaction?.name}\`.`, { isPrivate: true })
			}
			const answer = schemaAnswers.safeParse(customId).data
			if (answer === undefined) return // Silently, i think users can forge these.
			const game = AkinatorGame.get(message.interaction.id)
			if (!game) return interaction.respond({ content: "Sorry, this game has expired." }, { isPrivate: true })
			const nextTurn = game.insertAnswer(answer)
			switch (nextTurn.type) {
				case "question":
					return interaction.edit({ content: game.questionHumanReadable(), components: answerRow("yes", "idk", "no"), ...withoutPictures })
				case "guess": {
					await interaction.edit({ content: "Aha!", components: [], ...withoutPictures })
					const recipePreview = stills && await recipes
						.find((recipe) => recipe.emiRecipeId === nextTurn.recipeID && recipe.image)
						?.image
						?.read()
						.then((layers) => drawRecipe(layers, stills!, "default"))
						.catch((e) => console.error("Drawing a recipe failed", e))
					return interaction.edit({
						content: `${emojis.info} I think your recipe is \`${nextTurn.recipeID}\`. Am I right?`,
						files: recipePreview ? [pictureToAttachment(recipePreview)] : undefined,
						...withoutPictures,
						components: answerRow("yes", "no"),
					})
				}
				case "found":
					AkinatorGame.delete(message.interaction.id)
					return interaction.edit({ content: `${emojis.info} I knew it! Your recipe is \`${nextTurn.recipeID}\`.`, components: [] })
				case "fail":
					AkinatorGame.delete(message.interaction.id)
					return interaction.edit({
						content: `${emojis.errorExpected} You have bested me! I could not find your recipe. Unless you cheated...\n`
							+ `My next guesses would have been: ${nextTurn.recipeIDs.map((id) => `\`${id}\``).join(", ")}.`,
						components: [],
						...withoutPictures,
					})
				default:
					nextTurn satisfies never
			}
		},
	},
)

/**
 * What comes of an answer: another question to ask, or a recipe to ask the player to confirm; or the end of the game, with
 * the recipe confirmed, or the ones the game would have guessed next.
 */
type AkinatorTurn =
	| { type: "question" }
	| { type: "guess"; recipeID: string }
	| { type: "found"; recipeID: string }
	| { type: "fail"; recipeIDs: string[] }

/** How many bits of information a yes or no that is "yes" with probability `p` carries. */
const entropy = (p: number) => p <= 0 || p >= 1 ? 0 : -p * Math.log2(p) - (1 - p) * Math.log2(1 - p)

/**
 * How often a player is expected to answer a question wrong by accident. Any answer may be one of those, so none rules a
 * recipe out; an answer against a recipe makes it this many times less likely than one for it, as a bet on the odds.
 */
const mistakeRate = 0.1
/** By how much an answer changes how likely a recipe is, as a natural log: none if it agrees, and so on. */
const answerWeight = {
	agrees: 0,
	disagrees: Math.log(mistakeRate / (1 - mistakeRate)),
	/** An answer either way is honest for the recipe, so it is as likely as a coin flip. */
	either: Math.log(0.5 / (1 - mistakeRate)),
}
/** Once a recipe is at least this likely, the game asks the player whether it is theirs. */
const guessAbove = 0.95
/**
 * After this many questions, a recipe only needs to be {@link patientGuessAbove} likely to be guessed. A guess tells
 * less than a question, but it is the one question a player cannot answer wrong by accident, with the recipe in front
 * of them.
 */
const patientAfter = 40
const patientGuessAbove = 0.5
/** After this many questions, the game only guesses, likeliest first. A wrong guess before that, it asks on. */
const questionLimit = 100
/** After this many wrong guesses, the game gives up. */
const guessLimit = 8
/**
 * Recipes this much less likely than the likeliest, as a natural log, are forgotten to save time: some 21 more answers
 * against them. Out of 72,000, all of them together are too unlikely to matter.
 */
const forgetBelow = Math.log(1e-20)
/** How many bits of information a question must be expected to give to be worth asking. */
const leastGain = 0.01
/** How much more a question of how a word starts must tell than the best catalog question, which reads better. */
const wordStartAbove = 1.25
/** The most bits of information any question can be expected to give: that of a fair coin, less the chance of a mistake. */
const mostGain = 1 - entropy(mistakeRate)
/** Questions of how a word starts are only looked for once the best catalog question tells less than this many bits. */
const wordStartBelow = mostGain / wordStartAbove
/** Recipes at least this likely, next to the likeliest, are those questions of how a word starts are made for. */
const plausibleAbove = 1e-4

/**
 * How many bits of information about which recipe it is the answer to a question is expected to give, `tally` of
 * `total` weight being the likelihoods it holds for. The answer might be a mistake, and tells nothing of the recipes an
 * answer either way is honest for.
 */
function informationGain({ yes, maybe }: Tally, total: number): number {
	const no = total - yes - maybe
	const answeredYes = (yes * (1 - mistakeRate) + no * mistakeRate + maybe / 2) / total
	return entropy(answeredYes) - ((yes + no) * entropy(mistakeRate) + maybe) / total
}

/**
 * A game with one player, who has a recipe in mind. Rather than ruling recipes out, every answer makes those it agrees
 * with likelier than those it does not, so that no accidental lie loses the game: the likeliest recipe is guessed, and
 * the player confirms it or not, as Akinator does.
 */
class AkinatorGame {
	private static readonly games = new Map<bigint, AkinatorGame>()

	static create(id: bigint, cleanup: () => unknown) {
		const game = new AkinatorGame(setTimeout(
			() => {
				this.games.delete(id)
				cleanup()
			},
			14 * 60 * 1000, // Interaction tokens live for 15 minutes
		))
		this.games.set(id, game)
		return game
	}

	static get(id: bigint): AkinatorGame | undefined {
		return this.games.get(id)
	}

	static delete(id: bigint): boolean {
		const game = this.games.get(id)
		if (!game) return false
		clearTimeout(game.timer)
		return this.games.delete(id)
	}

	/**
	 * Of each candidate of {@link akinatorCatalog}, how likely the answers so far are if it is the player's recipe, as a
	 * natural log, over how likely they would be without a mistake: 0 until an answer disagrees with it.
	 */
	private readonly likelihood = new Float64Array(akinatorCatalog.size)
	/** The candidates still weighed: those not guessed wrong, nor forgotten. */
	private candidates = Uint32Array.from({ length: akinatorCatalog.size }, (_, candidate) => candidate)
	/** Every question asked, answered or not, by description: asked again, it gets the same answer, or an annoyed player. */
	private readonly asked = new Set<string>()
	private readonly wrongGuesses = new Set<string>()
	private pending: { type: "question"; question: AkinatorQuestion } | { type: "guess"; recipeID: string }

	private constructor(
		private readonly timer: NodeJS.Timeout,
	) {
		this.timer.unref()
		const question = this.bestQuestion(this.weigh())
		if (!question) {
			clearTimeout(this.timer)
			throw new Error("Programmer error, no first akinator question was found")
		}
		this.pending = { type: "question", question }
	}

	/** How likely each candidate is, relative to the likeliest, and all of them together. */
	private weigh(): { weights: Float64Array; total: number } {
		let best = -Infinity
		for (const candidate of this.candidates) best = Math.max(best, this.likelihood[candidate])
		const weights = new Float64Array(akinatorCatalog.size)
		let total = 0
		for (const candidate of this.candidates) total += weights[candidate] = Math.exp(this.likelihood[candidate] - best)
		return { weights, total }
	}

	/** The recipes the candidates are, likeliest first, with how likely each is. Several candidates may share an id. */
	private likeliestRecipes({ weights, total }: ReturnType<AkinatorGame["weigh"]>, count: number): [recipeID: string, probability: number][] {
		const byRecipe = new Float64Array(akinatorCatalog.recipeIds.length)
		for (const candidate of this.candidates) byRecipe[akinatorCatalog.recipeNumbers[candidate]] += weights[candidate]
		const likeliest: [number, number][] = []
		byRecipe.forEach((weight, recipe) => {
			if (!weight || (likeliest.length === count && weight <= likeliest[count - 1][1])) return
			likeliest.splice(likeliest.findIndex(([, known]) => known < weight) >>> 0, 0, [recipe, weight])
			likeliest.length = Math.min(likeliest.length, count)
		})
		return likeliest.map(([recipe, weight]) => [akinatorCatalog.recipeIds[recipe], weight / total])
	}

	/**
	 * The question not asked yet whose answer is expected to tell the most about which recipe it is, if it tells enough.
	 * Plainer questions win close calls, and questions of how a word starts are the last resort: they split recipes that
	 * differ only in a material, but a question about what the recipe is reads better.
	 */
	private bestQuestion({ weights, total }: ReturnType<AkinatorGame["weigh"]>): AkinatorQuestion | null {
		const gainOf = (tally: Tally) => informationGain(tally, total)
		const counts = akinatorCatalog.count(this.candidates, weights)
		let best: AkinatorQuestion | null = null
		let bestGain = 0
		let bestScore = 0
		for (const question of akinatorCatalog.questions) {
			const gain = gainOf({ yes: counts.yes[question.id], maybe: counts.maybe[question.id] })
			const score = gain * (1 - question.priority / 50)
			if (score > bestScore && gain >= leastGain && !this.asked.has(question.description)) {
				best = question
				bestGain = gain
				bestScore = score
			}
		}
		if (bestGain >= wordStartBelow) return best
		// Words of every candidate are many to go through, and the unlikely ones hardly change what a question tells.
		const plausible = this.candidates.filter((candidate) => weights[candidate] >= plausibleAbove)
		const plausibleTotal = plausible.reduce((sum, candidate) => sum + weights[candidate], 0)
		const plausibleGainOf = (tally: Tally) => informationGain(tally, plausibleTotal)
		for (const { question, ...tally } of akinatorCatalog.byWordStart(plausible, weights, this.asked, plausibleGainOf)) {
			const gain = plausibleGainOf(tally)
			if (gain > bestGain * wordStartAbove && gain >= leastGain) {
				best = question
				bestGain = gain
			}
		}
		return best
	}

	/** Asks the best question, or guesses the likeliest recipe once it is likely enough, or nothing is worth asking. */
	private next(): AkinatorTurn {
		const weighed = this.weigh()
		const [[likeliest, probability]] = this.likeliestRecipes(weighed, 1)
		const likelyEnough = this.asked.size >= patientAfter ? patientGuessAbove : guessAbove
		const question = probability < likelyEnough && this.asked.size < questionLimit ? this.bestQuestion(weighed) : null
		if (question) {
			this.pending = { type: "question", question }
			return { type: "question" }
		}
		this.pending = { type: "guess", recipeID: likeliest }
		return { type: "guess", recipeID: likeliest }
	}

	questionHumanReadable(): string {
		if (this.pending.type !== "question") throw new Error("The game is guessing, not asking")
		return `Does your recipe ${this.pending.question.description}?`
	}

	insertAnswer(answer: Answer): AkinatorTurn {
		const pending = this.pending
		if (pending.type === "guess") {
			// The player sees the recipe itself, so takes no mistake: "not sure" is no button of a guess, and counts as a "no".
			if (answer === "yes") return { type: "found", recipeID: pending.recipeID }
			this.wrongGuesses.add(pending.recipeID)
			this.candidates = this.candidates.filter((candidate) => akinatorCatalog.recipe(candidate).emiRecipeId !== pending.recipeID)
			if (!this.candidates.length || this.wrongGuesses.size >= guessLimit) {
				return { type: "fail", recipeIDs: this.candidates.length ? this.likeliestRecipes(this.weigh(), 5).map(([recipeID]) => recipeID) : [] }
			}
			return this.next()
		}

		const { question } = pending
		this.asked.add(question.description)
		if (answer !== "idk") {
			const said = answer === "yes"
			let best = -Infinity
			for (const candidate of this.candidates) {
				const truth = question.answer(candidate)
				this.likelihood[candidate] += truth === undefined ? answerWeight.either : truth === said ? answerWeight.agrees : answerWeight.disagrees
				best = Math.max(best, this.likelihood[candidate])
			}
			this.candidates = this.candidates.filter((candidate) => this.likelihood[candidate] - best >= forgetBelow)
		}
		return this.next()
	}
}

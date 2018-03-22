package net.mbonnin.arcanetracker

import android.os.Bundle
import android.os.Handler
import com.google.firebase.analytics.FirebaseAnalytics
import net.mbonnin.arcanetracker.hsreplay.HSReplay
import net.mbonnin.arcanetracker.hsreplay.model.UploadRequest
import net.mbonnin.arcanetracker.model.GameSummary
import net.mbonnin.arcanetracker.parser.Entity
import net.mbonnin.arcanetracker.parser.Game
import net.mbonnin.arcanetracker.parser.GameLogic
import net.mbonnin.arcanetracker.parser.LoadingScreenParser
import net.mbonnin.arcanetracker.room.WLCounter
import net.mbonnin.arcanetracker.trackobot.Trackobot
import net.mbonnin.arcanetracker.trackobot.model.CardPlay
import net.mbonnin.arcanetracker.trackobot.model.Result
import net.mbonnin.arcanetracker.trackobot.model.ResultData
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import timber.log.Timber
import java.util.*

class GameLogicListener private constructor() : GameLogic.Listener {
    private val mHandler: Handler
    var currentGame: Game? = null

    override fun gameStarted(game: Game) {
        Timber.w("gameStarted")

        var deck = MainViewCompanion.legacyCompanion.deck
        if (Settings.get(Settings.AUTO_SELECT_DECK, true)) {
            if (LoadingScreenParser.MODE_DRAFT == LoadingScreenParser.get().gameplayMode) {
                deck = LegacyDeckList.arenaDeck
                Timber.w("useArena deck")
            } else {
                val classIndex = game.getPlayer().classIndex()

                /*
                 * we filter the original deck to remove the coin mainly
                 */
                val map = game.getEntityList { entity ->
                    (game.player.entity.PlayerID == entity.tags[Entity.KEY_CONTROLLER]
                            && Entity.ZONE_HAND == entity.tags[Entity.KEY_ZONE]
                            && game.player.entity.PlayerID == entity.extra.originalController)
                }
                        .toCardMap()

                deck = activateBestDeck(classIndex, map)
            }
        }

        MainViewCompanion.legacyCompanion.deck = deck

        LegacyDeckList.opponentDeck.clear()
        LegacyDeckList.opponentDeck.classIndex = game.getOpponent().classIndex()
        MainViewCompanion.opponentCompanion.deck = LegacyDeckList.opponentDeck

        currentGame = game

        currentGame!!.rank = FMRHolder.rank
    }

    override fun gameOver() {
        val mode = LoadingScreenParser.get().gameplayMode

        Timber.w("gameOver  %s [gameType %s][formatType %s][mode %s] [user %s]",
                if (currentGame!!.victory) "victory" else "lost",
                currentGame!!.gameType,
                currentGame!!.formatType,
                mode,
                Trackobot.get().currentUser())

        val legacyDeck = MainViewCompanion.legacyCompanion.deck

        if (legacyDeck != null) {
            if (LegacyDeckList.hasValidDeck()) {
                addKnownCardsToDeck(currentGame!!, legacyDeck)
            }

            if (currentGame!!.victory) {
                legacyDeck.wins++
            } else {
                legacyDeck.losses++
            }
            MainViewCompanion.legacyCompanion.deck = legacyDeck

            if (LegacyDeckList.ARENA_DECK_ID == legacyDeck.id) {
                LegacyDeckList.saveArena()
            } else {
                LegacyDeckList.save()
            }

        }

        val playerDeck = MainViewCompanion.playerCompanion.deck
        if (playerDeck != null) {
            updateCounter(playerDeck.id, currentGame!!.victory)
        }


        if ((Utils.isAppDebuggable || LoadingScreenParser.MODE_DRAFT == mode || LoadingScreenParser.MODE_TOURNAMENT == mode) && Trackobot.get().currentUser() != null) {
            val resultData = ResultData()
            resultData.result = Result()
            resultData.result.coin = currentGame!!.getPlayer().hasCoin
            resultData.result.win = currentGame!!.victory
            resultData.result.mode = Trackobot.getMode(currentGame!!.gameType, currentGame!!.formatType)
            if (currentGame!!.rank >= 0) {
                resultData.result.rank = currentGame!!.rank
            }
            resultData.result.hero = Trackobot.getHero(currentGame!!.player.classIndex())
            resultData.result.opponent = Trackobot.getHero(currentGame!!.opponent.classIndex())
            resultData.result.added = Utils.ISO8601DATEFORMAT.format(Date())

            val history = ArrayList<CardPlay>()
            for (play in currentGame!!.plays) {
                val cardPlay = CardPlay()
                cardPlay.player = if (play.isOpponent) "opponent" else "me"
                cardPlay.turn = (play.turn + 1) / 2
                cardPlay.card_id = play.cardId
                history.add(cardPlay)
            }

            resultData.result.card_history = history

            FirebaseAnalytics.getInstance(ArcaneTrackerApplication.context).logEvent("trackobot_upload", null)
            Trackobot.get().sendResult(resultData)
        }

        FileTree.get().sync()

        val bundle = Bundle()
        bundle.putString(EventParams.GAME_TYPE.value, currentGame!!.gameType)
        bundle.putString(EventParams.FORMAT_TYPE.value, currentGame!!.formatType)
        bundle.putString(EventParams.TRACK_O_BOT.value,  (Trackobot.get().currentUser() != null).toString())
        bundle.putString(EventParams.HSREPLAY.value,  (HSReplay.get().token() != null).toString())
        FirebaseAnalytics.getInstance(ArcaneTrackerApplication.context).logEvent("game_ended", bundle)
    }

    private fun updateCounter(id: String, victory: Boolean) {
        val winsIncrement = if (victory) 1 else 0
        WLCounter.increment(id, winsIncrement, 1 - winsIncrement)
    }

    private fun addKnownCardsToDeck(game: Game, deck: Deck) {

        val originalDeck = game.getEntityList { entity -> game.player.entity.PlayerID == entity.extra.originalController }
        val originalDeckMap = originalDeck.toCardMap()
        if (Settings.get(Settings.AUTO_ADD_CARDS, true) && Utils.cardMapTotal(deck.cards) < Deck.MAX_CARDS) {
            for (cardId in originalDeckMap.keys) {
                val found = originalDeckMap[cardId]!!
                if (found > Utils.cardMapGet(deck.cards, cardId)) {
                    Timber.w("adding card to the deck " + cardId)
                    deck.cards.put(cardId, found)
                }
            }
            LegacyDeckList.save()
        }

    }

    override fun somethingChanged() {

    }

    init {
        mHandler = Handler()

    }

    fun uploadGame(gameStr: String, gameStart: String) {
        val summary = GameSummary()
        val game = currentGame

        if (game == null) {
            return
        } else if (game.spectator) {
            return
        }

        Timber.d("ready to send hsreplay %s", game.gameType)

        /*when (game.bnetGameType) {
            BnetGameType.BGT_ARENA,
            BnetGameType.BGT_CASUAL_STANDARD_NEWBIE,
            BnetGameType.BGT_CASUAL_WILD,
            BnetGameType.BGT_CASUAL_STANDARD_NORMAL,
            BnetGameType.BGT_FRIENDS,
            BnetGameType.BGT_RANKED_STANDARD,
            BnetGameType.BGT_RANKED_WILD,
            BnetGameType.BGT_VS_AI -> Unit // Note that this will never happen because there's just one GOLD_REWARD_STATE in AI mode
            else -> return // do not send strange games to HSReplay
        }*/

        summary.coin = game.getPlayer().hasCoin
        summary.win = game.victory
        summary.hero = game.player.classIndex()
        summary.opponentHero = game.opponent.classIndex()
        summary.date = Utils.ISO8601DATEFORMAT.format(Date())
        summary.deckName = MainViewCompanion.playerCompanion.deck?.name

        GameSummary.addFirst(summary)

        val uploadRequest = UploadRequest()
        uploadRequest.match_start = gameStart
        uploadRequest.build = ArcaneTrackerApplication.get().hearthstoneBuild
        uploadRequest.spectator_mode = game.spectator
        uploadRequest.friendly_player = game.player.entity.PlayerID
        uploadRequest.game_type = fromGameAndFormat(game.gameType, game.formatType).intValue

        val player = if (uploadRequest.friendly_player == "1") uploadRequest.player1 else uploadRequest.player2

        if (game.rank > 0) {
            player.rank = game.rank
        }

        MainViewCompanion.playerCompanion.deck?.id?.toLongOrNull() ?.let {
            player.deck_id = it
        }

        MainViewCompanion.playerCompanion.deck?.let {
            it.cards.forEach {
                for (i in 0 until it.value) {
                    player.deck.add(it.key)
                }
            }
        }

        if (HSReplay.get().token() != null) {
            HSReplay.get().uploadGame(uploadRequest, gameStr)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe({
                        if (it.data != null) {
                            summary.hsreplayUrl = it.data
                            GameSummary.sync()
                            Timber.d("hsreplay upload success")
                            Toaster.show(ArcaneTrackerApplication.context.getString(R.string.hsreplaySuccess))
                        } else if (it.error != null) {
                            Timber.d(it.error)
                            Toaster.show(ArcaneTrackerApplication.context.getString(R.string.hsreplayError))
                        }
                    })
        }
    }

    companion object {

        private var sGameLogicListener: GameLogicListener? = null

        private fun activateBestDeck(classIndex: Int, initialCards: HashMap<String, Int>): Deck {
            val deck = MainViewCompanion.legacyCompanion.deck!!
            if (deckScore(deck, classIndex, initialCards) != -1) {
                // the current deck works fine
                return deck
            }

            // sort the deck list by descending number of cards. We'll try to get the one with the most cards.
            val index = ArrayList<Int>()
            for (i in 0 until LegacyDeckList.get().size) {
                index.add(i)
            }

            Collections.sort(index) { a, b -> LegacyDeckList.get()[b!!].cardCount - LegacyDeckList.get()[a!!].cardCount }

            var maxScore = -1
            var bestDeck: Deck? = null

            for (i in index) {
                val candidateDeck = LegacyDeckList.get()[i]

                val score = deckScore(candidateDeck, classIndex, initialCards)

                Timber.i("Deck selection " + candidateDeck.name + " has score " + score)
                if (score > maxScore) {
                    bestDeck = candidateDeck
                    maxScore = score
                }
            }

            if (bestDeck == null) {
                /*
             * No good candidate, create a new deck
             */
                bestDeck = LegacyDeckList.createDeck(classIndex)
            }

            return bestDeck!!
        }

        /**
         *
         */
        private fun deckScore(deck: Deck, classIndex: Int, mulliganCards: HashMap<String, Int>): Int {
            if (deck.classIndex != classIndex) {
                return -1
            }

            var matchedCards = 0
            var newCards = 0

            /*
         * copy the cards
         */
            val deckCards = HashMap(deck.cards)

            /*
         * iterate through the mulligan cards.
         *
         * count the one that match the original deck and remove them from the original deck
         *
         * if a card is not in the original deck, increase newCards. At the end, if the total of cards is > 30, the deck is not viable
         */
            for (cardId in mulliganCards.keys) {
                val inDeck = Utils.cardMapGet(deckCards, cardId)
                val inMulligan = Utils.cardMapGet(mulliganCards, cardId)

                val a = Math.min(inDeck, inMulligan)

                Utils.cardMapAdd(deckCards, cardId, -a)
                newCards += inMulligan - a
                matchedCards += a
            }

            return if (Utils.cardMapTotal(deckCards) + matchedCards + newCards > Deck.MAX_CARDS) {
                -1
            } else matchedCards

        }

        fun get(): GameLogicListener {
            if (sGameLogicListener == null) {
                sGameLogicListener = GameLogicListener()
            }

            return sGameLogicListener!!
        }
    }
}

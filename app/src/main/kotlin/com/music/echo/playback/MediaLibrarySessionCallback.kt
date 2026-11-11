package echo.music.iad1tya.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.MediaSessionConstants.CommandToggleLike
import echo.music.iad1tya.constants.MediaSessionConstants.CommandToggleRepeatMode
import echo.music.iad1tya.constants.MediaSessionConstants.CommandToggleShuffle
import echo.music.iad1tya.constants.MediaSessionConstants.CommandToggleStartRadio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Auto / Media3 library callback pro pohádky z deti-online.com. Kopíruje uspořádání webu:
 *
 *   Záložky nahoře (kořen):
 *     [Pokračovat]              rozposlouchané pohádky (jen když existují)
 *     [1–30] [31–60] [61–90]... stránky katalogu po GROUP_SIZE pohádkách
 *   Stránka -> pohádky jako dlaždice (obrázek, název, délka)
 *   Pohádka -> kapitoly (seznam)
 *
 * Historie: každých 15 s přehrávání, při pauze a při přechodu na další kapitolu se uloží
 * pohádka + kapitola + pozice. Dokončená pohádka se z historie odstraní.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class MediaLibrarySessionCallback @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val detiParser = DetiOnlineParser()
    private val history = DetiResumeStore(context)

    // Callbacky pro vlastní tlačítka v ovládání přehrávače
    var toggleLike: (() -> Unit)? = null
    var toggleStartRadio: (() -> Unit)? = null
    var toggleLibrary: (() -> Unit)? = null
    var onAutomotiveConnected: (() -> Unit)? = null

    /** Nastavuje MusicService; slouží jako záloha, kdyby se tracker ještě nepřipojil přes onConnect. */
    var playerProvider: (() -> Player?)? = null

    // ---- Cache ----------------------------------------------------------------------------

    private class CachedPage(
        val items: List<DetiTaleItem>,
        val ageLinks: List<DetiAgeLink>,
        val ts: Long
    )

    private class TaleData(
        val browseItems: List<MediaItem>, // do seznamu v autě (bez artworku -> rychlé)
        val playItems: List<MediaItem>,   // do fronty přehrávače (s artworkem)
        val ts: Long
    )

    private val listCache = ConcurrentHashMap<String, CachedPage>()               // URL stránky -> pohádky
    private val inFlight = ConcurrentHashMap<String, Deferred<CachedPage?>>()     // běžící stahování
    private val taleCache = ConcurrentHashMap<String, TaleData>()                 // URL pohádky -> kapitoly
    private val trackItems = ConcurrentHashMap<String, MediaItem>()               // trackId -> playItem
    private val trackToTale = ConcurrentHashMap<String, String>()                 // trackId -> URL pohádky

    companion object {
        const val ROOT_ID = "ROOT_DETI_ONLINE"
        private const val RESUME_ID = "RESUME_DETI"
        private const val PAGE_PREFIX = "PAGE_DETI|"      // PAGE_DETI|<index stránky od 0>
        private const val RESUME_ITEM_PREFIX = "RESUME::" // RESUME::<URL pohádky> (přehratelné)

        private const val EXTRA_TALE_ID = "deti_tale_id"
        private const val EXTRA_TALE_TITLE = "deti_tale_title"
        private const val EXTRA_TALE_ARTWORK = "deti_tale_artwork"
        private const val EXTRA_TRACK_INDEX = "deti_track_index"

        private const val TAB_RESUME = "Pokračovat"

        private const val GROUP_SIZE = 30 // pohádek na jednu stránku (záložku)
        private const val SEARCH_LIMIT = 30
        private const val LIST_TTL_MS = 30 * 60_000L
        private const val TALE_TTL_MS = 6 * 60 * 60_000L
        private const val SAVE_INTERVAL_MS = 15_000L
        private const val TAG = "DetiLibrary"

        // Android Auto "content style" (dlaždice vs. seznam, nadpisy skupin)
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val HINT_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val HINT_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val HINT_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        private const val STYLE_LIST = 1
        private const val STYLE_GRID = 2
    }

    // ---- Sledování přehrávání (historie) ----------------------------------------------------

    private var trackedPlayer: Player? = null
    private var tickerJob: Job? = null

    private val trackerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveProgressNow()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else {
                stopTicker()
                saveProgressNow()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onTaleFinished()
        }
    }

    private fun attachTracker(player: Player) {
        if (trackedPlayer === player) return
        try {
            trackedPlayer?.removeListener(trackerListener)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "removeListener selhalo")
        }
        trackedPlayer = player
        player.addListener(trackerListener)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                saveProgressNow()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun currentPlayer(): Player? = trackedPlayer ?: playerProvider?.invoke()

    /**
     * Uloží aktuální kapitolu a pozici do historie. Veřejné - volá ho i MusicService.
     * Bezpečné volat z libovolného vlákna (přehrávač se čte vždy na hlavním vlákně).
     */
    fun saveProgress() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            saveProgressNow()
        } else {
            scope.launch { saveProgressNow() }
        }
    }

    private fun saveProgressNow() {
        val player = currentPlayer() ?: return
        if (player.playbackState == Player.STATE_ENDED) return
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val taleId = extras.getString(EXTRA_TALE_ID) ?: return
        val index = extras.getInt(EXTRA_TRACK_INDEX, -1)
        if (index < 0) return
        try {
            history.upsert(
                DetiResumeEntry(
                    taleId = taleId,
                    title = extras.getString(EXTRA_TALE_TITLE).orEmpty(),
                    artworkUrl = extras.getString(EXTRA_TALE_ARTWORK)?.ifEmpty { null },
                    trackIndex = index,
                    trackTitle = item.mediaMetadata.title?.toString(),
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    updatedAt = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Uložení historie selhalo")
        }
    }

    /** Přehrála se poslední kapitola -> pohádka je hotová, vyhodíme ji z "Pokračovat". */
    private fun onTaleFinished() {
        stopTicker()
        val taleId = currentPlayer()?.currentMediaItem?.mediaMetadata?.extras?.getString(EXTRA_TALE_ID)
            ?: return
        history.remove(taleId)
    }

    // ---- Připojení ------------------------------------------------------------------------

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo
    ): MediaSession.ConnectionResult {
        // Detekce připojení Android Auto
        if (controller.connectionHints.getBoolean("androidx.media3.session.AUTO", false)) {
            onAutomotiveConnected?.invoke()
        }

        attachTracker(session.player)

        // DŮLEŽITÉ: SESSION_AND_LIBRARY, jinak Android Auto nesmí procházet knihovnu!
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(CommandToggleLike)
            .add(CommandToggleRepeatMode)
            .add(CommandToggleShuffle)
            .add(CommandToggleStartRadio)
            .build()

        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle
    ): ListenableFuture<SessionResult> {
        val action = customCommand.customAction
        when {
            action == CommandToggleLike.customAction || action == "ACTION_TOGGLE_LIKE" -> {
                toggleLike?.invoke()
            }
            action == CommandToggleStartRadio.customAction || action == "ACTION_START_RADIO" -> {
                toggleStartRadio?.invoke()
            }
            action == "ACTION_TOGGLE_LIBRARY" -> {
                toggleLibrary?.invoke()
            }
            action == CommandToggleRepeatMode.customAction -> {
                val player = session.player
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }
            action == CommandToggleShuffle.customAction -> {
                val player = session.player
                player.shuffleModeEnabled = !player.shuffleModeEnabled
            }
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    // ---- Procházení -----------------------------------------------------------------------

    /** Kořenový uzel pro rozhraní Android Auto */
    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // Výchozí vzhled stromu: složky, pohádky i rozposlouchané jako dlaždice s obrázkem.
        // (Kapitoly uvnitř pohádky přepisují vzhled na seznam, viz storyItem.)
        val rootExtras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(HINT_BROWSABLE, STYLE_GRID)
            putInt(HINT_PLAYABLE, STYLE_GRID)
        }
        val rootParams = LibraryParams.Builder().setExtras(rootExtras).build()
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), rootParams))
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future {
            val items: List<MediaItem> = try {
                when {
                    parentId == ROOT_ID -> rootChildren()
                    parentId == RESUME_ID -> history.getAll().map { resumeItem(it, null) }
                    parentId.startsWith(PAGE_PREFIX) -> pageChildren(parentId)
                    else -> loadTale(parentId)?.browseItems ?: emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "onGetChildren selhalo pro %s", parentId)
                emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items.paged(page, pageSize)), params)
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val story = findTale(mediaId)
        val item: MediaItem? = when {
            mediaId == ROOT_ID -> rootItem()
            mediaId == RESUME_ID -> resumeFolderItem()
            mediaId.startsWith(PAGE_PREFIX) ->
                mediaId.removePrefix(PAGE_PREFIX).toIntOrNull()?.let { pageItem(it) }
            trackItems.containsKey(mediaId) -> trackItems[mediaId]
            mediaId.startsWith(RESUME_ITEM_PREFIX) ->
                history.get(mediaId.removePrefix(RESUME_ITEM_PREFIX))?.let { resumeItem(it, null) }
            story != null -> storyItem(story, null)
            mediaId.startsWith("http://") || mediaId.startsWith("https://") ->
                resolvePlayable(MediaItem.Builder().setMediaId(mediaId).build())
            else -> null
        }
        return if (item != null) {
            Futures.immediateFuture(LibraryResult.ofItem(item, null))
        } else {
            Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }
    }

    // ---- Přehrávání -----------------------------------------------------------------------

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>
    ): ListenableFuture<MutableList<MediaItem>> {
        return Futures.immediateFuture(mediaItems.map { resolvePlayable(it) }.toMutableList())
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        return scope.future {
            val first = mediaItems.firstOrNull()

            // 1) "Pokračovat" -> celá pohádka od uložené kapitoly a pozice
            if (mediaItems.size == 1 && first != null && first.mediaId.startsWith(RESUME_ITEM_PREFIX)) {
                val resumed = try {
                    resolveResume(first.mediaId.removePrefix(RESUME_ITEM_PREFIX))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Pokračování selhalo")
                    null
                }
                if (resumed != null) return@future resumed
            }

            val resolved = mediaItems.map { resolvePlayable(it) }

            // 2) Jedna kapitola -> do fronty celá pohádka od této kapitoly
            if (resolved.size == 1) {
                val id = resolved[0].mediaId
                val queue = trackToTale[id]?.let { taleCache[it]?.playItems }
                val index = queue?.indexOfFirst { it.mediaId == id } ?: -1
                if (queue != null && index >= 0) {
                    return@future MediaSession.MediaItemsWithStartPosition(queue, index, startPositionMs)
                }
            }
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
        }
    }

    private suspend fun resolveResume(taleId: String): MediaSession.MediaItemsWithStartPosition? {
        val entry = history.get(taleId) ?: return null
        val tale = loadTale(taleId, entry.title, entry.artworkUrl) ?: return null
        val items = tale.playItems
        if (items.isEmpty()) return null

        var index = entry.trackIndex.coerceIn(0, items.size - 1)
        // Kdyby se seznam kapitol na webu změnil, zkusíme kapitolu najít podle názvu
        val savedTitle = entry.trackTitle
        if (savedTitle != null && items[index].mediaMetadata.title?.toString() != savedTitle) {
            val byTitle = items.indexOfFirst { it.mediaMetadata.title?.toString() == savedTitle }
            if (byTitle >= 0) index = byTitle
        }
        return MediaSession.MediaItemsWithStartPosition(items, index, entry.positionMs)
    }

    // ---- Vyhledávání ----------------------------------------------------------------------

    /** Obsluha vyhledávání (hlasem i textem) v Android Auto */
    override fun onSearch(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<Void>> {
        scope.launch {
            val count = try {
                searchCatalog(query).size
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "onSearch selhalo")
                0
            }
            session.notifySearchResultChanged(browser, query, count, params)
        }
        return Futures.immediateFuture(LibraryResult.ofVoid())
    }

    override fun onGetSearchResult(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return scope.future {
            val results: List<MediaItem> = try {
                searchCatalog(query).map { storyItem(it, null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "onGetSearchResult selhalo")
                emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(results.paged(page, pageSize)), params)
        }
    }

    private suspend fun searchCatalog(query: String): List<DetiTaleItem> {
        if (query.isBlank()) return emptyList()
        return topPage()?.items.orEmpty()
            .filter { it.title.contains(query, ignoreCase = true) }
            .take(SEARCH_LIMIT)
    }

    // ---- Načítání dat ---------------------------------------------------------------------

    private suspend fun loadPage(url: String): CachedPage? {
        val cached = listCache[url]
        if (cached != null && SystemClock.elapsedRealtime() - cached.ts < LIST_TTL_MS) {
            return cached
        }
        // Souběžné požadavky na stejnou URL sdílejí jedno stahování.
        val job = inFlight.getOrPut(url) { scope.async { fetchPage(url) } }
        try {
            return job.await()
        } finally {
            if (job.isCompleted) inFlight.remove(url, job)
        }
    }

    private suspend fun fetchPage(url: String): CachedPage? {
        val page = detiParser.getCatalogPage(url)
        if (page.items.isNotEmpty()) {
            val fresh = CachedPage(page.items, page.ageLinks, SystemClock.elapsedRealtime())
            listCache[url] = fresh
            return fresh
        }
        return listCache[url] // web nedostupný -> raději stará data než nic
    }

    /** Úvodní stránka s "top pohádkami" (první dostupná z TOP_URLS). */
    private suspend fun topPage(): CachedPage? {
        for (url in DetiOnlineParser.TOP_URLS) {
            val page = loadPage(url)
            if (page != null && page.items.isNotEmpty()) return page
        }
        return null
    }

    private fun findTale(id: String): DetiTaleItem? {
        for (cached in listCache.values) {
            cached.items.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }

    private suspend fun loadTale(
        taleId: String,
        titleHint: String? = null,
        artworkHint: String? = null
    ): TaleData? {
        val cached = taleCache[taleId]
        if (cached != null && SystemClock.elapsedRealtime() - cached.ts < TALE_TTL_MS) return cached

        val tracks = detiParser.getTracks(taleId)
        if (tracks.isEmpty()) return cached

        val tale = findTale(taleId)
        val taleTitle = tale?.title ?: titleHint
        val artworkUrl = (tale?.artworkUrl ?: artworkHint)?.takeIf { it.isNotBlank() }

        val browse = tracks.mapIndexed { i, t -> buildTrackItem(t, taleId, taleTitle, artworkUrl, i, false) }
        val play = tracks.mapIndexed { i, t -> buildTrackItem(t, taleId, taleTitle, artworkUrl, i, true) }
        play.forEach {
            trackItems[it.mediaId] = it
            trackToTale[it.mediaId] = taleId
        }
        return TaleData(browse, play, SystemClock.elapsedRealtime()).also { taleCache[taleId] = it }
    }

    // ---- Uzly stromu ----------------------------------------------------------------------

    /** Kořen = záložky nahoře: [Pokračovat] (jen když existuje historie) + stránky 1–30, 31–60, ... */
    private suspend fun rootChildren(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        if (history.getAll().isNotEmpty()) items.add(resumeFolderItem())

        val total = topPage()?.items?.size ?: 0
        val pages = (total + GROUP_SIZE - 1) / GROUP_SIZE
        for (k in 0 until pages) items.add(pageItem(k, total))
        return items
    }

    /** Obsah jedné stránky: pohádky jako dlaždice; po klepnutí se otevřou kapitoly (loadTale). */
    private suspend fun pageChildren(pageId: String): List<MediaItem> {
        val index = pageId.removePrefix(PAGE_PREFIX).toIntOrNull() ?: return emptyList()
        return topPage()?.items.orEmpty()
            .drop(index * GROUP_SIZE)
            .take(GROUP_SIZE)
            .map { storyItem(it, null) }
    }

    /** Záložka stránky, např. "1–30". Děti (pohádky) se zobrazí jako dlaždice. */
    private fun pageItem(index: Int, total: Int = Int.MAX_VALUE): MediaItem {
        val from = index * GROUP_SIZE + 1
        val to = minOf(from + GROUP_SIZE - 1, total)
        val title = if (from >= to) "$from" else "$from–$to"
        return folderItem(id = "$PAGE_PREFIX$index", title = title, childStyle = STYLE_GRID)
    }

    /** Záložka "Pokračovat" - rozposlouchané pohádky taky jako dlaždice. */
    private fun resumeFolderItem(): MediaItem =
        folderItem(id = RESUME_ID, title = TAB_RESUME, childStyle = STYLE_GRID)

    // ---- Stavba MediaItemů ----------------------------------------------------------------

    private fun groupExtras(group: String?): Bundle? =
        if (group == null) null else Bundle().apply { putString(HINT_GROUP_TITLE, group) }

    /** childStyle = jak Android Auto vykreslí DĚTI této složky (dlaždice / seznam). */
    private fun folderExtras(group: String?, childStyle: Int?): Bundle? {
        if (group == null && childStyle == null) return null
        return Bundle().apply {
            if (group != null) putString(HINT_GROUP_TITLE, group)
            if (childStyle != null) {
                putInt(HINT_BROWSABLE, childStyle)
                putInt(HINT_PLAYABLE, childStyle)
            }
        }
    }

    private fun rootItem(): MediaItem = folderItem(ROOT_ID, "Pohádky Děti Online")

    private fun folderItem(
        id: String,
        title: String,
        artworkUrl: String? = null,
        artworkData: ByteArray? = null,
        group: String? = null,
        subtitle: String? = null,
        childStyle: Int? = null
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setArtworkUri(artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            .setExtras(folderExtras(group, childStyle))
            .setIsBrowsable(true)
            .setIsPlayable(false)
        if (artworkData != null) {
            metadata.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata.build())
            .build()
    }

    private fun storyItem(story: DetiTaleItem, group: String?): MediaItem {
        val extras = Bundle().apply {
            putInt(HINT_PLAYABLE, STYLE_LIST) // kapitoly uvnitř pohádky jako seznam
            if (group != null) putString(HINT_GROUP_TITLE, group)
        }
        return MediaItem.Builder()
            .setMediaId(story.id) // URL detailu pohádky
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(story.title)
                    .setSubtitle(formatDuration(story.durationMin)) // délka jako na webu
                    .setArtworkUri(story.artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .setExtras(extras)
                    .setIsBrowsable(true) // Lze rozkliknout na kapitoly
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    /** Položka v "Pokračovat": přehratelná, po klepnutí naváže tam, kde se skončilo. */
    private fun resumeItem(entry: DetiResumeEntry, group: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId("$RESUME_ITEM_PREFIX${entry.taleId}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title.ifEmpty { "Pohádka" })
                    .setSubtitle("Kapitola ${entry.trackIndex + 1} · ${formatTime(entry.positionMs)}")
                    .setArtworkUri(entry.artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .setExtras(groupExtras(group))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private fun buildTrackItem(
        track: DetiTaleItem,
        taleId: String,
        taleTitle: String?,
        taleArtworkUrl: String?,
        index: Int,
        withArtwork: Boolean
    ): MediaItem {
        val url = track.mediaUri ?: track.id
        val extras = Bundle().apply {
            putString(EXTRA_TALE_ID, taleId)
            putString(EXTRA_TALE_TITLE, taleTitle.orEmpty())
            putString(EXTRA_TALE_ARTWORK, taleArtworkUrl.orEmpty())
            putInt(EXTRA_TRACK_INDEX, index)
        }
        val artwork = if (withArtwork) taleArtworkUrl?.let { Uri.parse(it) } else null
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(url)
            // MusicService: val mediaId = dataSpec.key ?: error("No media id") -> klíč musí být vyplněný
            .setCustomCacheKey(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist("Děti Online")
                    .setAlbumTitle(taleTitle)
                    .setArtworkUri(artwork)
                    .setExtras(extras)
                    .setIsBrowsable(false)
                    .setIsPlayable(true) // Spustí přehrávání v autě
                    .build()
            )
            .build()
    }

    /** Z položky, kterou pošle Android Auto (často jen mediaId), udělá přehratelnou položku. */
    private fun resolvePlayable(item: MediaItem): MediaItem {
        trackItems[item.mediaId]?.let { return it }
        val id = item.mediaId
        if (!id.startsWith("http://") && !id.startsWith("https://")) return item
        return item.buildUpon()
            .setUri(id)
            .setCustomCacheKey(id)
            .build()
    }

    // ---- Pomocné --------------------------------------------------------------------------

    private fun formatDuration(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "$h h $m min"
            h > 0 -> "$h h"
            else -> "$m min"
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun <T> List<T>.paged(page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE && page == 0) return this
        val from = (page.toLong() * pageSize).coerceIn(0L, size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
        return subList(from, to)
    }
}

package echo.music.iad1tya.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Auto / Media3 library callback pro pohádky z deti-online.com.
 *
 * Co je opraveno oproti původní verzi:
 *  1. onConnect: používá DEFAULT_SESSION_AND_LIBRARY_COMMANDS (bez knihovních příkazů
 *     Android Auto nemohl procházet strom -> "doesn't seem to be working").
 *  2. Kořen se dělí do skupin po GROUP_SIZE pohádkách (206 položek najednou + artwork = timeout).
 *  3. Katalog i kapitoly se cachují (netahá se web při každém kliknutí).
 *  4. onAddMediaItems / onSetMediaItems: doplní URI + customCacheKey (MusicService jinak padá na
 *     "No media id") a při spuštění kapitoly dá do fronty všechny kapitoly dané pohádky.
 *  5. onCustomCommand: porovnává s konstantami z MediaSessionConstants, funguje repeat i shuffle.
 *  6. onSearch: hlásí skutečný počet výsledků.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class MediaLibrarySessionCallback @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaLibraryService.MediaLibrarySession.Callback {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val detiParser = DetiOnlineParser()

    // Callbacky pro vlastní tlačítka v ovládání přehrávače
    var toggleLike: (() -> Unit)? = null
    var toggleStartRadio: (() -> Unit)? = null
    var toggleLibrary: (() -> Unit)? = null
    var onAutomotiveConnected: (() -> Unit)? = null

    // ---- Cache ----------------------------------------------------------------------------

    @Volatile private var catalogCache: List<DetiTaleItem> = emptyList()
    @Volatile private var catalogCacheTs = 0L

    private class TaleData(
        val browseItems: List<MediaItem>, // do seznamu v autě (bez artworku -> rychlé)
        val playItems: List<MediaItem>    // do fronty přehrávače (s artworkem)
    )

    private val taleCache = ConcurrentHashMap<String, TaleData>()
    private val trackItems = ConcurrentHashMap<String, MediaItem>()   // trackId -> playItem
    private val trackToTale = ConcurrentHashMap<String, String>()     // trackId -> URL pohádky

    companion object {
        const val ROOT_ID = "ROOT_DETI_ONLINE"
        private const val GROUP_PREFIX = "GROUP_DETI_"
        private const val GROUP_SIZE = 30
        private const val SEARCH_LIMIT = 30
        private const val CATALOG_TTL_MS = 30 * 60_000L
        private const val TAG = "DetiLibrary"
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
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))
    }

    /**
     * - ROOT          -> skupiny pohádek (nebo rovnou pohádky, je-li jich málo)
     * - GROUP_DETI_n  -> pohádky z dané skupiny
     * - URL pohádky   -> kapitoly (přímé MP3)
     */
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
                    parentId.startsWith(GROUP_PREFIX) -> groupChildren(parentId)
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
        val story = catalogCache.firstOrNull { it.id == mediaId }
        val item: MediaItem? = when {
            mediaId == ROOT_ID -> rootItem()
            trackItems.containsKey(mediaId) -> trackItems[mediaId]
            story != null -> storyItem(story)
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
        val resolved = mediaItems.map { resolvePlayable(it) }

        // Android Auto pošle jednu kapitolu -> do fronty dáme celou pohádku od této kapitoly
        if (resolved.size == 1) {
            val id = resolved[0].mediaId
            val queue = trackToTale[id]?.let { taleCache[it]?.playItems }
            val index = queue?.indexOfFirst { it.mediaId == id } ?: -1
            if (queue != null && index >= 0) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(queue, index, startPositionMs)
                )
            }
        }
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
        )
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
                searchCatalog(query).map { storyItem(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "onGetSearchResult selhalo")
                emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(results.paged(page, pageSize)), params)
        }
    }

    // ---- Načítání dat ---------------------------------------------------------------------

    private suspend fun loadCatalog(): List<DetiTaleItem> {
        val cached = catalogCache
        if (cached.isNotEmpty() && SystemClock.elapsedRealtime() - catalogCacheTs < CATALOG_TTL_MS) {
            return cached
        }
        val fresh = detiParser.getCatalog()
        if (fresh.isNotEmpty()) {
            catalogCache = fresh
            catalogCacheTs = SystemClock.elapsedRealtime()
            return fresh
        }
        return cached // web nedostupný -> raději stará data než nic
    }

    private suspend fun searchCatalog(query: String): List<DetiTaleItem> {
        if (query.isBlank()) return emptyList()
        return loadCatalog()
            .filter { it.title.contains(query, ignoreCase = true) }
            .take(SEARCH_LIMIT)
    }

    private suspend fun rootChildren(): List<MediaItem> {
        val catalog = loadCatalog()
        if (catalog.size <= GROUP_SIZE) return catalog.map { storyItem(it) }
        return catalog.chunked(GROUP_SIZE).mapIndexed { index, chunk -> groupItem(index, chunk.size) }
    }

    private suspend fun groupChildren(groupId: String): List<MediaItem> {
        val index = groupId.removePrefix(GROUP_PREFIX).toIntOrNull() ?: return emptyList()
        return loadCatalog()
            .drop(index * GROUP_SIZE)
            .take(GROUP_SIZE)
            .map { storyItem(it) }
    }

    private suspend fun loadTale(taleId: String): TaleData? {
        taleCache[taleId]?.let { return it }

        val tracks = detiParser.getTracks(taleId)
        if (tracks.isEmpty()) return null

        val tale = catalogCache.firstOrNull { it.id == taleId }
        val artwork = tale?.artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        val browse = tracks.map { buildTrackItem(it, tale?.title, null) }
        val play = tracks.map { buildTrackItem(it, tale?.title, artwork) }
        play.forEach {
            trackItems[it.mediaId] = it
            trackToTale[it.mediaId] = taleId
        }

        return TaleData(browse, play).also { taleCache[taleId] = it }
    }

    // ---- Stavba MediaItemů ----------------------------------------------------------------

    private fun rootItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Pohádky Děti Online")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private fun groupItem(index: Int, count: Int): MediaItem {
        val from = index * GROUP_SIZE + 1
        val to = from + count - 1
        return MediaItem.Builder()
            .setMediaId("$GROUP_PREFIX$index")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Pohádky $from–$to")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun storyItem(story: DetiTaleItem): MediaItem =
        MediaItem.Builder()
            .setMediaId(story.id) // URL detailu pohádky
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(story.title)
                    .setIsBrowsable(true) // Lze rozkliknout na kapitoly
                    .setIsPlayable(false)
                    .setArtworkUri(story.artworkUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                    .build()
            )
            .build()

    private fun buildTrackItem(track: DetiTaleItem, taleTitle: String?, artwork: Uri?): MediaItem {
        val url = track.mediaUri ?: track.id
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

    private fun <T> List<T>.paged(page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE && page == 0) return this
        val from = (page.toLong() * pageSize).coerceIn(0L, size.toLong()).toInt()
        val to = (from.toLong() + pageSize).coerceAtMost(size.toLong()).toInt()
        return subList(from, to)
    }
}

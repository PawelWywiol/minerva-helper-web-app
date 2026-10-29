package io.github.pawelwywiol.minervaweb.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class CatalogService(
    private val sync: CatalogSync,
    private val scope: CoroutineScope,
) {
    enum class State { MISSING, DOWNLOADING, IMPORTING, READY, FAILED }

    data class Status(
        val state: State,
        val done: Long = 0,
        val total: Long = 0,
        val error: String? = null,
    )

    private val log = LoggerFactory.getLogger(CatalogService::class.java)
    private var job: Job? = null

    @Volatile
    var status: Status = Status(if (sync.isReady()) State.READY else State.MISSING)
        private set

    @Synchronized
    fun start(force: Boolean): Boolean {
        if (job?.isActive == true) return false
        status = Status(State.DOWNLOADING)
        job =
            scope.launch {
                try {
                    sync.run(force) { status = it.toStatus() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("catalog sync failed", e)
                    status = Status(State.FAILED, error = e.message ?: e::class.simpleName)
                }
            }
        return true
    }

    suspend fun hasUpdate(): Boolean = sync.hasUpdate()

    fun <T> read(block: (CatalogReader) -> T): T? =
        if (sync.isReady()) CatalogReader(sync.catalogFile).use(block) else null

    private fun CatalogSync.Stage.toStatus(): Status =
        when (this) {
            is CatalogSync.Stage.Downloading -> Status(State.DOWNLOADING, done, total)
            is CatalogSync.Stage.Importing -> Status(State.IMPORTING, done, total)
            CatalogSync.Stage.Ready -> Status(State.READY)
        }
}

package io.github.pawelwywiol.minervaweb.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

class CatalogSync(
    dataDir: Path,
    private val downloader: HashesDownloader,
) {
    sealed interface Stage {
        data class Downloading(
            val done: Long,
            val total: Long,
        ) : Stage

        data class Importing(
            val done: Long,
            val total: Long,
        ) : Stage

        data object Ready : Stage
    }

    val catalogFile: Path = dataDir.resolve("catalog.db")
    private val sourceFile: Path = dataDir.resolve("hashes.db")

    fun isReady(): Boolean =
        catalogFile.exists() && runCatching { CatalogReader(catalogFile).use { it.isComplete() } }.getOrDefault(false)

    suspend fun hasUpdate(): Boolean =
        withContext(Dispatchers.IO) {
            val built = runCatching { CatalogReader(catalogFile).use { it.sourceVersion() } }.getOrNull()
            val upstream = runCatching { downloader.version() }.getOrNull()
            built != null && upstream != null && upstream != built
        }

    suspend fun run(
        force: Boolean = false,
        onStage: suspend (Stage) -> Unit,
    ) {
        if (!force && isReady()) {
            onStage(Stage.Ready)
            return
        }
        val version = runCatching { downloader.version() }.getOrNull()
        downloader.download(sourceFile) { onStage(Stage.Downloading(it.downloaded, it.total)) }
        CatalogImporter().import(sourceFile, catalogFile, version) { onStage(Stage.Importing(it.imported, it.total)) }
        sourceFile.deleteIfExists()
        sourceFile.resolveSibling("${sourceFile.fileName}.etag").deleteIfExists()
        onStage(Stage.Ready)
    }
}

package mihon.domain.extensionrepo.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.NetworkMangaExtensionStore
import okio.BufferedSource
import okio.buffer
import okio.gzip
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

class ExtensionRepoService(
    networkHelper: NetworkHelper,
    private val json: Json,
    private val protoBuf: ProtoBuf,
) {
    val client = networkHelper.client

    private val lenientJson = Json(json) {
        isLenient = true
    }

    /**
     * Fetches repo metadata from repo.json, JSON store, or gzip-compressed protobuf file
     * that carries its metadata inline.
     */
    suspend fun fetchRepoDetails(
        repo: String,
    ): ExtensionRepo? {
        return withIOContext {
            try {
                val normalized = repo.trim().trimEnd('/')
                val targetUrl = if (normalized.endsWith(".json") || normalized.endsWith(".pb")) {
                    normalized
                } else {
                    "$normalized/repo.json"
                }
                digest(targetUrl)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to fetch repo details" }
                null
            }
        }
    }

    private suspend fun digest(indexUrl: String, depth: Int = 0): ExtensionRepo {
        require(depth < 5) { "Too many extension repository redirects" }
        val response = client.newCall(GET(indexUrl)).awaitSuccess()
        return response.body.source().decompressIfGzipped().use { source ->
            when (source.peek().readByte()) {
                // Metadata lives in a sibling repo.json
                0x5B.toByte() -> {
                    val repoJsonUrl = "${indexUrl.substringBeforeLast('/')}/repo.json"
                    val meta = with(lenientJson) {
                        client.newCall(GET(repoJsonUrl)).awaitSuccess().parseAs<ExtensionRepoMetaDto>()
                    }
                    meta.indexV2?.let { return digest(it, depth + 1) }
                    meta.toExtensionRepo(baseUrl = indexUrl)
                }
                // Legacy repo.json shape or the new JSON form
                0x7B.toByte() -> {
                    val meta = try {
                        lenientJson.decodeFromBufferedSource<ExtensionRepoMetaDto>(source.peek())
                    } catch (_: Exception) {
                        null
                    }
                    when {
                        meta?.indexV2 != null -> return digest(meta.indexV2, depth + 1)
                        meta?.meta != null -> {
                            val resolvedUrl = if (indexUrl.endsWith("/repo.json")) {
                                "${indexUrl.substringBeforeLast('/')}/index.min.json"
                            } else {
                                indexUrl
                            }
                            meta.toExtensionRepo(baseUrl = resolvedUrl)
                        }
                        else -> lenientJson.decodeFromBufferedSource<NetworkMangaExtensionStore>(source)
                            .toExtensionRepo(baseUrl = indexUrl)
                    }
                }
                // Gzip-compressed or raw protobuf store
                else -> protoBuf.decodeFromByteArray<NetworkMangaExtensionStore>(source.readByteArray())
                    .toExtensionRepo(baseUrl = indexUrl)
            }
        }
    }

    /**
     * Repos migrated to protobuf format point via index_v2 in repo.json.
     * Returns null for repos that haven't migrated or don't exist.
     */
    suspend fun fetchIndexUrl(
        repo: String,
    ): String? {
        return withIOContext {
            try {
                with(lenientJson) {
                    client.newCall(GET("$repo/repo.json"))
                        .awaitSuccess()
                        .parseAs<ExtensionRepoMetaDto>()
                        .indexV2
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to fetch repo index url" }
                null
            }
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzip = peek().use { peeked ->
            try {
                peeked.readShort().toInt() == 0x1f8b
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzip) gzip().buffer() else this
    }
}

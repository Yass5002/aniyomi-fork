package mihon.domain.extensionrepo.service

import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mihon.domain.extensionrepo.manga.interactor.CreateMangaExtensionRepo
import mihon.domain.extensionrepo.manga.interactor.UpdateMangaExtensionRepo
import mihon.domain.extensionrepo.manga.repository.MangaExtensionRepoRepository
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.model.NetworkMangaExtensionStore
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class ExtensionRepoServiceTest {

    private val baseJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val lenientJson = Json(baseJson) {
        isLenient = true
    }
    private val protoBuf = ProtoBuf

    private fun createService(
        handler: (String) -> Pair<Int, ByteArray>,
    ): ExtensionRepoService {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val (code, body) = handler(request.url.toString())
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message(if (code == 200) "OK" else "Error")
                        .body(body.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                },
            )
            .build()
        val networkHelper = mockk<NetworkHelper> {
            every { this@mockk.client } returns client
        }
        return ExtensionRepoService(networkHelper, baseJson, protoBuf)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    @Test
    fun `fetchRepoDetails decodes gzipped index pb store`() = runTest {
        val store = NetworkMangaExtensionStore(
            name = "Keiyoushi",
            badgeLabel = "KEI",
            signingKey = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
            contact = NetworkMangaExtensionStore.Contact(
                website = "https://keiyoushi.github.io",
                discord = "https://discord.gg/3FbCpdKbdY",
            ),
            extensionList = NetworkMangaExtensionStore.ExtensionList(
                extensions = listOf(
                    NetworkMangaExtensionStore.Extension(
                        name = "AchyManga",
                        packageName = "eu.kanade.tachiyomi.extension.all.achymanga",
                        resources = NetworkMangaExtensionStore.Resources(
                            apkUrl = "https://example.com/app.apk",
                            iconUrl = "https://example.com/icon.png",
                            jarUrl = "https://example.com/app.jar",
                        ),
                        extensionLib = "1.6",
                        versionCode = 106001L,
                        versionName = "1.6.1",
                        contentWarning = NetworkMangaExtensionStore.ContentWarning.MIXED,
                        sources = listOf(
                            NetworkMangaExtensionStore.Source(
                                id = 9124545289113350724L,
                                name = "AchyManga",
                                language = "es",
                                homeUrl = "https://achymanga.online",
                                mirrorUrls = listOf("https://mirror.example.com"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val encoded = gzip(protoBuf.encodeToByteArray(store))
        val url = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"
        val service = createService { reqUrl ->
            reqUrl shouldBe url
            200 to encoded
        }

        val repo = service.fetchRepoDetails(url)

        repo shouldBe ExtensionRepo(
            baseUrl = url,
            name = "Keiyoushi",
            shortName = "KEI",
            website = "https://keiyoushi.github.io",
            signingKeyFingerprint = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
        )
    }

    @Test
    fun `lenient json decodes live keiyoushi index json with quoted longs`() {
        val rawJson = """
            {
              "name": "Keiyoushi",
              "badgeLabel": "KEI",
              "signingKey": "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
              "contact": {
                "website": "https://keiyoushi.github.io",
                "discord": "https://discord.gg/3FbCpdKbdY"
              },
              "extensionList": {
                "extensions": [
                  {
                    "name": "AchyManga",
                    "packageName": "eu.kanade.tachiyomi.extension.all.achymanga",
                    "resources": {
                      "apkUrl": "https://example.com/app.apk",
                      "iconUrl": "https://example.com/icon.png",
                      "jarUrl": "https://example.com/app.jar"
                    },
                    "extensionLib": "1.6",
                    "versionCode": "106001",
                    "versionName": "1.6.1",
                    "contentWarning": "CONTENT_WARNING_MIXED",
                    "sources": [
                      {
                        "id": "9124545289113350724",
                        "name": "AchyManga",
                        "language": "es",
                        "homeUrl": "https://achymanga.online"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val decoded = lenientJson.decodeFromString(NetworkMangaExtensionStore.serializer(), rawJson)

        decoded.name shouldBe "Keiyoushi"
        val ext = decoded.extensionList!!.extensions.single()
        ext.versionCode shouldBe 106001L
        ext.contentWarning shouldBe NetworkMangaExtensionStore.ContentWarning.MIXED
        ext.sources shouldHaveSize 1
        ext.sources.single().id shouldBe 9124545289113350724L
    }

    @Test
    fun `fetchRepoDetails follows index_v2 redirect from index min json and bare repo url`() = runTest {
        val v2Url = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        val storePb = gzip(
            protoBuf.encodeToByteArray(
                NetworkMangaExtensionStore(
                    name = "Keiyoushi",
                    badgeLabel = "KEI",
                    signingKey = "fingerprint123",
                    contact = NetworkMangaExtensionStore.Contact(website = "https://keiyoushi.github.io"),
                ),
            ),
        )
        val repoJson = """
            {
              "index_v2": "$v2Url",
              "meta": {
                "name": "Keiyoushi",
                "website": "https://keiyoushi.github.io",
                "signingKeyFingerprint": "fingerprint123"
              }
            }
        """.trimIndent().toByteArray()

        val service = createService { reqUrl ->
            when (reqUrl) {
                "https://example.com/extensions/repo/index.min.json" -> 200 to "[]".toByteArray()
                "https://example.com/extensions/repo/repo.json" -> 200 to repoJson
                v2Url -> 200 to storePb
                else -> 404 to ByteArray(0)
            }
        }

        val fromMinJson = service.fetchRepoDetails("https://example.com/extensions/repo/index.min.json")
        val fromBareUrl = service.fetchRepoDetails("https://example.com/extensions/repo")

        fromMinJson shouldBe ExtensionRepo(
            baseUrl = v2Url,
            name = "Keiyoushi",
            shortName = "KEI",
            website = "https://keiyoushi.github.io",
            signingKeyFingerprint = "fingerprint123",
        )
        fromBareUrl shouldBe fromMinJson
    }

    @Test
    fun `CreateMangaExtensionRepo and UpdateMangaExtensionRepo handle pb url and migration`() = runTest {
        val v2Url = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
        val pbBytes = gzip(
            protoBuf.encodeToByteArray(
                NetworkMangaExtensionStore(
                    name = "Keiyoushi",
                    badgeLabel = "KEI",
                    signingKey = "fp-1",
                    contact = NetworkMangaExtensionStore.Contact(website = "https://keiyoushi.github.io"),
                ),
            ),
        )
        val service = createService { reqUrl ->
            when (reqUrl) {
                v2Url -> 200 to pbBytes
                "https://old.example.com/repo.json" -> 200 to """
                    {
                      "index_v2": "$v2Url",
                      "meta": {
                        "name": "Keiyoushi",
                        "website": "https://keiyoushi.github.io",
                        "signingKeyFingerprint": "fp-1"
                      }
                    }
                """.trimIndent().toByteArray()
                else -> 404 to ByteArray(0)
            }
        }
        val repository = mockk<MangaExtensionRepoRepository>(relaxed = true)
        val createRepo = CreateMangaExtensionRepo(repository, service)

        createRepo.await("http://insecure.example.com/index.pb") shouldBe CreateMangaExtensionRepo.Result.InvalidUrl
        createRepo.await("https://example.com/not-index.txt") shouldBe CreateMangaExtensionRepo.Result.InvalidUrl
        createRepo.await(v2Url) shouldBe CreateMangaExtensionRepo.Result.Success

        coVerify {
            repository.insertRepo(
                v2Url,
                "Keiyoushi",
                "KEI",
                "https://keiyoushi.github.io",
                "fp-1",
            )
        }

        val oldRepo = ExtensionRepo(
            baseUrl = "https://old.example.com",
            name = "Old",
            shortName = null,
            website = "https://old.example.com",
            signingKeyFingerprint = "fp-1",
        )
        coEvery { repository.getAll() } returns listOf(oldRepo)

        val updateRepo = UpdateMangaExtensionRepo(repository, service)
        updateRepo.awaitAll()

        coVerify {
            repository.replaceRepo(
                ExtensionRepo(
                    baseUrl = v2Url,
                    name = "Keiyoushi",
                    shortName = "KEI",
                    website = "https://keiyoushi.github.io",
                    signingKeyFingerprint = "fp-1",
                ),
            )
        }
    }
}

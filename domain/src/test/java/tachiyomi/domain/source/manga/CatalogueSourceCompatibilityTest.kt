package tachiyomi.domain.source.manga

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import mihon.core.common.extensions.EMPTY
import org.junit.jupiter.api.Test
import rx.Observable
import tachiyomi.domain.source.manga.model.SourceNotInstalledException
import tachiyomi.domain.source.manga.model.StubMangaSource

class CatalogueSourceCompatibilityTest {

    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    private class Legacy15CatalogueSource : CatalogueSource {
        override val id: Long = 1L
        override val name: String = "Legacy 1.5"
        override val lang: String = "en"
        override val supportsLatest: Boolean = false

        var detailsFetched = 0
        var chaptersFetched = 0

        override fun getFilterList(): FilterList = FilterList()

        override fun fetchPopularManga(page: Int): Observable<MangasPage> =
            Observable.just(MangasPage(emptyList(), false))

        override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
            Observable.just(MangasPage(emptyList(), false))

        override fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
            Observable.just(MangasPage(emptyList(), false))

        override suspend fun getMangaDetails(manga: SManga): SManga {
            detailsFetched++
            return manga.copy().apply {
                title = "Updated Title"
                memo = buildJsonObject { put("k", JsonPrimitive("v")) }
            }
        }

        override suspend fun getChapterList(manga: SManga): List<SChapter> {
            chaptersFetched++
            return listOf(
                SChapter.create().apply {
                    url = "/ch1"
                    name = "Chapter 1"
                    memo = buildJsonObject { put("c", JsonPrimitive(1)) }
                },
            )
        }

        override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()
    }

    @Test
    fun `CatalogueSource getMangaUpdate bridges 1_5 suspend methods and copies memo`() = runTest {
        val source = Legacy15CatalogueSource()
        val initialManga = SManga.create().apply {
            url = "/manga/1"
            title = "Initial"
        }

        val partial = source.getMangaUpdate(
            manga = initialManga,
            chapters = emptyList(),
            fetchDetails = false,
            fetchChapters = true,
        )
        source.detailsFetched shouldBe 0
        source.chaptersFetched shouldBe 1
        partial.manga.title shouldBe "Initial"
        partial.chapters shouldHaveSize 1
        partial.chapters.single().memo shouldBe buildJsonObject { put("c", JsonPrimitive(1)) }

        val full = source.getMangaUpdate(
            manga = initialManga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true,
        )
        source.detailsFetched shouldBe 1
        source.chaptersFetched shouldBe 2
        full.manga.title shouldBe "Updated Title"
        full.manga.memo shouldBe buildJsonObject { put("k", JsonPrimitive("v")) }
    }

    @Test
    fun `SManga and SChapter copy preserve memo while create defaults to EMPTY`() {
        val original = SManga.create().apply {
            title = "Sample"
            memo = buildJsonObject { put("token", JsonPrimitive("abc")) }
        }
        val copied = original.copy()
        copied.memo shouldBe original.memo

        val chapterA = SChapter.create().apply {
            name = "Ch 1"
            memo = buildJsonObject { put("id", JsonPrimitive("xyz")) }
        }
        val chapterB = SChapter.create()
        chapterB.memo shouldBe JsonObject.EMPTY
        chapterB.copyFrom(chapterA)
        chapterB.memo shouldBe chapterA.memo
    }

    @Test
    fun `StubMangaSource throws SourceNotInstalledException on getMangaUpdate`() = runTest {
        val stub = StubMangaSource(id = 42L, lang = "en", name = "Missing")
        shouldThrow<SourceNotInstalledException> {
            stub.getMangaUpdate(
                manga = SManga.create(),
                chapters = emptyList(),
                fetchDetails = true,
                fetchChapters = true,
            )
        }
    }
}

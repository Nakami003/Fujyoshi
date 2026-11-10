package eu.kanade.tachiyomi.extension.zh.huaya

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

@Source
abstract class Huaya : KeiSource() {
    override val supportsLatest = true

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    override suspend fun getPopularManga(page: Int) = listing(page, "/tag/-1/-1/-1/14")

    override suspend fun getLatestUpdates(page: Int) = listing(page, "/tag/-1/-1/-1/12")

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?): FilterList = FilterList(
        Filter.Header("以下筛选适用于分类浏览，搜索书名时使用网站的搜索结果。"),
        Genre(),
        Audience(),
        Publication(),
        Sort(),
    )

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isNotBlank()) return listing(page, "/Search/SearchIndex", query.trim())
        val genre = filters.filterIsInstance<Genre>().firstOrNull()?.selected ?: -1
        val audience = filters.filterIsInstance<Audience>().firstOrNull()?.selected ?: -1
        val publication = filters.filterIsInstance<Publication>().firstOrNull()?.selected ?: -1
        val sort = filters.filterIsInstance<Sort>().firstOrNull()?.selected ?: 2
        return listing(page, "/tag/$genre/$audience/$publication/$sort")
    }

    private suspend fun listing(
        page: Int,
        path: String,
        query: String? = null,
    ): MangasPage {
        val url = "$baseUrl$path".toHttpUrl().newBuilder()
        query?.let { url.addQueryParameter("srchkey", it) }
        if (page > 1) url.addQueryParameter("page", page.toString())
        val document = client.get(url.build(), Fetch.DOCUMENT).asJsoup()
        val results =
            document
                .select("a[href^=/book/]:has(img)")
                .mapNotNull { link ->
                    val image = link.selectFirst("img") ?: return@mapNotNull null
                    val title = link.attr("title").ifBlank { image.attr("alt") }.trim()
                    if (title.isEmpty()) return@mapNotNull null
                    SManga.create().apply {
                        this.title = title
                        this.url = link.attr("href")
                        thumbnail_url = image.absUrl("src")
                    }
                }.distinctBy { it.url }
        val next =
            document.select("a.pagination-link").any { link ->
                link
                    .absUrl("href")
                    .toHttpUrlOrNull()
                    ?.queryParameter("page")
                    ?.toIntOrNull() == page + 1
            }
        return MangasPage(results, results.isNotEmpty() && next)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || !BOOK_PATH.matches(url.encodedPath)) return null
        val manga = SManga.create().apply { this.url = url.encodedPath }
        fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
        return manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = getMangaUrl(manga)
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        if (fetchDetails) {
            val info = document.selectFirst(".book-info-pc") ?: error("画涯未返回漫画详情")
            manga.title = info.selectFirst("h1.title")?.text() ?: manga.title
            manga.author =
                info
                    .selectFirst(".meta span")
                    ?.text()
                    ?.removePrefix("作者：")
                    ?.trim()
            manga.genre =
                info
                    .selectFirst(".meta p")
                    ?.text()
                    ?.split(Regex("\\s+"))
                    ?.joinToString(", ")
            manga.description = info.selectFirst(".desc")?.let { it.attr("title").ifBlank(it::text) }
            manga.thumbnail_url = info.selectFirst("img.cover")?.absUrl("src")
        }
        if (!fetchChapters) return SMangaUpdate(manga, chapters)

        val novelId = document.selectFirst("#jqHidNovelId")?.attr("value") ?: error("画涯未返回漫画编号")
        val ranges = document.select(".pc-tabs button").eachText()
        check(ranges.isNotEmpty()) { "画涯未返回章节目录" }
        val chapterHeaders = headers.newBuilder().set("X-Requested-With", "XMLHttpRequest").build()
        val entries =
            ranges.flatMap { range ->
                val body =
                    FormBody
                        .Builder()
                        .add("novelId", novelId)
                        .add("range", range)
                        .build()
                client
                    .post("$baseUrl/Details/GetChaptersData", chapterHeaders, Fetch.XHR.from(url), body)
                    .parseAs<List<ChapterEntry>>()
            }
        return SMangaUpdate(
            manga,
            entries.distinctBy { it.number }.sortedByDescending { it.number.toIntOrNull() }.map { entry ->
                SChapter.create().apply {
                    this.url = "/read/$novelId/${entry.number}"
                    name = entry.title
                    chapter_number = entry.number.toFloatOrNull() ?: -1f
                    date_upload = entry.created
                        ?.substringAfter("(")
                        ?.substringBefore(")")
                        ?.toLongOrNull() ?: 0L
                }
            },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = getChapterUrl(chapter)
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val urls =
            document.select(".comic-img img").mapNotNull { image ->
                image.absUrl("src").takeIf { it.startsWith("https://img.huaya.run/Images/", ignoreCase = true) }
            }
        check(urls.isNotEmpty()) { "此话无法在网页直接阅读，请在 WebView 查看画涯的登录或付费提示。" }
        return urls.mapIndexed { index, imageUrl -> Page(index, url, imageUrl) }
    }

    override fun getImageRequest(page: Page): Request = super
        .getImageRequest(page)
        .newBuilder()
        .fetch(Fetch.IMAGE.from(page.url))
        .build()

    @Serializable
    private class ChapterEntry(
        @SerialName("TitleNo") val number: String,
        @SerialName("TitleStr") val title: String,
        @SerialName("CreateTime") val created: String? = null,
    )

    private abstract class SiteFilter(
        title: String,
        private val choices: List<Pair<String, Int>>,
    ) : Filter.Select<String>(title, choices.map { it.first }.toTypedArray()) {
        val selected: Int get() = choices[state].second
    }

    private class Genre : SiteFilter("作品题材", GENRES)

    private class Audience : SiteFilter("作品类型", listOf("全部" to -1, "通用" to 3, "女生" to 4, "男生" to 5))

    private class Publication : SiteFilter("连载状态", listOf("全部" to -1, "连载" to 0, "完结" to 1))

    private class Sort : SiteFilter("排序方式", listOf("最新上架" to 2, "人气推荐" to 14, "最近更新" to 12))

    companion object {
        private val BOOK_PATH = Regex("/book/[0-9]+")
        private val GENRES =
            listOf(
                "全部" to -1,
                "纯爱" to 12,
                "言情" to 145,
                "ABO" to 141,
                "多女主" to 162,
                "单女主" to 161,
                "橘里橘气" to 128,
                "都市" to 37,
                "家庭" to 166,
                "西方" to 146,
                "灵异" to 9,
                "非人类" to 144,
                "合集" to 143,
                "短篇" to 142,
                "悬疑" to 6,
                "校园" to 35,
                "恐怖" to 7,
                "奇幻" to 129,
                "玄幻" to 34,
                "热血" to 33,
                "总裁" to 8,
                "异能" to 147,
                "穿越" to 21,
                "古风" to 31,
                "搞笑" to 32,
                "架空" to 164,
                "复仇" to 165,
                "黑白漫" to 167,
            )
    }
}

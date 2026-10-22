package top.app.market.data.remote.fdroid

import kotlinx.coroutines.test.runTest
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.preference.FDroidMirror
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FDroidIndexTest {
    // 字符串里故意放入括号、引号转义、反斜杠，验证边界识别不被字符串内容干扰
    private val sampleJson = """
        {"repo": {"timestamp": 1, "name": "F-Droid {x}", "mirrors": ["https://a/b", "https://c/d"]},
         "requests": {"install": [], "uninstall": []},
         "apps": [
           {"packageName": "org.example.notes", "icon": "org.example.notes.12.png",
            "suggestedVersionCode": "12", "lastUpdated": 200,
            "localized": {
              "en-US": {"name": "Notes {beta}", "summary": "A \"quick\" note taker", "description": "<b>Fast</b> notes\n<ul><li>one</li></ul>",
                        "whatsNew": "fixes ] and [", "phoneScreenshots": ["1.png", "2.png"]},
              "zh-CN": {"name": "笔记", "summary": "快速记事", "phoneScreenshots": []}
            }},
           {"packageName": "org.example.notepad", "suggestedVersionCode": "5", "lastUpdated": 100,
            "localized": {"en-US": {"name": "Pad", "summary": "plain text notes"}}},
           {"packageName": "org.example.nolocale", "suggestedVersionCode": "1", "lastUpdated": 50},
           {"packageName": "org.example.fr", "suggestedVersionCode": "1", "lastUpdated": 10,
            "localized": {"fr": {"name": "Carnet", "summary": "notes en français"}}}
         ],
         "packages": {
           "org.example.notes": [
             {"apkName": "org.example.notes_13.apk", "versionCode": 13, "versionName": "1.3", "size": 3000},
             {"apkName": "org.example.notes_12.apk", "versionCode": 12, "versionName": "1.2", "size": 2000}
           ],
           "org.example.notepad": [{"apkName": "org.example.notepad_5.apk", "versionCode": 5, "versionName": "0.5", "size": 500}],
           "org.unknown.pkg": [{"apkName": "x.apk", "versionCode": 1, "versionName": "1", "size": 1}]
         }}
    """.trimIndent()

    private fun parse() = FDroidIndexParser.parse(StringReader(sampleJson))

    @Test
    fun parserPicksSuggestedVersionAndLocalizedText() {
        val index = parse()
        assertEquals(4, index.apps.size)

        val notes = assertNotNull(index.find("org.example.notes"))
        // 推荐版本 12 优先于列表第一条（13）
        assertEquals("1.2", notes.versionName)
        assertEquals(12L, notes.versionCode)
        assertEquals("org.example.notes_12.apk", notes.apkName)
        assertEquals(2000L, notes.apkSize)
        assertEquals("Notes {beta}", notes.en?.name)
        assertEquals("A \"quick\" note taker", notes.en?.summary)
        assertEquals("fixes ] and [", notes.en?.whatsNew)
        assertEquals(listOf("1.png", "2.png"), notes.en?.screenshots)
        assertEquals("笔记", notes.zh?.name)
        assertEquals("笔记", notes.displayName)
    }

    @Test
    fun parserFallsBackForMissingLocalizationAndVersions() {
        val index = parse()
        // 没有任何语言时用包名；其它语言兜底给英文槽
        assertEquals("org.example.nolocale", index.find("org.example.nolocale")?.displayName)
        assertEquals("Carnet", index.find("org.example.fr")?.displayName)
        // packages 里出现索引外的包不影响结果
        assertNull(index.find("org.unknown.pkg"))
        // 无版本信息的应用保留，apkName 为空
        assertEquals("", index.find("org.example.nolocale")?.apkName)
    }

    @Test
    fun cacheEncodingRoundTrips() {
        val original = parse().apps
        val restored = original.map { FDroidIndexParser.decode(FDroidIndexParser.encode(it)) }
        assertEquals(original, restored)
        // 每条记录必须是单行，落盘格式按行读取
        assertTrue(original.all { !FDroidIndexParser.encode(it).contains('\n') })
    }

    @Test
    fun malformedIndexFailsInsteadOfReturningPartialData() {
        assertFailsWith<IllegalStateException> {
            FDroidIndexParser.parse(StringReader("""{"apps": [{"packageName": "a""""))
        }
    }

    private val source = object : FDroidIndexSource {
        override suspend fun load(mirror: FDroidMirror) = parse()
    }
    private val api = FDroidApi(source)

    @Test
    fun searchRanksNameMatchesAheadOfSummaryMatches() = runTest {
        val page = api.search("note")
        // 名称前缀命中 > 包名命中 > 仅简介命中
        assertEquals(
            listOf("org.example.notes", "org.example.notepad", "org.example.fr"),
            page.items.map { it.packageName },
        )
    }

    @Test
    fun searchRequiresAllTokensAndHandlesBlankKeyword() = runTest {
        assertEquals(listOf("org.example.notes"), api.search("note 快速").items.map { it.packageName })
        assertTrue(api.search("   ").items.isEmpty())
        assertTrue(api.search("zzz-not-found").items.isEmpty())
    }

    @Test
    fun searchPaginates() = runTest {
        val manyApps = (1..45).map { i ->
            FDroidIndexApp(
                packageName = "org.bulk.app$i", icon = "", versionName = "", versionCode = 0,
                apkName = "", apkSize = 0, lastUpdated = i.toLong(), zh = null, en = null,
            )
        }
        val bulk = FDroidApi(object : FDroidIndexSource {
            override suspend fun load(mirror: FDroidMirror) = FDroidIndex(manyApps)
        })
        val first = bulk.search("bulk", page = 0)
        val last = bulk.search("bulk", page = 2)
        assertEquals(20, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(5, last.items.size)
        assertTrue(!last.hasMore)
        // 同等匹配度时较新更新的在前
        assertEquals("org.bulk.app45", first.items.first().packageName)
    }

    @Test
    fun searchResultsUseMirrorUrlsAndKnownSize() = runTest {
        val item = api.search("notes", mirror = FDroidMirror.TUNA).items.first { it.packageName == "org.example.notes" }
        assertEquals("${FDroidMirror.TUNA.repoBaseUrl}/icons-640/org.example.notes.12.png", item.icon)
        assertEquals(2000L, item.apkSize)
    }

    @Test
    fun detailBuildsScreenshotUrlsAndPlainTextDescription() = runTest {
        val detail = api.appDetail("org.example.notes", FDroidMirror.NJU)
        // 中文无截图，回落到英文截图
        assertEquals(
            listOf(
                "${FDroidMirror.NJU.repoBaseUrl}/org.example.notes/en-US/phoneScreenshots/1.png",
                "${FDroidMirror.NJU.repoBaseUrl}/org.example.notes/en-US/phoneScreenshots/2.png",
            ),
            detail.screenshots.map { it.url },
        )
        // 文案逐字段中文优先：简介取中文，中文没有的描述回落英文，且 HTML 标签被去掉
        assertEquals("快速记事", detail.brief)
        assertEquals("Fast notes\n• one", detail.introduction)
        assertEquals("fixes ] and [", detail.changeLog)
        assertFailsWith<MarketException> { api.appDetail("org.missing") }
    }

    @Test
    fun downloadMetaPointsAtMirrorApk() = runTest {
        val app = api.search("notes").items.first { it.packageName == "org.example.notes" }
        val meta = api.downloadMeta(app, FDroidMirror.TUNA)
        assertEquals("${FDroidMirror.TUNA.repoBaseUrl}/org.example.notes_12.apk", meta.url)
        assertEquals(2000L, meta.size)
        assertEquals(12L, meta.versionCode)

        // 没有版本信息（无 apk）的应用不能生成下载地址
        val noApk = api.search("nolocale").items.single()
        assertFailsWith<MarketException> { api.downloadMeta(noApk) }
    }
}

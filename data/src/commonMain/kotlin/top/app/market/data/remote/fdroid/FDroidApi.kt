package top.app.market.data.remote.fdroid

import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.ScreenshotOrientation
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.preference.FDroidMirror
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess

internal data class FDroidApiConfig(
    val searchBaseUrl: String = "https://search.f-droid.org",
    val packageBaseUrl: String = "https://f-droid.org/en/packages",
)

private const val DefaultPageLimit = 20
private const val UserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private val HtmlTag = Regex("<[^>]+>")
private val SearchItemPattern = Regex(
    """<a class="package-header" href="https://f-droid\.org/en/packages/([^"/]+)">\s*<img class="package-icon" src="([^"]+)"\s*/>\s*<div class="package-info">\s*<h4 class="package-name">\s*([\s\S]*?)\s*</h4>\s*<div class="package-desc">\s*<span class="package-summary">([\s\S]*?)</span>""",
    RegexOption.MULTILINE
)
private val PackageTitlePattern = Regex("""<h3 class="package-name">\s*([\s\S]*?)\s*</h3>""")
private val PackageIconPattern = Regex("""<img class="package-icon" src="([^"]+)"[^>]*alt="package icon"""")
private val PackageSummaryPattern = Regex("""<div class="package-summary">\s*([\s\S]*?)\s*</div>""")
private val PackageDescPattern = Regex("""<div class="package-description"[^>]*>([\s\S]*?)</div>""")
private val PackageWhatsNewPattern = Regex("""<div class="package-whats-new"[^>]*>[\s\S]*?<div dir="auto">\s*([\s\S]*?)\s*</div>""")
private val ScreenshotPattern = Regex("""<li class="js_slide screenshot"><img src="([^"]+)"""")
private val PackageVersionHeaderPattern = Regex(
    """<b>Version\s+([^<]+)</b>\s*\((\d+)\)[\s\S]*?<p class="package-version-download">\s*<b>\s*<a href="([^"]+)"""",
    RegexOption.MULTILINE
)
private val DownloadSizePattern = Regex("""</a>\s*</b>\s*([0-9.]+\s*[KMGT]?i?B)""", RegexOption.IGNORE_CASE)

internal class FDroidApi(
    private val client: HttpClient,
    private val config: FDroidApiConfig = FDroidApiConfig(),
) {
    /**
     * 搜索 F-Droid 开源应用。
     */
    suspend fun search(keyword: String, page: Int = 0, mirror: FDroidMirror = FDroidMirror.Default): SearchPage {
        val query = keyword.trim()
        if (query.isBlank()) return SearchPage(emptyList(), hasMore = false)

        val pageNumber = page + 1 // F-Droid 搜索页从 1 开始
        val url = "${config.searchBaseUrl}/?q=${query.encodeURLParameter()}&page=$pageNumber"

        val response = client.get(url) {
            header(HttpHeaders.UserAgent, UserAgent)
        }

        if (!response.status.isSuccess()) {
            throw MarketException("F-Droid 搜索请求失败：HTTP ${response.status.value}")
        }

        val html = response.bodyAsText()
        val apps = mutableListOf<MarketAppInfo>()

        SearchItemPattern.findAll(html).forEach { match ->
            val pkg = match.groupValues[1].trim()
            val rawIcon = match.groupValues[2].trim()
            val name = cleanHtml(match.groupValues[3])
            val summary = cleanHtml(match.groupValues[4])

            val iconUrl = replaceWithMirror(rawIcon, mirror)
            val appId = pkg.hashCode().toLong()

            apps.add(
                MarketAppInfo(
                    appId = appId,
                    packageName = pkg,
                    displayName = name.ifBlank { pkg },
                    publisherName = "",
                    versionName = "",
                    versionCode = 0L,
                    icon = iconUrl,
                    apkSize = 0L,
                    ratingScore = 0.0,
                    changeLog = summary,
                    openLink = "https://f-droid.org/en/packages/$pkg/",
                    source = AppSource.FDROID,
                )
            )
        }

        // 检查是否有下一页
        val nextPageParam = "page=${pageNumber + 1}"
        val hasMore = html.contains(nextPageParam) && apps.isNotEmpty()

        return SearchPage(items = apps, hasMore = hasMore)
    }

    /**
     * 获取应用详情（提取应用名、图标、描述、更新日志、截图、最新版本与下载链接）。
     */
    suspend fun appDetail(packageName: String, mirror: FDroidMirror = FDroidMirror.Default): AppDetail {
        val pkg = packageName.trim()
        val url = "${config.packageBaseUrl}/$pkg/"

        val response = client.get(url) {
            header(HttpHeaders.UserAgent, UserAgent)
        }

        if (!response.status.isSuccess()) {
            throw MarketException("F-Droid 详情请求失败：HTTP ${response.status.value}")
        }

        val html = response.bodyAsText()

        val displayName = PackageTitlePattern.find(html)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty().ifBlank { pkg }
        val rawIcon = PackageIconPattern.find(html)?.groupValues?.get(1).orEmpty()
        val iconUrl = replaceWithMirror(rawIcon, mirror)

        val summary = PackageSummaryPattern.find(html)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
        val description = PackageDescPattern.find(html)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
        val whatsNew = PackageWhatsNewPattern.find(html)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()

        // 截图
        val screenshots = ScreenshotPattern.findAll(html).map { match ->
            val screenshotUrl = replaceWithMirror(match.groupValues[1], mirror)
            AppScreenshot(url = screenshotUrl, orientation = ScreenshotOrientation.PORTRAIT)
        }.toList()

        // 解析版本列表，取最新的 suggested 或第一个版本
        val versionMatch = PackageVersionHeaderPattern.find(html)
        val versionName = versionMatch?.groupValues?.get(1)?.trim().orEmpty()
        val versionCode = versionMatch?.groupValues?.get(2)?.toLongOrNull() ?: 1L
        val rawDownloadUrl = versionMatch?.groupValues?.get(3)?.trim().orEmpty()

        val downloadSize = DownloadSizePattern.find(html)?.groupValues?.get(1)?.let(::parseDisplaySize) ?: 0L

        val appInfo = MarketAppInfo(
            appId = pkg.hashCode().toLong(),
            packageName = pkg,
            displayName = displayName,
            publisherName = "",
            versionName = versionName,
            versionCode = versionCode,
            icon = iconUrl,
            apkSize = downloadSize,
            ratingScore = 0.0,
            changeLog = whatsNew.ifBlank { summary },
            openLink = url,
            source = AppSource.FDROID,
        )

        return AppDetail(
            app = appInfo,
            brief = summary,
            introduction = description.ifBlank { summary },
            changeLog = whatsNew,
            category = "Open Source",
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = "",
            privacyUrl = "",
            screenshots = screenshots,
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    /**
     * 获取 APK 下载直链（映射到用户选择的国内镜像源）。
     */
    suspend fun downloadMeta(app: MarketAppInfo, mirror: FDroidMirror = FDroidMirror.Default): DownloadMeta {
        // 先检查 app 是否已经带了有效版本，如果没有或者没有下载文件名，通过 appDetail 获取
        val detailUrl = "${config.packageBaseUrl}/${app.packageName}/"
        val response = client.get(detailUrl) {
            header(HttpHeaders.UserAgent, UserAgent)
        }

        if (!response.status.isSuccess()) {
            throw MarketException("F-Droid 解析下载地址失败：HTTP ${response.status.value}")
        }

        val html = response.bodyAsText()
        val versionMatch = PackageVersionHeaderPattern.find(html)
            ?: throw MarketException("F-Droid 未找到该应用的有效版本下载")

        val versionName = versionMatch.groupValues[1].trim().ifBlank { app.versionName }
        val versionCode = versionMatch.groupValues[2].toLongOrNull() ?: app.versionCode.takeIf { it > 0 } ?: 1L
        val rawDownloadUrl = versionMatch.groupValues[3].trim()

        val downloadSize = DownloadSizePattern.find(html)?.groupValues?.get(1)?.let(::parseDisplaySize)
            ?: app.apkSize

        val finalDownloadUrl = replaceWithMirror(rawDownloadUrl, mirror)

        return fdroidDownloadMeta(
            appId = app.appId,
            packageName = app.packageName,
            displayName = app.displayName,
            versionName = versionName,
            versionCode = versionCode,
            icon = replaceWithMirror(app.icon, mirror),
            downloadUrl = finalDownloadUrl,
            fallbackSize = downloadSize,
        )
    }

    private fun cleanHtml(raw: String): String =
        HtmlTag.replace(raw, "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .trim()

    internal fun replaceWithMirror(rawUrl: String, mirror: FDroidMirror): String {
        if (rawUrl.isBlank()) return ""
        // 将任何官方 host 替换为用户配置的镜像源 baseUrl (e.g. https://mirror.nju.edu.cn/fdroid/repo)
        // 典型 F-Droid 资源格式:
        // https://f-droid.org/repo/xxx
        // https://ftp.fau.de/fdroid/repo/xxx
        // /repo/xxx
        val repoPrefix = Regex("""^(https?://[^/]+)?/repo/""")
        return if (repoPrefix.containsMatchIn(rawUrl)) {
            rawUrl.replace(repoPrefix, "${mirror.repoBaseUrl}/")
        } else if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "${mirror.repoBaseUrl}/${rawUrl.trimStart('/')}"
        }
    }

    private fun parseDisplaySize(text: String): Long {
        val trimmed = text.trim()
        val match = Regex("""([0-9.]+)\s*([KMGT]?i?B)""", RegexOption.IGNORE_CASE).find(trimmed) ?: return 0L
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].uppercase()
        val factor = when {
            unit.startsWith("G") -> 1024L * 1024L * 1024L
            unit.startsWith("M") -> 1024L * 1024L
            unit.startsWith("K") -> 1024L
            else -> 1L
        }
        return (value * factor).toLong()
    }
}

internal fun fdroidDownloadMeta(
    appId: Long,
    packageName: String,
    displayName: String,
    versionName: String,
    versionCode: Long,
    icon: String,
    downloadUrl: String,
    fallbackSize: Long,
): DownloadMeta {
    return DownloadMeta(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        versionName = versionName,
        versionCode = versionCode,
        url = downloadUrl,
        size = fallbackSize,
        parts = listOf(
            DownloadPart(
                name = "",
                type = "base",
                url = downloadUrl,
                size = fallbackSize,
                hash = "",
            )
        ),
        icon = icon,
    )
}

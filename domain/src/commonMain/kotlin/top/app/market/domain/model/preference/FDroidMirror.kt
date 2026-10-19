package top.app.market.domain.model.preference

enum class FDroidMirror(
    val token: String,
    val displayName: String,
    val repoBaseUrl: String,
) {
    NJU(
        token = "nju",
        displayName = "南京大学镜像源 (NJU)",
        repoBaseUrl = "https://mirror.nju.edu.cn/fdroid/repo",
    ),
    TUNA(
        token = "tuna",
        displayName = "清华大学开源软件镜像站 (TUNA)",
        repoBaseUrl = "https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo",
    );

    companion object {
        val Default: FDroidMirror = NJU

        fun fromToken(token: String?): FDroidMirror =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) } ?: Default
    }
}

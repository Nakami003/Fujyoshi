import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Huaya"
    versionCode = 3
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "画涯漫画"
        baseUrl = "https://www.huaya.run"
        lang = "zh"
    }
}

import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Hitomi"
    versionCode = 42
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        id = 690123758188633713
        lang = "all"
        baseUrl = "https://hitomi.la"
    }
}

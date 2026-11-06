package com.music2ktv.engine.template

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
enum class BackgroundType {
    @SerialName("blurred_cover")
    BLURRED_COVER,

    @SerialName("gradient")
    GRADIENT,

    @SerialName("image")
    IMAGE,

    @SerialName("solid")
    SOLID,
}

@Serializable
enum class LyricLayout {
    /** 经典 KTV 双行交替：上行左对齐、下行右对齐。 */
    @SerialName("classic_two_line")
    CLASSIC_TWO_LINE,

    /** 居中多行滚动，当前行高亮。 */
    @SerialName("center_scroll")
    CENTER_SCROLL,
}

@Serializable
enum class GlowLevel {
    @SerialName("none")
    NONE,

    @SerialName("medium")
    MEDIUM,

    @SerialName("strong")
    STRONG,
}

@Serializable
enum class SpectrumStyle {
    @SerialName("none")
    NONE,

    @SerialName("bars")
    BARS,

    @SerialName("ring")
    RING,
}

@Serializable
data class BackgroundSpec(
    val type: BackgroundType = BackgroundType.BLURRED_COVER,
    /** 渐变/纯色使用的颜色（#RRGGBB 或 #AARRGGBB），纯色取第一个。 */
    val colors: List<String> = listOf("#1C1046", "#0B0820"),
    /** type=image 时的图片地址（content:// 或数据源内路径）。 */
    val imageUri: String? = null,
    val particles: Boolean = true,
    /** 背景渐变缓慢流动。 */
    val animated: Boolean = true,
)

@Serializable
data class LyricStyle(
    val layout: LyricLayout = LyricLayout.CLASSIC_TWO_LINE,
    val fontSizeSp: Int = 64,
    /** 已唱部分渐变色，至少一个。 */
    val sungColors: List<String> = listOf("#3FE0FF", "#FF4FD8"),
    val unsungColor: String = "#FFFFFF",
    val stroke: Boolean = true,
    val strokeColor: String = "#B3000000",
    val glow: GlowLevel = GlowLevel.NONE,
    val showTranslation: Boolean = false,
)

/** KTV 播放页模板；以 JSON 持久化，可在局域网页面导入导出。 */
@Serializable
data class KtvTemplate(
    val id: String,
    val name: String,
    val version: Int = CURRENT_VERSION,
    val builtin: Boolean = false,
    val background: BackgroundSpec = BackgroundSpec(),
    val lyric: LyricStyle = LyricStyle(),
    val spectrum: SpectrumStyle = SpectrumStyle.NONE,
    val showNextSong: Boolean = true,
    val showQrCode: Boolean = true,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

object TemplateCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

    private val colorPattern = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    fun encode(template: KtvTemplate): String = json.encodeToString(KtvTemplate.serializer(), template)

    /** 解析并校验；格式错误时抛出 [IllegalArgumentException]，消息可直接展示给用户。 */
    fun decode(text: String): KtvTemplate {
        val t =
            try {
                json.decodeFromString(KtvTemplate.serializer(), text)
            } catch (e: SerializationException) {
                throw IllegalArgumentException("模板 JSON 格式错误：${e.message}", e)
            }
        validate(t)
        return t
    }

    fun validate(t: KtvTemplate) {
        require(t.id.isNotBlank()) { "模板 id 不能为空" }
        require(t.name.isNotBlank()) { "模板名称不能为空" }
        require(t.version <= KtvTemplate.CURRENT_VERSION) { "模板版本 ${t.version} 高于当前支持的 ${KtvTemplate.CURRENT_VERSION}" }
        require(t.lyric.fontSizeSp in 24..160) { "字号需在 24..160 之间：${t.lyric.fontSizeSp}" }
        require(t.lyric.sungColors.isNotEmpty()) { "已唱颜色至少需要一个" }
        val colors = t.background.colors + t.lyric.sungColors + t.lyric.unsungColor + t.lyric.strokeColor
        colors.forEach { require(colorPattern.matches(it)) { "颜色格式应为 #RRGGBB 或 #AARRGGBB：$it" } }
        if (t.background.type == BackgroundType.IMAGE) require(!t.background.imageUri.isNullOrBlank()) { "图片背景需要 imageUri" }
    }

    /** `#RRGGBB` / `#AARRGGBB` → ARGB。 */
    fun parseColor(hex: String): Long {
        require(colorPattern.matches(hex)) { "颜色格式应为 #RRGGBB 或 #AARRGGBB：$hex" }
        val v = hex.substring(1).toLong(16)
        return if (hex.length == 7) 0xFF000000L or v else v
    }
}

object BuiltinTemplates {
    val CLASSIC =
        KtvTemplate(
            id = "builtin.classic",
            name = "经典 KTV",
            builtin = true,
            background = BackgroundSpec(type = BackgroundType.BLURRED_COVER, particles = true),
            lyric = LyricStyle(layout = LyricLayout.CLASSIC_TWO_LINE, fontSizeSp = 72),
        )

    val SCROLLING =
        KtvTemplate(
            id = "builtin.scrolling",
            name = "滚动歌词",
            builtin = true,
            background = BackgroundSpec(type = BackgroundType.GRADIENT, colors = listOf("#2B1A5C", "#0B0820", "#12324A")),
            lyric =
                LyricStyle(
                    layout = LyricLayout.CENTER_SCROLL,
                    fontSizeSp = 56,
                    sungColors = listOf("#FFD84F", "#FF7A4F"),
                    unsungColor = "#B9B4D6",
                    stroke = false,
                    showTranslation = true,
                ),
            spectrum = SpectrumStyle.BARS,
        )

    val NEON =
        KtvTemplate(
            id = "builtin.neon",
            name = "霓虹频谱",
            builtin = true,
            background = BackgroundSpec(type = BackgroundType.SOLID, colors = listOf("#05020F"), particles = false),
            lyric =
                LyricStyle(
                    layout = LyricLayout.CENTER_SCROLL,
                    fontSizeSp = 64,
                    sungColors = listOf("#FF4FD8", "#3FE0FF"),
                    glow = GlowLevel.STRONG,
                    showTranslation = true,
                ),
            spectrum = SpectrumStyle.RING,
        )

    val ALL = listOf(CLASSIC, SCROLLING, NEON)
}

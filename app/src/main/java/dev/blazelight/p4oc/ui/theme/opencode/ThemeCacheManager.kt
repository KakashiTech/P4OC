package dev.blazelight.p4oc.ui.theme.opencode

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ThemeCacheManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val themeCache = mutableMapOf<String, OpenCodeTheme>()
    private val jsonCache = mutableMapOf<String, JsonObject>()
    private val cacheMutex = Mutex()
    private var preloadJob: Job? = null
    
    data class CacheKey(val themeName: String, val isDark: Boolean) {
        override fun toString(): String = "${themeName}_${isDark}"
    }
    
    suspend fun preloadThemes(context: Context) {
        if (preloadJob?.isActive == true) return
        
        preloadJob = CoroutineScope(Dispatchers.IO).launch {
            val availableThemes = getAvailableThemes(context)
            availableThemes.forEach { themeName ->
                try {
                    loadThemeJson(context, themeName)
                    loadTheme(context, themeName, true)
                    loadTheme(context, themeName, false)
                } catch (e: Exception) {
                    // Continue with other themes if one fails
                }
            }
        }
    }
    
    suspend fun loadTheme(context: Context, themeName: String, isDark: Boolean): OpenCodeTheme {
        val cacheKey = CacheKey(themeName, isDark).toString()
        
        cacheMutex.withLock {
            themeCache[cacheKey]?.let { return it }
        }
        
        val jsonString = loadThemeJson(context, themeName)
        val theme = parseThemeOptimized(jsonString, themeName, isDark)
        
        cacheMutex.withLock {
            themeCache[cacheKey] = theme
        }
        
        return theme
    }
    
    private suspend fun loadThemeJson(context: Context, themeName: String): String {
        jsonCache[themeName]?.let { return it.toString() }
        
        val jsonString = withContext(Dispatchers.IO) {
            try {
                context.assets.open("themes/$themeName.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                context.assets.open("themes/opencode.json").bufferedReader().use { it.readText() }
            }
        }
        
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        cacheMutex.withLock {
            jsonCache[themeName] = jsonObject
        }
        
        return jsonString
    }
    
    private fun parseThemeOptimized(jsonString: String, themeName: String, isDark: Boolean): OpenCodeTheme {
        val root = json.parseToJsonElement(jsonString).jsonObject
        val defs = root["defs"]?.jsonObject ?: JsonObject(emptyMap())
        val theme = root["theme"]?.jsonObject ?: error("Theme must have 'theme' object")
        
        val colorCache = mutableMapOf<String, Color>()
        
        fun resolveRef(ref: String): Color {
            return colorCache.getOrPut(ref) {
                if (ref.startsWith("#")) {
                    parseHexColorOptimized(ref)
                } else {
                    val resolved = defs[ref]?.jsonPrimitive?.content ?: return Color.Magenta
                    parseHexColorOptimized(resolved)
                }
            }
        }
        
        fun resolveColor(key: String): Color {
            return colorCache.getOrPut(key) {
                val value = theme[key] ?: return Color.Magenta
                
                when (value) {
                    is JsonPrimitive -> {
                        val content = value.content
                        resolveRef(content)
                    }
                    else -> {
                        try {
                            val pair = value.jsonObject
                            val modeKey = if (isDark) "dark" else "light"
                            val modeValue = pair[modeKey]?.jsonPrimitive?.content
                                ?: return Color.Magenta
                            resolveRef(modeValue)
                        } catch (e: Exception) {
                            Color.Magenta
                        }
                    }
                }
            }
        }
        
        return OpenCodeTheme(
            name = themeName,
            isDark = isDark,
            primary = resolveColor("primary"),
            secondary = resolveColor("secondary"),
            accent = resolveColor("accent"),
            text = resolveColor("text"),
            textMuted = resolveColor("textMuted"),
            background = resolveColor("background"),
            error = resolveColor("error"),
            warning = resolveColor("warning"),
            success = resolveColor("success"),
            info = resolveColor("info"),
            backgroundPanel = resolveColor("backgroundPanel"),
            backgroundElement = resolveColor("backgroundElement"),
            border = resolveColor("border"),
            borderActive = resolveColor("borderActive"),
            borderSubtle = resolveColor("borderSubtle"),
            diffAdded = resolveColor("diffAdded"),
            diffRemoved = resolveColor("diffRemoved"),
            diffContext = resolveColor("diffContext"),
            diffHunkHeader = resolveColor("diffHunkHeader"),
            diffHighlightAdded = resolveColor("diffHighlightAdded"),
            diffHighlightRemoved = resolveColor("diffHighlightRemoved"),
            diffAddedBg = resolveColor("diffAddedBg"),
            diffRemovedBg = resolveColor("diffRemovedBg"),
            diffContextBg = resolveColor("diffContextBg"),
            diffLineNumber = resolveColor("diffLineNumber"),
            diffAddedLineNumberBg = resolveColor("diffAddedLineNumberBg"),
            diffRemovedLineNumberBg = resolveColor("diffRemovedLineNumberBg"),
            markdownText = resolveColor("markdownText"),
            markdownHeading = resolveColor("markdownHeading"),
            markdownLink = resolveColor("markdownLink"),
            markdownLinkText = resolveColor("markdownLinkText"),
            markdownCode = resolveColor("markdownCode"),
            markdownBlockQuote = resolveColor("markdownBlockQuote"),
            markdownEmph = resolveColor("markdownEmph"),
            markdownStrong = resolveColor("markdownStrong"),
            markdownHorizontalRule = resolveColor("markdownHorizontalRule"),
            markdownListItem = resolveColor("markdownListItem"),
            markdownListEnumeration = resolveColor("markdownListEnumeration"),
            markdownImage = resolveColor("markdownImage"),
            markdownImageText = resolveColor("markdownImageText"),
            markdownCodeBlock = resolveColor("markdownCodeBlock"),
            syntaxComment = resolveColor("syntaxComment"),
            syntaxKeyword = resolveColor("syntaxKeyword"),
            syntaxFunction = resolveColor("syntaxFunction"),
            syntaxVariable = resolveColor("syntaxVariable"),
            syntaxString = resolveColor("syntaxString"),
            syntaxNumber = resolveColor("syntaxNumber"),
            syntaxType = resolveColor("syntaxType"),
            syntaxOperator = resolveColor("syntaxOperator"),
            syntaxPunctuation = resolveColor("syntaxPunctuation")
        )
    }
    
    private fun parseHexColorOptimized(hex: String): Color {
        val cleaned = hex.removePrefix("#")
        return when (cleaned.length) {
            3 -> {
                val r = (cleaned[0].toString() + cleaned[0]).toInt(16)
                val g = (cleaned[1].toString() + cleaned[1]).toInt(16)
                val b = (cleaned[2].toString() + cleaned[2]).toInt(16)
                Color(r, g, b)
            }
            6 -> {
                val colorInt = cleaned.toLong(16).toInt() or 0xFF000000.toInt()
                Color(colorInt)
            }
            8 -> {
                val colorLong = cleaned.toLong(16)
                Color(colorLong.toInt())
            }
            else -> Color.Magenta
        }
    }
    
    fun getAvailableThemes(context: Context): List<String> {
        return try {
            context.assets.list("themes")
                ?.filter { it.endsWith(".json") }
                ?.map { it.removeSuffix(".json") }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    suspend fun clearCache() {
        cacheMutex.withLock {
            themeCache.clear()
            jsonCache.clear()
        }
        preloadJob?.cancel()
        preloadJob = null
    }
    
    fun getCacheStats(): Pair<Int, Int> {
        return Pair(themeCache.size, jsonCache.size)
    }
}

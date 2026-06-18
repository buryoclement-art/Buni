package com.example.data

import android.content.Context
import android.webkit.URLUtil
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ImageRepository(private val context: Context, private val imageDao: ImageDao) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, ExtractedImage::class.java)
    private val imagesAdapter = moshi.adapter<List<ExtractedImage>>(listType)

    val allHistory: Flow<List<ExtractionHistory>> = imageDao.getAllHistory()
    val allSavedImages: Flow<List<SavedImage>> = imageDao.getAllSavedImages()

    fun isImageSaved(url: String): Flow<Boolean> = imageDao.isImageSaved(url)

    suspend fun saveImage(image: SavedImage) = withContext(Dispatchers.IO) {
        imageDao.insertSavedImage(image)
    }

    suspend fun unsaveImage(url: String) = withContext(Dispatchers.IO) {
        imageDao.deleteSavedImageByUrl(url)
    }

    suspend fun deleteHistoryById(id: Long) = withContext(Dispatchers.IO) {
        imageDao.deleteHistoryById(id)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        imageDao.clearHistory()
    }

    fun serializeImages(images: List<ExtractedImage>): String {
        return imagesAdapter.toJson(images)
    }

    fun deserializeImages(json: String): List<ExtractedImage> {
        return try {
            imagesAdapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Extracts images from a website URL using various strategies:
     * - Standard img tags (including lazily loaded content attributes)
     * - Inline CSS background-images
     * - SVG inline elements (saved as data URIs)
     * - Web icons (favicon, apple-touch-icon)
     * - Social/Meta media (OpenGraph og:image, twitter:image)
     */
    suspend fun extractImagesFromUrl(
        inputUrl: String,
        includeBackgrounds: Boolean,
        includeSvgs: Boolean,
        includeMetaAndIcons: Boolean
    ): Pair<String, List<ExtractedImage>> = withContext(Dispatchers.IO) {
        // Sanitize and normalize URL
        var targetUrl = inputUrl.trim()
        if (targetUrl.isEmpty()) {
            throw IllegalArgumentException("A URL não pode estar vazia")
        }

        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://$targetUrl"
        }

        if (!URLUtil.isValidUrl(targetUrl)) {
            throw IllegalArgumentException("Formato de URL inválido")
        }

        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Falha ao carregar o site: Código HTTP ${response.code}")
            }

            val html = response.body?.string() ?: ""
            val doc = Jsoup.parse(html, targetUrl)
            val title = doc.title().ifEmpty { "Sem título" }

            val images = mutableListOf<ExtractedImage>()
            val visitedUrls = mutableSetOf<String>()

            // 1. Standard <img> elements
            doc.select("img").forEach { element ->
                // Try multiple common attributes for images (support lazy loading scripts)
                val srcAttributes = listOf(
                    "src", "data-src", "data-original", "original-src", 
                    "srcset", "data-lazy-src", "data-srcset"
                )
                
                var resolvedUrl = ""
                for (attr in srcAttributes) {
                    val rawAttr = element.attr(attr).trim()
                    if (rawAttr.isNotEmpty()) {
                        // Handle srcset format (comma separated URLs)
                        val candidate = if (rawAttr.contains(",")) {
                            rawAttr.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.trim() ?: ""
                        } else {
                            rawAttr
                        }
                        
                        if (candidate.isNotEmpty()) {
                            resolvedUrl = element.absUrl(attr).ifEmpty { 
                                resolveRelativeUrl(targetUrl, candidate) 
                            }
                            if (resolvedUrl.isNotEmpty()) break
                        }
                    }
                }

                if (resolvedUrl.isNotEmpty() && !resolvedUrl.startsWith("data:") && visitedUrls.add(resolvedUrl)) {
                    val alt = element.attr("alt").trim()
                    val widthStr = element.attr("width").replace(Regex("[^0-9]"), "")
                    val heightStr = element.attr("height").replace(Regex("[^0-9]"), "")
                    val w = widthStr.toIntOrNull() ?: 0
                    val h = heightStr.toIntOrNull() ?: 0
                    
                    val name = extractFilenameFromUrl(resolvedUrl, alt)
                    val type = determineTypeFromUrl(resolvedUrl)

                    images.add(
                        ExtractedImage(
                            url = resolvedUrl,
                            name = name,
                            type = type,
                            width = w,
                            height = h,
                            altText = alt.ifEmpty { "Imagem do site" },
                            sourceSiteUrl = targetUrl
                        )
                    )
                }
            }

            // 2. Inline CSS background-images
            if (includeBackgrounds) {
                val cssUrlPattern = Pattern.compile("url\\(['\"]?(.*?)['\"]?\\)")
                doc.select("[style]").forEach { element ->
                    val style = element.attr("style")
                    if (style.contains("background")) {
                        val matcher = cssUrlPattern.matcher(style)
                        while (matcher.find()) {
                            val rawUrl = matcher.group(1)?.trim() ?: ""
                            if (rawUrl.isNotEmpty() && !rawUrl.startsWith("data:")) {
                                val resolvedUrl = resolveRelativeUrl(targetUrl, rawUrl)
                                if (resolvedUrl.isNotEmpty() && visitedUrls.add(resolvedUrl)) {
                                    val name = extractFilenameFromUrl(resolvedUrl)
                                    images.add(
                                        ExtractedImage(
                                            url = resolvedUrl,
                                            name = name,
                                            type = "Fundo (CSS)",
                                            sourceSiteUrl = targetUrl
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. SVG elements (extracted as inline string data URLs)
            if (includeSvgs) {
                doc.select("svg").forEachIndexed { index, svgElement ->
                    val svgHtml = svgElement.outerHtml().trim()
                    if (svgHtml.isNotEmpty()) {
                        // Make a custom data URL so coil or image renderers can render it, or we copy it
                        val dataUrl = "data:image/svg+xml;utf8," + android.net.Uri.encode(svgHtml)
                        if (visitedUrls.add(dataUrl)) {
                            images.add(
                                ExtractedImage(
                                    url = dataUrl,
                                    name = "elemento_svg_${index + 1}.svg",
                                    type = "SVG",
                                    width = svgElement.attr("width").toIntOrNull() ?: 64,
                                    height = svgElement.attr("height").toIntOrNull() ?: 64,
                                    altText = "Elemento SVG incorporado",
                                    sourceSiteUrl = targetUrl
                                )
                            )
                        }
                    }
                }
            }

            // 4. Meta og:image, twitter:image, apple-touch-icon, favicons
            if (includeMetaAndIcons) {
                // favicons and touch icons
                doc.select("link[rel*=icon], link[rel*=apple-touch-icon]").forEach { element ->
                    val rawHref = element.attr("href").trim()
                    if (rawHref.isNotEmpty()) {
                        val resolvedUrl = element.absUrl("href").ifEmpty { 
                            resolveRelativeUrl(targetUrl, rawHref) 
                        }
                        if (resolvedUrl.isNotEmpty() && visitedUrls.add(resolvedUrl)) {
                            images.add(
                                ExtractedImage(
                                    url = resolvedUrl,
                                    name = "ícone_" + extractFilenameFromUrl(resolvedUrl),
                                    type = "Ícone/Favicon",
                                    sourceSiteUrl = targetUrl
                                )
                            )
                        }
                    }
                }

                // Social metadata og:image
                doc.select("meta[property=og:image], meta[name=twitter:image], meta[property=og:image:secure_url]").forEach { element ->
                    val rawContent = element.attr("content").trim()
                    if (rawContent.isNotEmpty() && !rawContent.startsWith("data:")) {
                        val resolvedUrl = resolveRelativeUrl(targetUrl, rawContent)
                        if (resolvedUrl.isNotEmpty() && visitedUrls.add(resolvedUrl)) {
                            val name = "social_" + extractFilenameFromUrl(resolvedUrl)
                            images.add(
                                ExtractedImage(
                                    url = resolvedUrl,
                                    name = name,
                                    type = "Mídia Social",
                                    sourceSiteUrl = targetUrl
                                )
                            )
                        }
                    }
                }
            }

            // Save this to local history database so it persists
            val savedId = imageDao.insertHistory(
                ExtractionHistory(
                    siteUrl = targetUrl,
                    siteTitle = title,
                    imagesJson = serializeImages(images),
                    timestamp = System.currentTimeMillis()
                )
            )

            return@withContext Pair(title, images)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Resolves absolute details on size and mime-type for a single ExtractedImage in the background.
     * This avoids full image downloads but secures actual content size (HEAD requests).
     */
    suspend fun analyzeImageDetails(image: ExtractedImage): ExtractedImage = withContext(Dispatchers.IO) {
        if (image.url.startsWith("data:")) {
            // SVGs embedded inline
            val byteCount = image.url.length.toLong()
            return@withContext image.copy(sizeBytes = byteCount)
        }

        try {
            val request = Request.Builder()
                .url(image.url)
                .head() // Use lightweight HEAD request
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                val contentType = response.header("Content-Type") ?: ""
                
                var resolvedType = image.type
                if (contentType.isNotEmpty()) {
                    resolvedType = when {
                        contentType.contains("jpeg") || contentType.contains("jpg") -> "JPEG"
                        contentType.contains("png") -> "PNG"
                        contentType.contains("webp") -> "WEBP"
                        contentType.contains("gif") -> "GIF"
                        contentType.contains("svg") -> "SVG"
                        else -> contentType.substringAfter("image/").uppercase().substringBefore("+")
                    }
                }

                return@withContext image.copy(
                    sizeBytes = contentLength,
                    type = resolvedType
                )
            }
        } catch (e: Exception) {
            // Silently fail and return current details if head request fails
        }
        return@withContext image
    }

    private fun resolveRelativeUrl(baseUri: String, relativePath: String): String {
        return try {
            val base = URI(baseUri)
            val resolved = base.resolve(relativePath)
            resolved.toString()
        } catch (e: Exception) {
            if (relativePath.startsWith("//")) {
                "https:$relativePath"
            } else if (relativePath.startsWith("/")) {
                val proto = if (baseUri.startsWith("https")) "https" else "http"
                val host = URI(baseUri).host
                "$proto://$host$relativePath"
            } else {
                relativePath
            }
        }
    }

    private fun extractFilenameFromUrl(url: String, fallback: String = ""): String {
        try {
            val uri = URI(url)
            val path = uri.path
            if (path != null && path.contains("/")) {
                val filename = path.substring(path.lastIndexOf('/') + 1)
                if (filename.isNotEmpty()) {
                    // Check if there are query parameters we should clean
                    val cleaned = if (filename.contains("?")) filename.substringBefore("?") else filename
                    if (cleaned.contains(".") && cleaned.length > 3) {
                        return cleaned
                    }
                }
            }
        } catch (e: Exception) {
            // fallback
        }
        
        val extension = determineTypeFromUrl(url).lowercase()
        val safeFallback = if (fallback.isNotEmpty()) {
            fallback.lowercase()
                .replace(Regex("[^a-z0-9]"), "_")
                .take(30)
                .trim('_')
        } else {
            "imagem_${System.currentTimeMillis() % 100000}"
        }
        
        return "$safeFallback.$extension"
    }

    private fun determineTypeFromUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".png") -> "PNG"
            lower.contains(".jpg") || lower.contains(".jpeg") -> "JPEG"
            lower.contains(".webp") -> "WEBP"
            lower.contains(".gif") -> "GIF"
            lower.contains(".svg") -> "SVG"
            lower.contains(".bmp") -> "BMP"
            else -> "Imagem"
        }
    }
}

package com.example.ui

import android.app.Application
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

enum class SortBy {
    DEFAULT, NAME, SIZE, WIDTH, HEIGHT
}

class ImageExtractorViewModel(
    application: Application,
    private val repository: ImageRepository
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val geminiService = GeminiService(okHttpClient)

    // --- State Observables ---
    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _extractionError = MutableStateFlow<String?>(null)
    val extractionError: StateFlow<String?> = _extractionError.asStateFlow()

    private val _extractedTitle = MutableStateFlow("")
    val extractedTitle: StateFlow<String> = _extractedTitle.asStateFlow()

    private val _extractedImages = MutableStateFlow<List<ExtractedImage>>(emptyList())
    val extractedImages: StateFlow<List<ExtractedImage>> = _extractedImages.asStateFlow()

    // Filters and sorting states
    val searchQuery = MutableStateFlow("")
    val selectedTypeFilter = MutableStateFlow<String?>(null) // e.g. "PNG", "SVG", "JPEG", "WEBP", "Fundo (CSS)"
    val sortBy = MutableStateFlow(SortBy.DEFAULT)
    val isGridView = MutableStateFlow(true)

    // Selection states
    private val _selectedUrls = MutableStateFlow<Set<String>>(emptySet())
    val selectedUrls: StateFlow<Set<String>> = _selectedUrls.asStateFlow()

    // History and Pinned Favorites (reactive Room Flow states)
    val extractionHistoryList: StateFlow<List<ExtractionHistory>> = repository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedImagesList: StateFlow<List<SavedImage>> = repository.allSavedImages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Configuration strategies
    val includeBackgrounds = MutableStateFlow(true)
    val includeSvgs = MutableStateFlow(true)
    val includeMetaAndIcons = MutableStateFlow(true)

    // Gemini states
    private val _analysisMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val analysisMap: StateFlow<Map<String, String>> = _analysisMap.asStateFlow()

    private val _analysisLoadingMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val analysisLoadingMap: StateFlow<Map<String, Boolean>> = _analysisLoadingMap.asStateFlow()

    // Single Notification text trigger
    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    // Active Website URL input
    val inputUrl = MutableStateFlow("")

    // Filtered & Sorted Images result
    val filteredImages: StateFlow<List<ExtractedImage>> = combine(
        _extractedImages,
        searchQuery,
        selectedTypeFilter,
        sortBy
    ) { images, query, typeFilter, sort ->
        var list = images

        // 1. Search Query
        if (query.isNotEmpty()) {
            list = list.filter { 
                it.name.contains(query, ignoreCase = true) || 
                it.url.contains(query, ignoreCase = true) || 
                (it.altText != null && it.altText.contains(query, ignoreCase = true))
            }
        }

        // 2. Type Filter
        if (typeFilter != null) {
            list = list.filter { it.type.equals(typeFilter, ignoreCase = true) }
        }

        // 3. Sorting
        when (sort) {
            SortBy.DEFAULT -> list
            SortBy.NAME -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            SortBy.SIZE -> list.sortedByDescending { it.sizeBytes }
            SortBy.WIDTH -> list.sortedByDescending { it.width }
            SortBy.HEIGHT -> list.sortedByDescending { it.height }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Core Operations ---

    fun triggerExtraction() {
        val url = inputUrl.value.trim()
        if (url.isEmpty()) {
            _extractionError.value = "Insira uma URL válida para continuar."
            return
        }

        viewModelScope.launch {
            _isExtracting.value = true
            _extractionError.value = null
            _extractedImages.value = emptyList()
            _selectedUrls.value = emptySet()
            
            try {
                val (title, list) = repository.extractImagesFromUrl(
                    inputUrl = url,
                    includeBackgrounds = includeBackgrounds.value,
                    includeSvgs = includeSvgs.value,
                    includeMetaAndIcons = includeMetaAndIcons.value
                )
                
                _extractedTitle.value = title
                _extractedImages.value = list
                
                if (list.isEmpty()) {
                    _toastEvent.emit("Nenhuma imagem encontrada com as estratégias selecionadas.")
                } else {
                    _toastEvent.emit("Extraídas ${list.size} imagens com sucesso!")
                    // Trigger asynchronous background analysis of head headers (size and mime-type) without blocking
                    analyzeLoadedImagesInBg()
                }
            } catch (e: Exception) {
                _extractionError.value = e.localizedMessage ?: "Ocorreu um erro desconhecido durante a extração."
            } finally {
                _isExtracting.value = false
            }
        }
    }

    /**
     * Set active extraction from local database history
     */
    fun selectHistoryItem(history: ExtractionHistory) {
        viewModelScope.launch {
            inputUrl.value = history.siteUrl
            _extractedTitle.value = history.siteTitle
            _extractedImages.value = repository.deserializeImages(history.imagesJson)
            _selectedUrls.value = emptySet()
            _extractionError.value = null
            _toastEvent.emit("Carregado histórico para ${history.siteTitle}")
        }
    }

    fun unsaveImage(url: String) {
        viewModelScope.launch {
            repository.unsaveImage(url)
            _toastEvent.emit("Removido dos favoritos.")
        }
    }

    fun deleteHistoryById(id: Long) {
        viewModelScope.launch {
            repository.deleteHistoryById(id)
            _toastEvent.emit("Item de histórico excluído.")
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
            _toastEvent.emit("Todo o histórico foi limpo.")
        }
    }

    private fun analyzeLoadedImagesInBg() {
        viewModelScope.launch(Dispatchers.Default) {
            val list = _extractedImages.value
            val analyzed = list.map { img ->
                // Analyze image size (HEAD details call)
                repository.analyzeImageDetails(img)
            }
            _extractedImages.value = analyzed
        }
    }

    // --- Saved / pins logic ---
    fun togglePinImage(img: ExtractedImage) {
        viewModelScope.launch {
            val isCurrentlySaved = savedImagesList.value.any { it.url == img.url }
            if (isCurrentlySaved) {
                repository.unsaveImage(img.url)
                _toastEvent.emit("Removido dos favoritos.")
            } else {
                repository.saveImage(
                    SavedImage(
                        url = img.url,
                        name = img.name,
                        type = img.type,
                        width = img.width,
                        height = img.height,
                        sizeBytes = img.sizeBytes,
                        sourceSiteUrl = img.sourceSiteUrl,
                        sourceSiteTitle = _extractedTitle.value.ifEmpty { "Origem desconhecida" },
                        savedAt = System.currentTimeMillis()
                    )
                )
                _toastEvent.emit("Salvo nos favoritos!")
            }
        }
    }

    // --- Gemini Interactive Analyzer ---
    fun runGeminiAnalysis(imageUrl: String) {
        if (_analysisLoadingMap.value[imageUrl] == true) return
        
        viewModelScope.launch {
            _analysisLoadingMap.value = _analysisLoadingMap.value + (imageUrl to true)
            try {
                val outcome = geminiService.analyzeImage(imageUrl)
                _analysisMap.value = _analysisMap.value + (imageUrl to outcome)
            } catch (e: Exception) {
                _analysisMap.value = _analysisMap.value + (imageUrl to "Erro de análise: ${e.localizedMessage}")
            } finally {
                _analysisLoadingMap.value = _analysisLoadingMap.value + (imageUrl to false)
            }
        }
    }

    // --- Selection and batch togglers ---
    fun toggleSelectUrl(url: String) {
        val currentSet = _selectedUrls.value
        if (currentSet.contains(url)) {
            _selectedUrls.value = currentSet - url
        } else {
            _selectedUrls.value = currentSet + url
        }
    }

    fun selectAllMatched() {
        val urls = filteredImages.value.map { it.url }.toSet()
        _selectedUrls.value = urls
    }

    fun selectNone() {
        _selectedUrls.value = emptySet()
    }

    // --- Action Helpers (Copy and Batch Export) ---

    fun copySelectedToClipboard() {
        val urls = if (_selectedUrls.value.isEmpty() && filteredImages.value.isNotEmpty()) {
            // fallback to copying all matched if none checked
            _extractedImages.value.map { it.url }
        } else {
            _selectedUrls.value.toList()
        }

        if (urls.isEmpty()) {
            viewModelScope.launch { _toastEvent.emit("Nenhum link disponível para cópia") }
            return
        }

        val textToCopy = urls.joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("URLs de Imagens", textToCopy)
        clipboard.setPrimaryClip(clip)
        
        viewModelScope.launch {
            _toastEvent.emit("Copiados ${urls.size} links para a área de transferência!")
        }
    }

    fun exportSelectedAsJson(): String {
        val imagesToExport = _extractedImages.value.filter { 
            _selectedUrls.value.isEmpty() || _selectedUrls.value.contains(it.url) 
        }
        return repository.serializeImages(imagesToExport)
    }

    fun copyJsonToClipboard() {
        val json = exportSelectedAsJson()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("JSON Export", json)
        clipboard.setPrimaryClip(clip)
        viewModelScope.launch {
            _toastEvent.emit("JSON de exportação copiado!")
        }
    }

    // --- Downloading Implementation (Zero Permissions needed on modern Android APIs) ---

    fun downloadImage(image: ExtractedImage) {
        viewModelScope.launch {
            _toastEvent.emit("Iniciando download de: ${image.name}")
            val success = performDownload(image)
            if (success) {
                _toastEvent.emit("Download concluído: Salvo na pasta Downloads")
            } else {
                _toastEvent.emit("Falha no download para ${image.name}")
            }
        }
    }

    fun downloadSelectedInBatch() {
        val urlsToDownload = _selectedUrls.value
        if (urlsToDownload.isEmpty()) {
            viewModelScope.launch { _toastEvent.emit("Nenhuma imagem selecionada para download em lote.") }
            return
        }

        val imagesToDownload = _extractedImages.value.filter { urlsToDownload.contains(it.url) }
        viewModelScope.launch {
            _toastEvent.emit("Baixando em lote ${imagesToDownload.size} imagens...")
            var successCount = 0
            
            withContext(Dispatchers.IO) {
                for (img in imagesToDownload) {
                    val ok = performDownload(img)
                    if (ok) successCount++
                }
            }
            
            _toastEvent.emit("Lote concluído! $successCount de ${imagesToDownload.size} salvas na pasta pública Downloads.")
            _selectedUrls.value = emptySet()
        }
    }

    private suspend fun performDownload(image: ExtractedImage): Boolean = withContext(Dispatchers.IO) {
        try {
            val filename = if (image.name.isNotEmpty()) image.name else "img_${System.currentTimeMillis()}.jpg"
            val mimeType = when (image.type) {
                "PNG" -> "image/png"
                "WEBP" -> "image/webp"
                "GIF" -> "image/gif"
                "SVG" -> "image/svg+xml"
                else -> "image/jpeg"
            }

            val inputStream: InputStream = if (image.url.startsWith("data:image/svg+xml")) {
                // inline SVG
                val rawSvg = android.net.Uri.decode(image.url.substringAfter("data:image/svg+xml;utf8,"))
                ByteArrayInputStream(rawSvg.toByteArray(Charsets.UTF_8))
            } else {
                // web resource downloads
                val request = Request.Builder().url(image.url).build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false
                response.body?.byteStream() ?: return@withContext false
            }

            // Write to Android Public Downloads Directory (Zero manual runtime permissions needed for shared scope!)
            return@withContext writeInputStreamToDownloads(context, inputStream, filename, mimeType)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    private fun writeInputStreamToDownloads(
        context: Context,
        inputStream: InputStream,
        filename: String,
        mimeType: String
    ): Boolean {
        return try {
            val contentResolver = context.contentResolver
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ExtratorDeImagens")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        inputStream.use { input ->
                            input.copyTo(output)
                        }
                    }
                    true
                } else {
                    false
                }
            } else {
                // Legacy devices fallback (save directly directly in Downloads directory)
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val customDir = java.io.File(dir, "ExtratorDeImagens")
                if (!customDir.exists()) {
                    customDir.mkdirs()
                }
                val outputFile = java.io.File(customDir, filename)
                outputFile.outputStream().use { output ->
                    inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // Utility filters
    fun distinctTypes(): List<String> {
        return _extractedImages.value.map { it.type }.distinct().sorted()
    }
}

// Single factory provider to build ViewModel correctly bypassing complex DI containers.
class ImageExtractorViewModelFactory(
    private val application: Application,
    private val repository: ImageRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ImageExtractorViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ImageExtractorViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Classe ViewModel desconhecida")
    }
}

package com.example

import android.app.Application
import android.content.ClipData
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.*
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Lazy local DB database, repo, and ViewModel creation
    val db = remember { AppDatabase.getDatabase(context) }
    val repo = remember { ImageRepository(context, db.imageDao()) }
    val factory = remember { ImageExtractorViewModelFactory(context.applicationContext as Application, repo) }
    val viewModel: ImageExtractorViewModel = viewModel(factory = factory)

    // Reactive states
    val isExtracting by viewModel.isExtracting.collectAsStateWithLifecycle()
    val extractionError by viewModel.extractionError.collectAsStateWithLifecycle()
    val extractedTitle by viewModel.extractedTitle.collectAsStateWithLifecycle()
    val filteredImages by viewModel.filteredImages.collectAsStateWithLifecycle()
    val selectedUrls by viewModel.selectedUrls.collectAsStateWithLifecycle()
    
    val historyList by viewModel.extractionHistoryList.collectAsStateWithLifecycle()
    val savedImages by viewModel.savedImagesList.collectAsStateWithLifecycle()
    
    val inputUrl by viewModel.inputUrl.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedTypeFilter by viewModel.selectedTypeFilter.collectAsStateWithLifecycle()
    val sortByState by viewModel.sortBy.collectAsStateWithLifecycle()
    val isGridView by viewModel.isGridView.collectAsStateWithLifecycle()
    
    val includeBackgrounds by viewModel.includeBackgrounds.collectAsStateWithLifecycle()
    val includeSvgs by viewModel.includeSvgs.collectAsStateWithLifecycle()
    val includeMetaAndIcons by viewModel.includeMetaAndIcons.collectAsStateWithLifecycle()
    
    val analysisMap by viewModel.analysisMap.collectAsStateWithLifecycle()
    val analysisLoadingMap by viewModel.analysisLoadingMap.collectAsStateWithLifecycle()

    // Tab states: 0 = Extract, 1 = Favorites, 2 = History
    var currentTab by remember { mutableIntStateOf(0) }
    
    // Dialog state for viewing full size / details
    var activeImageDetails by remember { mutableStateOf<ExtractedImage?>(null) }
    
    // Batch select modes
    var isSelectModeActive by remember { mutableStateOf(false) }

    // Settings panel switch state list
    var showSettingsPanel by remember { mutableStateOf(false) }

    // Toast event collection
    LaunchedEffect(Unit) {
        viewModel.toastEvent.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Explore, contentDescription = "Extrair") },
                    label = { Text("Extrair") },
                    modifier = Modifier.testTag("tab_extract")
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.Favorite, contentDescription = "Favoritos") },
                    label = { Text("Favoritos") },
                    modifier = Modifier.testTag("tab_favorites")
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.History, contentDescription = "Histórico") },
                    label = { Text("Histórico") },
                    modifier = Modifier.testTag("tab_history")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        ) {
            when (currentTab) {
                0 -> Column(modifier = Modifier.fillMaxSize()) {
                    // Header Card
                    ExtractionHeaderView(
                        inputUrl = inputUrl,
                        onUrlChange = { viewModel.inputUrl.value = it },
                        isExtracting = isExtracting,
                        showSettings = showSettingsPanel,
                        onToggleSettings = { showSettingsPanel = !showSettingsPanel },
                        onExtract = { viewModel.triggerExtraction() }
                    )

                    // Expandable advanced properties selectors
                    AnimatedVisibility(
                        visible = showSettingsPanel,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        ExtractionSettingsCard(
                            includeBackgrounds = includeBackgrounds,
                            includeSvgs = includeSvgs,
                            includeMetaAndIcons = includeMetaAndIcons,
                            onToggleBackgrounds = { viewModel.includeBackgrounds.value = !includeBackgrounds },
                            onToggleSvgs = { viewModel.includeSvgs.value = !includeSvgs },
                            onToggleMeta = { viewModel.includeMetaAndIcons.value = !includeMetaAndIcons }
                        )
                    }

                    // Content results
                    if (isExtracting) {
                        ImageExtractionLoadingBlock()
                    } else if (extractionError != null) {
                        ExtractionErrorBlock(
                            errorMsg = extractionError ?: "",
                            onRetry = { viewModel.triggerExtraction() }
                        )
                    } else if (filteredImages.isEmpty() && viewModel.extractedImages.value.isEmpty()) {
                        EmptyStateSplashCard()
                    } else {
                        // Regular Results Interface
                        ResultControllerBar(
                            searchQuery = searchQuery,
                            onQueryChange = { viewModel.searchQuery.value = it },
                            selectedFilter = selectedTypeFilter,
                            onFilterChange = { viewModel.selectedTypeFilter.value = it },
                            allFilters = viewModel.distinctTypes(),
                            sortBy = sortByState,
                            onSortChange = { viewModel.sortBy.value = it },
                            isGridView = isGridView,
                            onToggleLayout = { viewModel.isGridView.value = !isGridView },
                            isSelectMode = isSelectModeActive,
                            onToggleSelectMode = { 
                                isSelectModeActive = !isSelectModeActive
                                if (!isSelectModeActive) viewModel.selectNone()
                            },
                            selectedCount = selectedUrls.size,
                            onSelectAll = { viewModel.selectAllMatched() },
                            onClearSelection = { viewModel.selectNone() },
                            onCopyMarkdown = { viewModel.copySelectedToClipboard() },
                            onCopyJson = { viewModel.copyJsonToClipboard() },
                            onBatchDownload = { viewModel.downloadSelectedInBatch() }
                        )

                        // Images feed Scroll container
                        ImagesScrollFeed(
                            images = filteredImages,
                            isGridView = isGridView,
                            selectedUrls = selectedUrls,
                            isSelectMode = isSelectModeActive,
                            onImageClick = { img ->
                                if (isSelectModeActive) {
                                    viewModel.toggleSelectUrl(img.url)
                                } else {
                                    activeImageDetails = img
                                }
                            },
                            onImageLongClick = { img ->
                                if (!isSelectModeActive) {
                                    isSelectModeActive = true
                                    viewModel.toggleSelectUrl(img.url)
                                }
                            },
                            onTogglePin = { viewModel.togglePinImage(it) },
                            savedImages = savedImages
                        )
                    }
                }
                
                1 -> FavoritesGalleryView(
                    savedImages = savedImages,
                    onImageClick = { savedImg ->
                        // Convert savedImage schema to extracted image matching details dialog
                        activeImageDetails = ExtractedImage(
                            url = savedImg.url,
                            name = savedImg.name,
                            type = savedImg.type,
                            width = savedImg.width,
                            height = savedImg.height,
                            sizeBytes = savedImg.sizeBytes,
                            sourceSiteUrl = savedImg.sourceSiteUrl
                        )
                    },
                    onUnsave = { viewModel.unsaveImage(it.url) },
                    onDownload = { savedImg ->
                        viewModel.downloadImage(
                            ExtractedImage(
                                url = savedImg.url,
                                name = savedImg.name,
                                type = savedImg.type,
                                width = savedImg.width,
                                height = savedImg.height,
                                sizeBytes = savedImg.sizeBytes,
                                sourceSiteUrl = savedImg.sourceSiteUrl
                            )
                        )
                    }
                )

                2 -> HistoryTimelineView(
                    historyEntries = historyList,
                    onLoadHistory = { history ->
                        viewModel.selectHistoryItem(history)
                        currentTab = 0 // bounce back to browser
                    },
                    onDeleteHistory = { viewModel.deleteHistoryById(it) },
                    onClearAll = { viewModel.clearAllHistory() }
                )
            }

            // High-fidelity image details sheet modal
            activeImageDetails?.let { image ->
                ImageAnalysisDialog(
                    image = image,
                    isSaved = savedImages.any { it.url == image.url },
                    onToggleSaved = { viewModel.togglePinImage(image) },
                    onDownload = { viewModel.downloadImage(image) },
                    onDismiss = { activeImageDetails = null },
                    onAnalyzeAI = { viewModel.runGeminiAnalysis(image.url) },
                    analysisResult = analysisMap[image.url],
                    isAnalysisLoading = analysisLoadingMap[image.url] == true
                )
            }
        }
    }
}

// ======================== SUB-COMPOSABLES PARSING ========================

@Composable
fun ExtractionHeaderView(
    inputUrl: String,
    onUrlChange: (String) -> Unit,
    isExtracting: Boolean,
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onExtract: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Extrator de Imagens",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Versão 3.3 • Filtragem & Análise de Mídia",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                IconButton(
                    onClick = onToggleSettings,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (showSettings) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Configurações Avançadas"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // URL input box
            OutlinedTextField(
                value = inputUrl,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("url_text_input"),
                placeholder = { Text("site.com ou https://google.com") },
                leadingIcon = {
                    Icon(Icons.Default.Language, contentDescription = "Website URL")
                },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (inputUrl.isNotEmpty()) {
                            IconButton(onClick = { onUrlChange("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Limpar")
                            }
                        }
                        IconButton(
                            onClick = {
                                clipboardManager.getText()?.let { text ->
                                    onUrlChange(text.text)
                                }
                            }
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Colar")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboardController?.hide()
                        onExtract()
                    }
                ),
                shape = RoundedCornerShape(16.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    keyboardController?.hide()
                    onExtract()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("extract_trigger_button"),
                enabled = !isExtracting && inputUrl.trim().isNotEmpty(),
                shape = RoundedCornerShape(16.dp)
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analisando Elementos...")
                } else {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Encontrar Todas as Imagens", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ExtractionSettingsCard(
    includeBackgrounds: Boolean,
    includeSvgs: Boolean,
    includeMetaAndIcons: Boolean,
    onToggleBackgrounds: () -> Unit,
    onToggleSvgs: () -> Unit,
    onToggleMeta: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Estratégias de Extração de Mídia",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(12.dp))

            ToggleSwitchRow(
                label = "Imagens de Fundo",
                description = "Busca estilos em CSS inline 'background-image: url()'",
                checked = includeBackgrounds,
                onCheckedChange = { onToggleBackgrounds() }
            )
            
            Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            ToggleSwitchRow(
                label = "Elementos SVG incorporados",
                description = "Detecta tags <svg> nativas e as gera em arquivos de formato direto",
                checked = includeSvgs,
                onCheckedChange = { onToggleSvgs() }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            ToggleSwitchRow(
                label = "Metadados de Redes Sociais & Icons",
                description = "og:image, twitter:image, favicons e logotipos",
                checked = includeMetaAndIcons,
                onCheckedChange = { onToggleMeta() }
            )
        }
    }
}

@Composable
fun ToggleSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("switch_$label")
        )
    }
}

@Composable
fun ImageExtractionLoadingBlock() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
            strokeWidth = 4.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Utilizando múltiplos métodos...",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Vasculhando tags HTML, CSS inline e metadados.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

@Composable
fun ExtractionErrorBlock(errorMsg: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Falha na Extração",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = errorMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Tentar Novamente")
            }
        }
    }
}

@Composable
fun EmptyStateSplashCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // High polish backdrop banner
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(180.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.img_hero_banner),
                    contentDescription = "Fundo decorativo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                )
                Text(
                    text = "Aproveite a Versão 3.3",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Nenhuma Imagem Descoberta",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Digite uma URL de site para realizar uma varredura completa. Também salvamos seu histórico para que nunca perca o trabalho!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AssistChip(
                onClick = { /* Demo paste link button */ },
                leadingIcon = { Icon(Icons.Default.Lightbulb, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Exemplo: google.com") }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultControllerBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    selectedFilter: String?,
    onFilterChange: (String?) -> Unit,
    allFilters: List<String>,
    sortBy: SortBy,
    onSortChange: (SortBy) -> Unit,
    isGridView: Boolean,
    onToggleLayout: () -> Unit,
    isSelectMode: Boolean,
    onToggleSelectMode: () -> Unit,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCopyMarkdown: () -> Unit,
    onCopyJson: () -> Unit,
    onBatchDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Row 1: Search and selection triggers
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("result_search_input"),
                    placeholder = { Text("Pesquisar por nome...", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp)
                )

                IconButton(
                    onClick = onToggleLayout,
                    modifier = Modifier.testTag("btn_toggle_layout")
                ) {
                    Icon(
                        imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                        contentDescription = "Alternar Visualização"
                    )
                }

                FilledTonalIconButton(
                    onClick = onToggleSelectMode,
                    modifier = Modifier.testTag("btn_select_mode"),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isSelectMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = if (isSelectMode) Icons.Default.Rule else Icons.Outlined.CheckCircle,
                        contentDescription = "Seleção em lote"
                    )
                }
            }

            // Quick Sort and Filters Row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Formatting sort drop down
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Filtrar e Ordenar: ", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    var expandedSort by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(
                            onClick = { expandedSort = true },
                            label = { 
                                Text(
                                    text = when(sortBy) {
                                        SortBy.DEFAULT -> "Padrão"
                                        SortBy.NAME -> "Nome"
                                        SortBy.SIZE -> "Tamanho"
                                        SortBy.WIDTH -> "Largura"
                                        SortBy.HEIGHT -> "Altura"
                                    },
                                    fontSize = 12.sp
                                )
                            },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
                        )
                        DropdownMenu(
                            expanded = expandedSort,
                            onDismissRequest = { expandedSort = false }
                        ) {
                            DropdownMenuItem(text = { Text("Padrão") }, onClick = { onSortChange(SortBy.DEFAULT); expandedSort = false })
                            DropdownMenuItem(text = { Text("Nome (A-Z)") }, onClick = { onSortChange(SortBy.NAME); expandedSort = false })
                            DropdownMenuItem(text = { Text("Tamanho") }, onClick = { onSortChange(SortBy.SIZE); expandedSort = false })
                            DropdownMenuItem(text = { Text("Largura") }, onClick = { onSortChange(SortBy.WIDTH); expandedSort = false })
                            DropdownMenuItem(text = { Text("Altura") }, onClick = { onSortChange(SortBy.HEIGHT); expandedSort = false })
                        }
                    }
                }

                // Batch Operations Panel triggering
                if (isSelectMode) {
                    Text(
                        text = "$selectedCount selecionadas",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Row dynamic Chips filter metrics
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InputChip(
                    selected = selectedFilter == null,
                    onClick = { onFilterChange(null) },
                    label = { Text("Todas") }
                )
                
                allFilters.forEach { t ->
                    InputChip(
                        selected = selectedFilter == t,
                        onClick = { onFilterChange(t) },
                        label = { Text(t) }
                    )
                }
            }

            // Batch selection contextual action buttons
            AnimatedVisibility(visible = isSelectMode) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onSelectAll) {
                                Text("Tudo", fontSize = 12.sp)
                            }
                            TextButton(onClick = onClearSelection) {
                                Text("Nenhum", fontSize = 12.sp)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(onClick = onCopyMarkdown) {
                                Icon(Icons.Default.Link, contentDescription = "Copiar Links", tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = onCopyJson) {
                                Icon(Icons.Default.Code, contentDescription = "Exportar JSON")
                            }
                            Button(
                                onClick = onBatchDownload,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Download", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImagesScrollFeed(
    images: List<ExtractedImage>,
    isGridView: Boolean,
    selectedUrls: Set<String>,
    isSelectMode: Boolean,
    onImageClick: (ExtractedImage) -> Unit,
    onImageLongClick: (ExtractedImage) -> Unit,
    onTogglePin: (ExtractedImage) -> Unit,
    savedImages: List<SavedImage>
) {
    if (isGridView) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(images, key = { it.url }) { img ->
                ImageTileCard(
                    image = img,
                    isGridView = true,
                    isSelected = selectedUrls.contains(img.url),
                    isSelectMode = isSelectMode,
                    onClick = { onImageClick(img) },
                    onLongClick = { onImageLongClick(img) },
                    isSaved = savedImages.any { it.url == img.url },
                    onTogglePin = { onTogglePin(img) }
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(images, key = { it.url }) { img ->
                ImageTileCard(
                    image = img,
                    isGridView = false,
                    isSelected = selectedUrls.contains(img.url),
                    isSelectMode = isSelectMode,
                    onClick = { onImageClick(img) },
                    onLongClick = { onImageLongClick(img) },
                    isSaved = savedImages.any { it.url == img.url },
                    onTogglePin = { onTogglePin(img) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageTileCard(
    image: ExtractedImage,
    isGridView: Boolean,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isSaved: Boolean,
    onTogglePin: () -> Unit
) {
    val context = LocalContext.current
    val cardBorderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val cardBorderWidth = if (isSelected) 3.dp else 0.dp

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(cardBorderWidth, cardBorderColor, RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (isGridView) {
            Box(modifier = Modifier.aspectRatio(1f)) {
                // Background image loading asynchronously
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(image.url.ifEmpty { "https://placehold.co/150" })
                        .crossfade(true)
                        .build(),
                    contentDescription = image.altText ?: image.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // SVG elements tags indicator
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = image.type,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Batch Selection Checkbox overlays
                if (isSelectMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = if (isSelected) 0.4f else 0.1f))
                    )
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .testTag("checkbox_${image.name}")
                    )
                } else {
                    // Quick pinning / unpinning button
                    IconButton(
                        onClick = onTogglePin,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                    ) {
                        Icon(
                            imageVector = if (isSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Salvar favorita",
                            tint = if (isSaved) Color.Red else Color.White
                        )
                    }
                }

                // Title banner bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                            )
                        )
                        .padding(6.dp)
                ) {
                    Text(
                        text = image.name,
                        color = Color.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            // Horizontal list description card
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(image.url)
                        .crossfade(true)
                        .build(),
                    contentDescription = image.altText,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = image.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = "Tipo: ${image.type}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    
                    if (image.sizeBytes > 0) {
                        Text(
                            text = "Peso: ${formatByteSize(image.sizeBytes)}", 
                            style = MaterialTheme.typography.bodySmall, 
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                if (isSelectMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onClick() })
                } else {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (isSaved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isSaved) Color.Red else Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FavoritesGalleryView(
    savedImages: List<SavedImage>,
    onImageClick: (SavedImage) -> Unit,
    onUnsave: (SavedImage) -> Unit,
    onDownload: (SavedImage) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Biblioteca de Favoritos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${savedImages.size} imagens salvas localmente no dispositivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (savedImages.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Sem favoritos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Salve imagens interessantes de sites para mostrá-las juntas em sua biblioteca pessoal e baixá-las a qualquer momento.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 130.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(savedImages, key = { it.url }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onImageClick(item) },
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(modifier = Modifier.aspectRatio(1f)) {
                            AsyncImage(
                                model = item.url,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )

                            // Quick download action floating
                            FilledTonalIconButton(
                                onClick = { onDownload(item) },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .size(36.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Baixar", modifier = Modifier.size(18.dp))
                            }

                            // Quick unsave action floating
                            IconButton(
                                onClick = { onUnsave(item) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                    .size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Desfavoritar",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryTimelineView(
    historyEntries: List<ExtractionHistory>,
    onLoadHistory: (ExtractionHistory) -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Histórico de Varredura",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${historyEntries.size} sites visitados anteriormente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                if (historyEntries.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Limpar Tudo")
                    }
                }
            }
        }

        if (historyEntries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Sem histórico recente", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Nenhum site visitado ainda por este dispositivo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(historyEntries, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                    .padding(8.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            
                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.siteTitle,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = item.siteUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatTimestamp(item.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }

                            Row {
                                IconButton(onClick = { onLoadHistory(item) }) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = "Carregar",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(onClick = { onDeleteHistory(item.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Excluir",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImageAnalysisDialog(
    image: ExtractedImage,
    isSaved: Boolean,
    onToggleSaved: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onAnalyzeAI: () -> Unit,
    analysisResult: String?,
    isAnalysisLoading: Boolean
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f)
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Análise de Mídia",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar")
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        // Grand image preview
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                AsyncImage(
                                    model = image.url,
                                    contentDescription = image.altText,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.05f))
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(8.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = image.type,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    item {
                        // General properties table
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = "Propriedades", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                PropertyRow("Nome do arquivo", image.name)
                                PropertyRow("Tipo de formato", image.type)
                                PropertyRow("Peso do arquivo", if (image.sizeBytes > 0) formatByteSize(image.sizeBytes) else "Desconhecido (Sem cabeçalho HEAD)")
                                PropertyRow("Website de Origem", image.sourceSiteUrl)
                                PropertyRow("Texto Alt (HTML)", image.altText?.ifEmpty { "Nenhum" } ?: "Nenhum")
                            }
                        }
                    }

                    item {
                        // Action mechanics
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onDownload,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Baixar Mídia")
                            }

                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(image.url))
                                    Toast.makeText(context, "Link copiado!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copiar Link")
                            }
                        }
                    }

                    item {
                        // --- Gemini Interactive Analyzer Card ---
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Análise Pró Gemini AI",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    
                                    Button(
                                        onClick = onAnalyzeAI,
                                        enabled = !isAnalysisLoading,
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        if (isAnalysisLoading) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Text("Analisar")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                if (isAnalysisLoading) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                                    ) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("A IA está buscando a imagem e descrevendo cores, tags e alt-texts de acessibilidade...", style = MaterialTheme.typography.bodySmall)
                                    }
                                } else if (analysisResult != null) {
                                    SimpleMarkdownText(markdownText = analysisResult)
                                } else {
                                    Text(
                                        text = "Toque em Analisar para decodificar esta imagem com o Gemini AI. Ele gerará descrições acessíveis, paleta estética e tags inteligentes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Simple, beautiful parser for standard Gemini markdown structures
 */
@Composable
fun SimpleMarkdownText(markdownText: String) {
    val lines = markdownText.split("\n")
    Column {
        lines.forEach { line ->
            when {
                line.startsWith("###") -> {
                    val header = line.substringAfter("###").trim()
                    Text(
                        text = header,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                }
                line.startsWith("##") -> {
                    val header = line.substringAfter("##").trim()
                    Text(
                        text = header,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
                    )
                }
                line.startsWith("-") || line.startsWith("*") -> {
                    val cleanText = line.trimStart('-', '*', ' ').trim()
                    Row(
                        modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("• ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                        Text(text = cleanText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                line.trim().isNotEmpty() -> {
                    Text(
                        text = line.trim(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }
        }
    }
}

// --- Dynamic Utils Formatters ---

private fun formatByteSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

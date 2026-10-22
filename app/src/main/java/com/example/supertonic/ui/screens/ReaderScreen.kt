package com.example.supertonic.ui.screens

import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.supertonic.engine.SupertonicConstants
import com.example.supertonic.playback.PlaybackStatus
import com.example.supertonic.ui.MainViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val currentDoc by viewModel.currentDocMetadata.collectAsState()
    val currentChapterIdx by viewModel.currentChapterIndex.collectAsState()
    val paragraphs by viewModel.currentParagraphs.collectAsState()
    val activeParagraphIdx by viewModel.currentParagraphIndex.collectAsState()
    val documentStats by viewModel.documentStats.collectAsState()

    val selectedLang by viewModel.selectedLang.collectAsState()
    val detectedLang by viewModel.detectedLang.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val steps by viewModel.synthesisSteps.collectAsState()
    val speed by viewModel.speechSpeed.collectAsState()
    val playbackState by viewModel.servicePlaybackState.collectAsState()

    val isImporting by viewModel.isImportingDocument.collectAsState()
    val importMessage by viewModel.importProgress.collectAsState()

    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val exportMessage by viewModel.exportStatusMessage.collectAsState()

    var showLangMenu by remember { mutableStateOf(false) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showTextEditDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Auto-scroll to active paragraph on change
    LaunchedEffect(activeParagraphIdx) {
        if (paragraphs.isNotEmpty() && activeParagraphIdx in paragraphs.indices) {
            try {
                listState.animateScrollToItem(activeParagraphIdx)
            } catch (ignored: Exception) {}
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            var fileName = "document"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            viewModel.importDocument(uri, fileName)
        }
    }

    Scaffold(
        containerColor = ObsidianBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Top Action Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language Selector
                Box {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { showLangMenu = true }
                            .testTag("language_selector_button"),
                        color = ObsidianCard,
                        contentColor = TextPrimary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Language,
                                contentDescription = "Выбор языка",
                                tint = CoralPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            val langLabel = if (selectedLang == "auto") {
                                "Авто · ${detectedLang.uppercase()}"
                            } else {
                                selectedLang.uppercase()
                            }
                            Text(text = langLabel, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary)
                        }
                    }

                    DropdownMenu(
                        expanded = showLangMenu,
                        onDismissRequest = { showLangMenu = false },
                        modifier = Modifier.background(ObsidianElevated)
                    ) {
                        DropdownMenuItem(
                            text = { Text("Авто · приоритет русского", color = CoralPrimary, fontWeight = FontWeight.Bold) },
                            onClick = {
                                viewModel.selectedLang.value = "auto"
                                showLangMenu = false
                            }
                        )
                        Divider(color = ObsidianCard)
                        SupertonicConstants.SUPPORTED_LANGUAGES.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text("$name ($code)", color = TextPrimary) },
                                onClick = {
                                    viewModel.selectedLang.value = code
                                    showLangMenu = false
                                }
                            )
                        }
                    }
                }

                // Import & Manual Text Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            documentPickerLauncher.launch(
                                arrayOf("text/plain", "application/pdf", "application/epub+zip", "*/*")
                            )
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = CoralPrimary.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Импорт книги", tint = CoralPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Импорт", fontSize = 12.sp, color = CoralPrimary, fontWeight = FontWeight.Bold)
                    }

                    FilledTonalButton(
                        onClick = { showTextEditDialog = true },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = ObsidianCard),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.EditNote, contentDescription = "Редактировать текст", tint = TextPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Текст", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // 2. Import Progress Banner
            AnimatedVisibility(visible = isImporting) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ObsidianElevated),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CoralPrimary, strokeWidth = 2.dp)
                        Text(importMessage ?: "Импорт книги...", color = TextPrimary, fontSize = 12.sp)
                    }
                }
            }

            // 3. Document Title & Chapters Strip
            currentDoc?.let { doc ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard.copy(alpha = 0.8f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = AmberWave, modifier = Modifier.size(16.dp))
                            Text(
                                text = doc.title,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (doc.chapters.size > 1) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                itemsIndexed(doc.chapters) { idx, chapter ->
                                    val isSelected = idx == currentChapterIdx
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.loadChapter(idx) },
                                        label = { Text(chapter.title, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = CoralPrimary,
                                            selectedLabelColor = ObsidianBackground,
                                            containerColor = ObsidianElevated,
                                            labelColor = TextPrimary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Synthesis Error Banner with Retry Button (Task 3)
            if (playbackState.status == PlaybackStatus.ERROR && !playbackState.errorMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.18f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(20.dp))
                            Text(
                                text = playbackState.errorMessage ?: "Ошибка синтеза",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Button(
                            onClick = { viewModel.retrySynthesis() },
                            colors = ButtonDefaults.buttonColors(containerColor = CoralPrimary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("retry_synthesis_button")
                        ) {
                            Text("Повторить", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ObsidianBackground)
                        }
                    }
                }
            }

            // 5. Read-Only Paragraphs LazyColumn (Task 8)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (paragraphs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Нет текста. Нажмите «Импорт» или «Текст» для добавления.", color = TextSecondary, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                                .testTag("paragraphs_lazy_column"),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(paragraphs) { pIdx, paragraph ->
                                val isCurrentParagraph = pIdx == activeParagraphIdx && playbackState.status == PlaybackStatus.PLAYING

                                if (isCurrentParagraph && playbackState.currentSentenceEnd > playbackState.currentSentenceStart) {
                                    val relStart = (playbackState.currentSentenceStart - paragraph.charStart).coerceIn(0, paragraph.text.length)
                                    val relEnd = (playbackState.currentSentenceEnd - paragraph.charStart).coerceIn(relStart, paragraph.text.length)

                                    val annotated = buildAnnotatedString {
                                        if (relStart > 0) {
                                            append(paragraph.text.substring(0, relStart))
                                        }
                                        pushStyle(SpanStyle(background = CoralPrimary.copy(alpha = 0.35f), color = TextPrimary, fontWeight = FontWeight.Bold))
                                        append(paragraph.text.substring(relStart, relEnd))
                                        pop()
                                        if (relEnd < paragraph.text.length) {
                                            append(paragraph.text.substring(relEnd))
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = CoralPrimary.copy(alpha = 0.08f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { viewModel.readFromParagraph(pIdx) }
                                    ) {
                                        Text(
                                            text = annotated,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = TextPrimary,
                                            lineHeight = 24.sp,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.Transparent,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { viewModel.readFromParagraph(pIdx) }
                                    ) {
                                        Text(
                                            text = paragraph.text,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (isCurrentParagraph) TextPrimary else TextSecondary,
                                            lineHeight = 24.sp,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Precalculated Character/Word Counter
                    Text(
                        text = documentStats,
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                    )
                }
            }

            // 6. Voice, Steps, Speed Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Voice Picker
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showVoiceMenu = true },
                        colors = CardDefaults.outlinedCardColors(containerColor = ObsidianCard),
                        shape = RoundedCornerShape(12.dp),
                        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(ObsidianElevated))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Голос", fontSize = 10.sp, color = TextSecondary)
                                Text(selectedVoice, fontWeight = FontWeight.Bold, color = CoralPrimary, fontSize = 14.sp)
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary)
                        }
                    }

                    DropdownMenu(
                        expanded = showVoiceMenu,
                        onDismissRequest = { showVoiceMenu = false },
                        modifier = Modifier.background(ObsidianElevated)
                    ) {
                        val availableVoices = viewModel.getAvailableVoices()
                        availableVoices.forEach { v ->
                            val desc = when (v) {
                                "F1" -> "F1 (Женский, выразительный)"
                                "F2" -> "F2 (Женский, мягкий)"
                                "M1" -> "M1 (Мужской, глубокий)"
                                "M2" -> "M2 (Мужской, спокойный)"
                                else -> v
                            }
                            DropdownMenuItem(
                                text = { Text(desc, color = TextPrimary) },
                                onClick = {
                                    viewModel.selectedVoice.value = v
                                    showVoiceMenu = false
                                }
                            )
                        }
                    }
                }

                // Steps selector
                OutlinedCard(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.outlinedCardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(ObsidianElevated))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text("Шаги инференса", fontSize = 10.sp, color = TextSecondary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            listOf(2, 3, 5).forEach { s ->
                                Text(
                                    text = "${s}x",
                                    color = if (steps == s) CoralPrimary else TextSecondary,
                                    fontWeight = if (steps == s) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (steps == s) CoralPrimary.copy(alpha = 0.2f) else Color.Transparent)
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                        .clickable { viewModel.synthesisSteps.value = s }
                                )
                            }
                        }
                    }
                }

                // Playback Speed
                OutlinedCard(
                    modifier = Modifier.weight(1.1f),
                    colors = CardDefaults.outlinedCardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(ObsidianElevated))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text("Скорость", fontSize = 10.sp, color = TextSecondary)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            listOf(1.0f, 1.3f, 1.5f).forEach { sp ->
                                val label = String.format("%.1fx", sp)
                                Text(
                                    text = label,
                                    color = if (speed == sp) MintVoice else TextSecondary,
                                    fontWeight = if (speed == sp) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (speed == sp) MintVoice.copy(alpha = 0.2f) else Color.Transparent)
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .clickable { viewModel.onSpeedChanged(sp) }
                                )
                            }
                        }
                    }
                }
            }

            // 7. Playback Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Фраза ${playbackState.currentChunkIndex + 1} из ${maxOf(1, playbackState.totalChunks)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CoralPrimary
                            )
                            Text(
                                text = playbackState.timelineDisplay,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MintVoice
                            )
                        }
                        Text(
                            text = "Буфер: ${String.format("%.1f", playbackState.bufferedSeconds)}с | RTF: ${String.format("%.2f", playbackState.rtf)}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    if (playbackState.totalChunks > 1) {
                        Slider(
                            value = playbackState.currentChunkIndex.toFloat(),
                            onValueChange = { targetFloat ->
                                viewModel.seekToChunk(targetFloat.toInt())
                            },
                            valueRange = 0f..(playbackState.totalChunks - 1).toFloat(),
                            steps = (playbackState.totalChunks - 2).coerceAtLeast(0),
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = CoralPrimary,
                                activeTrackColor = CoralPrimary,
                                inactiveTrackColor = ObsidianElevated
                            )
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.jumpSeconds(-15) }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.Replay10, contentDescription = "-15 сек", tint = TextSecondary)
                        }

                        IconButton(onClick = { viewModel.skipPrevious() }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущая фраза", tint = TextPrimary)
                        }

                        // Play/Pause Button with non-blocking PREPARING state (Task 9)
                        Button(
                            onClick = { viewModel.togglePlayPause() },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("play_pause_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CoralPrimary),
                            shape = CircleShape
                        ) {
                            when (playbackState.status) {
                                PlaybackStatus.PREPARING -> {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = ObsidianBackground, strokeWidth = 2.dp)
                                }
                                PlaybackStatus.BUFFERING -> {
                                    Icon(Icons.Default.HourglassTop, contentDescription = "Буферизация", tint = ObsidianBackground, modifier = Modifier.size(24.dp))
                                }
                                PlaybackStatus.PLAYING -> {
                                    Icon(Icons.Default.Pause, contentDescription = "Пауза", tint = ObsidianBackground, modifier = Modifier.size(24.dp))
                                }
                                else -> {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Играть", tint = ObsidianBackground, modifier = Modifier.size(24.dp))
                                }
                            }
                        }

                        IconButton(onClick = { viewModel.skipNext() }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Следующая фраза", tint = TextPrimary)
                        }

                        IconButton(onClick = { viewModel.jumpSeconds(15) }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.Forward10, contentDescription = "+15 сек", tint = TextSecondary)
                        }

                        IconButton(onClick = { viewModel.stopPlayback() }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.Stop, contentDescription = "Стоп", tint = ErrorRed)
                        }
                    }
                }
            }

            // 8. Background Export & External Player Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { showExportDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null, tint = CoralPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Создать файл в фоне", color = TextPrimary, fontSize = 12.sp)
                }

                FilledTonalButton(
                    onClick = { viewModel.exportAndOpenInVlc(context, "m4a") },
                    modifier = Modifier.height(44.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Launch, contentDescription = null, tint = MintVoice, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Открыть в VLC", color = MintVoice, fontSize = 12.sp)
                }
            }

            // Export Progress
            AnimatedVisibility(visible = isExporting) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = ObsidianElevated)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Фоновое создание аудиофайла...", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("${(exportProgress * 100).toInt()}%", color = CoralPrimary, fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = CoralPrimary,
                            trackColor = ObsidianCard
                        )
                    }
                }
            }

            if (!exportMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MintVoice.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MintVoice, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(exportMessage ?: "", color = MintVoice, fontSize = 12.sp)
                    }
                }
            }
        }

        // Export Dialog
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                containerColor = ObsidianCard,
                title = { Text("Создание аудиофайла", color = TextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Создание аудиофайла выполняется независимо в оперативной памяти на максимальной скорости CPU:",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Button(
                            onClick = {
                                showExportDialog = false
                                viewModel.startBackgroundBatchExport("m4a")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CoralPrimary)
                        ) {
                            Text("AAC (.m4a) — аппаратное сжатие (рекомендуется)")
                        }
                        OutlinedButton(
                            onClick = {
                                showExportDialog = false
                                viewModel.startBackgroundBatchExport("wav")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                        ) {
                            Text("WAV (.wav) — нежатый звук 44.1 кГц")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Отмена", color = TextSecondary)
                    }
                }
            )
        }

        // Separate Text Edit Dialog with 50,000 char limit (Task 8)
        if (showTextEditDialog) {
            TextEditModal(
                initialText = viewModel.currentChapterText.value,
                onDismiss = { showTextEditDialog = false },
                onSave = { title, text ->
                    showTextEditDialog = false
                    viewModel.saveManualText(title, text)
                }
            )
        }
    }
}

@Composable
fun TextEditModal(
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    val maxLimit = 50000
    var textValue by remember { mutableStateOf(initialText) }
    var titleValue by remember { mutableStateOf("Мой текст") }
    val isOverLimit = textValue.length > maxLimit

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianCard,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ввод и правка текста", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "${textValue.length} / $maxLimit",
                    color = if (isOverLimit) ErrorRed else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isOverLimit) {
                    Surface(
                        color = ErrorRed.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Превышен лимит 50 000 символов! Для больших книг и текстов используйте кнопку «Импорт».",
                            color = ErrorRed,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = titleValue,
                    onValueChange = { titleValue = it },
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CoralPrimary,
                        unfocusedBorderColor = ObsidianElevated
                    )
                )

                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = { Text("Вставьте или введите текст для озвучивания...", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = if (isOverLimit) ErrorRed else CoralPrimary,
                        unfocusedBorderColor = ObsidianElevated
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(titleValue, textValue) },
                enabled = !isOverLimit && textValue.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CoralPrimary)
            ) {
                Text("Сохранить в читалку", color = ObsidianBackground, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = TextSecondary)
            }
        }
    )
}

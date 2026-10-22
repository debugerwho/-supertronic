package com.example.supertonic.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import com.example.supertonic.document.DocumentChapter
import com.example.supertonic.engine.SupertonicConstants
import com.example.supertonic.playback.PlaybackStatus
import com.example.supertonic.ui.MainViewModel
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val text by viewModel.inputText.collectAsState()
    val selectedLang by viewModel.selectedLang.collectAsState()
    val detectedLang by viewModel.detectedLang.collectAsState()
    val selectedVoice by viewModel.selectedVoice.collectAsState()
    val steps by viewModel.synthesisSteps.collectAsState()
    val speed by viewModel.speechSpeed.collectAsState()
    val playbackState by viewModel.servicePlaybackState.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val exportProgress by viewModel.exportProgress.collectAsState()
    val exportMessage by viewModel.exportStatusMessage.collectAsState()
    val documentTitle by viewModel.importedDocumentTitle.collectAsState()
    val chapters by viewModel.documentChapters.collectAsState()
    val isImporting by viewModel.isImportingDocument.collectAsState()

    var showLangMenu by remember { mutableStateOf(false) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // Document Picker launcher for TXT, PDF, EPUB
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
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Action Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language Selector Button
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

                // Document Import, Paste & Clear Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                val pasteText = clip.getItemAt(0).text?.toString() ?: ""
                                if (pasteText.isNotBlank()) {
                                    viewModel.onTextChange(pasteText)
                                }
                            }
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = ObsidianCard),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Вставить", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }

                    FilledTonalButton(
                        onClick = { viewModel.onTextChange("") },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = ObsidianCard),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Document Title & Chapters (if imported)
            if (documentTitle != null || isImporting) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard.copy(alpha = 0.8f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = AmberWave, modifier = Modifier.size(18.dp))
                            Text(
                                text = if (isImporting) "Извлечение текста из документа..." else (documentTitle ?: "Документ"),
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (chapters.size > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Главы книги:", fontSize = 11.sp, color = TextSecondary)
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(chapters) { chapter ->
                                    AssistChip(
                                        onClick = { viewModel.selectChapter(chapter) },
                                        label = { Text(chapter.title, fontSize = 11.sp) },
                                        colors = AssistChipDefaults.assistChipColors(
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

            // Text Editor Card with sentence highlight
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 320.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (playbackState.status == PlaybackStatus.PLAYING && playbackState.currentSentenceEnd > playbackState.currentSentenceStart) {
                        // Highlighted Reading View
                        val annotated = remember(text, playbackState.currentSentenceStart, playbackState.currentSentenceEnd) {
                            buildAnnotatedString {
                                val start = playbackState.currentSentenceStart.coerceIn(0, text.length)
                                val end = playbackState.currentSentenceEnd.coerceIn(0, text.length)
                                append(text.substring(0, start))
                                pushStyle(SpanStyle(background = CoralPrimary.copy(alpha = 0.35f), color = TextPrimary, fontWeight = FontWeight.Bold))
                                append(text.substring(start, end))
                                pop()
                                append(text.substring(end))
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = annotated,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextPrimary,
                                lineHeight = 24.sp
                            )
                        }
                    } else {
                        // Editable text field
                        OutlinedTextField(
                            value = text,
                            onValueChange = { viewModel.onTextChange(it) },
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("text_input_field"),
                            placeholder = { Text("Введите текст или выберите файл книги (TXT, PDF, EPUB)...", color = TextSecondary) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp)
                        )
                    }

                    // Bottom character counter
                    Text(
                        text = "${text.length} симв. · ${text.split("\\s+".toRegex()).count { it.isNotBlank() }} слов",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                    )
                }
            }

            // Voice, Quality Steps and Speed Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Voice Style Picker
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
                        listOf("F1", "F2", "M1", "M2").forEach { v ->
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

                // Playback Speed (Handled entirely in AudioTrack player - 0% audio truncation!)
                OutlinedCard(
                    modifier = Modifier.weight(1.2f),
                    colors = CardDefaults.outlinedCardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(ObsidianElevated))
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
                        Text("Скорость (плеер)", fontSize = 10.sp, color = TextSecondary)
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

            // Interactive Seekable Timeline & Control Bar
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Timeline Scrubber
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Фраза ${playbackState.currentChunkIndex + 1} из ${maxOf(1, playbackState.totalChunks)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CoralPrimary
                        )
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

                    // Timeline Step & Playback Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Jump -15s
                        IconButton(
                            onClick = { viewModel.jumpSeconds(-15) },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = "-15 сек", tint = TextSecondary)
                        }

                        // Step Prev Chunk
                        IconButton(
                            onClick = { viewModel.skipPrevious() },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Предыдущая фраза", tint = TextPrimary)
                        }

                        // Primary Play / Pause Button
                        Button(
                            onClick = { viewModel.togglePlayPause() },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("play_pause_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = CoralPrimary),
                            shape = CircleShape
                        ) {
                            val icon = when (playbackState.status) {
                                PlaybackStatus.PLAYING -> Icons.Default.Pause
                                PlaybackStatus.BUFFERING -> Icons.Default.HourglassTop
                                else -> Icons.Default.PlayArrow
                            }
                            Icon(icon, contentDescription = "Play/Pause", tint = ObsidianBackground, modifier = Modifier.size(24.dp))
                        }

                        // Step Next Chunk
                        IconButton(
                            onClick = { viewModel.skipNext() },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Следующая фраза", tint = TextPrimary)
                        }

                        // Jump +15s
                        IconButton(
                            onClick = { viewModel.jumpSeconds(15) },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = "+15 сек", tint = TextSecondary)
                        }

                        // Stop
                        IconButton(
                            onClick = { viewModel.stopPlayback() },
                            modifier = Modifier.size(42.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Стоп", tint = ErrorRed)
                        }
                    }

                    if (playbackState.currentSentenceText.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = playbackState.currentSentenceText,
                            fontSize = 12.sp,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Decoupled Background File Creation & External Player Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Background Batch File Creation Button
                Button(
                    onClick = { showExportDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null, tint = CoralPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Создать файл в фоне", color = TextPrimary, fontSize = 13.sp)
                }

                // External Player Button (e.g. VLC)
                FilledTonalButton(
                    onClick = {
                        viewModel.exportAndOpenInVlc(context, "m4a")
                    },
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Launch, contentDescription = null, tint = MintVoice, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Открыть в VLC", color = MintVoice, fontSize = 13.sp)
                }
            }

            // Export / Batch Progress
            AnimatedVisibility(visible = isExporting) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = ObsidianElevated)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Фоновое создание аудиофайла...", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("${(exportProgress * 100).toInt()}%", color = CoralPrimary, fontSize = 13.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
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
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MintVoice)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(exportMessage ?: "", color = MintVoice, fontSize = 13.sp)
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
                            "Создание аудиофайла выполняется независимо на максимальной скорости CPU без задержек воспроизведения:",
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
    }
}

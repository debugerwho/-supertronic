package com.example.supertonic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.supertonic.engine.ModelDownloadState
import com.example.supertonic.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun ModelSetupScreen(viewModel: MainViewModel) {
    val downloadState by viewModel.downloadState.collectAsState()
    val totalContentLengthBytes by viewModel.modelManager.totalContentLengthBytes.collectAsState()
    val verificationList = viewModel.modelManager.getFilesVerificationList()

    val sizeText = totalContentLengthBytes?.let { bytes ->
        "~${(bytes + 500000L) / (1024 * 1024)} МБ"
    } ?: "неизвестно"

    Scaffold(
        containerColor = ObsidianBackground
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                // Hero Icon
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(CoralPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Синтезатор речи",
                        tint = CoralPrimary,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Загрузка Supertonic 3",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Для 100% автономного синтеза без интернета требуются веса модели Supertone Supertonic 3 ($sizeText).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )
            }

            // Download Status Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        when (val state = downloadState) {
                            is ModelDownloadState.Downloading -> {
                                Text(
                                    text = "Загрузка: ${state.currentFileName}",
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    fontSize = 15.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { state.progressPercentage },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = CoralPrimary,
                                    trackColor = ObsidianElevated
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val downloadedMb = state.downloadedBytes / (1024 * 1024)
                                    val totalMb = state.totalBytes / (1024 * 1024)
                                    val speedKb = state.speedBytesPerSec / 1024
                                    Text(
                                        text = "$downloadedMb / $totalMb МБ (${(state.progressPercentage * 100).toInt()}%)",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "$speedKb КБ/с · ост. ${state.etaSeconds}с",
                                        color = TextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                OutlinedButton(
                                    onClick = { viewModel.cancelModelDownload() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Отмена")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Отменить")
                                }
                            }

                            is ModelDownloadState.Error -> {
                                Text(
                                    text = state.message,
                                    color = ErrorRed,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { viewModel.startModelDownload() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("retry_download_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = CoralPrimary)
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = "Повторить")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Повторить загрузку")
                                }
                            }

                            else -> {
                                Text(
                                    text = "Официальные веса Supertone ONNX",
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Все 4 компонента (duration, text_encoder, vector_estimator, vocoder) и выразительные голоса загружаются напрямую с HuggingFace в приватное хранилище приложения.",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { viewModel.startModelDownload() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("start_download_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = CoralPrimary),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = "Загрузить")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Загрузить модель ($sizeText)", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Files Checklist
            item {
                Text(
                    text = "Файлы конфигурации и весов:",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            items(verificationList) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = item.fileName,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            val mb = item.expectedSizeBytes / (1024 * 1024.0)
                            Text(
                                text = if (mb >= 1.0) String.format("%.1f МБ", mb) else "${item.expectedSizeBytes / 1024} КБ",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        if (item.isPresent) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Загружено",
                                tint = MintVoice,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = "Ожидание",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

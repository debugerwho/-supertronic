package com.example.supertonic.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.supertonic.engine.CpuPreset
import com.example.supertonic.ui.MainViewModel
import com.example.ui.theme.*

@Composable
fun DiagnosticScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val isCalibrating by viewModel.isCalibrating.collectAsState()
    val calibProgress by viewModel.calibrationProgress.collectAsState()
    val report by viewModel.calibrationReport.collectAsState()
    val overlayEnabled by viewModel.overlayEnabled.collectAsState()
    val playbackState by viewModel.servicePlaybackState.collectAsState()
    val cores by viewModel.availableCores.collectAsState()

    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = ObsidianBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Настройки и диагностика",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )

            // 1. Device Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = CoralPrimary)
                        Text("Устройство и среда", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    val cpuCores = Runtime.getRuntime().availableProcessors()
                    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                    val device = "${Build.MANUFACTURER} ${Build.MODEL}"

                    DiagnosticRow("Модель смартфона", device)
                    DiagnosticRow("Архитектура (ABI)", abi)
                    DiagnosticRow("Число ядер CPU", "$cpuCores ядер")
                    DiagnosticRow("Версия Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    DiagnosticRow("Потоки инференса (engine.currentThreads)", "${viewModel.currentEngineThreads}")
                    DiagnosticRow("Текущий RTF синтеза", String.format("%.2f", playbackState.rtf))
                    DiagnosticRow("Счетчик опустошений (Underruns)", "${playbackState.underruns}")
                }
            }

            // 2. Inference Threads Card (formerly Core Affinity)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.DeveloperBoard, contentDescription = null, tint = CoralPrimary)
                        Text("Потоки инференса", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Фактическое число рабочих потоков ONNX: ${viewModel.currentEngineThreads}. Выбор чекбоксов влияет на число потоков; привязка к ядрам в этой сборке не реализована.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Preset buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyCpuPreset(CpuPreset.ALL_CORES) },
                            label = { Text("Все потоки", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = TextPrimary,
                                containerColor = ObsidianElevated
                            )
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyCpuPreset(CpuPreset.PERFORMANCE_ONLY) },
                            label = { Text("2 потока", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = CoralPrimary,
                                containerColor = ObsidianElevated
                            )
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyCpuPreset(CpuPreset.EFFICIENCY_ONLY) },
                            label = { Text("4 потока", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = MintVoice,
                                containerColor = ObsidianElevated
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Cores / threads grid
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cores.chunked(2).forEach { rowCores ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowCores.forEach { core ->
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp)),
                                        color = if (core.isEnabled) CoralPrimary.copy(alpha = 0.2f) else ObsidianElevated
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(
                                                    text = "Поток/ядро ${core.id}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = if (core.isEnabled) TextPrimary else TextSecondary
                                                )
                                                Text(
                                                    text = if (core.isEnabled) "Включен" else "Отключен",
                                                    fontSize = 10.sp,
                                                    color = if (core.isEnabled) CoralPrimary else TextSecondary
                                                )
                                            }
                                            Checkbox(
                                                checked = core.isEnabled,
                                                onCheckedChange = { isChecked ->
                                                    viewModel.toggleCore(core.id, isChecked)
                                                },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = CoralPrimary,
                                                    uncheckedColor = TextSecondary
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    val activeCount = cores.count { it.isEnabled }
                    Text(
                        text = "Выбрано слотов: $activeCount (фактических потоков: ${viewModel.currentEngineThreads})",
                        fontSize = 12.sp,
                        color = CoralPrimary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 3. Hardware Threads Calibration Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = MintVoice)
                        Text("Калибровка потоков CPU", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Сравнивает скорость синтеза (RTF) и задержку при 2, 4 и более потоках на тестовой фразе для выбора оптимального баланса скорости и нагрева.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isCalibrating) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = calibProgress.ifEmpty { "Выполняется измерение..." },
                                color = MintVoice,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MintVoice,
                                trackColor = ObsidianElevated
                            )
                        }
                    } else {
                        Button(
                            onClick = { viewModel.runCpuCalibration() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("run_calibration_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MintVoice),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ObsidianBackground)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Запустить калибровку (тест RTF)", color = ObsidianBackground, fontWeight = FontWeight.Bold)
                        }
                    }

                    report?.let { rep ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp)),
                            color = ObsidianElevated
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Результаты калибровки",
                                    fontWeight = FontWeight.Bold,
                                    color = MintVoice,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                rep.steps.forEach { res ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            "${res.threadCount} потока(ов):",
                                            color = TextSecondary,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            "RTF: ${String.format("%.2f", res.rtf)} · ${res.synthesisTimeMs}мс",
                                            color = if (res.threadCount == rep.recommendedThreads) MintVoice else TextPrimary,
                                            fontWeight = if (res.threadCount == rep.recommendedThreads) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Рекомендация: ${rep.recommendedThreads} потока(ов) (лучший RTF ${String.format("%.2f", rep.bestRtf)})",
                                    fontSize = 12.sp,
                                    color = CoralPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // 4. Floating Overlay Permission & Toggle Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.PictureInPicture, contentDescription = null, tint = CoralPrimary)
                        Text("Плавающая панель поверх всех окон", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Позволяет управлять синтезом, переключать предложения и следить за текстом поверх браузера или читалки.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val canDraw = Settings.canDrawOverlays(context)

                    if (!canDraw) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralPrimary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Выдать разрешение поверх окон")
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Показывать мини-панель при старте",
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                            Switch(
                                checked = overlayEnabled,
                                onCheckedChange = { viewModel.setOverlayEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CoralPrimary,
                                    checkedTrackColor = CoralPrimary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }
            }

            // 5. Model Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Supertonic 3 Architecture", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "• Архитектура: G2P-free Flow Matching + ConvNeXt + Vocoder\n" +
                                "• Runtime: ONNX Runtime Android (JNI C++ CPU backend)\n" +
                                "• Локальность: 100% офлайн, нулевая телеметрия, аудио в памяти (RAM), текст не передается в сеть.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

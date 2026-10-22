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

    var cacheSizeBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        cacheSizeBytes = calculatePcmCacheSize(context)
    }

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

                    val cores = Runtime.getRuntime().availableProcessors()
                    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                    val device = "${Build.MANUFACTURER} ${Build.MODEL}"

                    DiagnosticRow("Модель смартфона", device)
                    DiagnosticRow("Архитектура (ABI)", abi)
                    DiagnosticRow("Число ядер CPU", "$cores ядер")
                    DiagnosticRow("Версия Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    DiagnosticRow("Текущий RTF синтеза", String.format("%.2f", playbackState.rtf))
                    DiagnosticRow("Счетчик опустошений (Underruns)", "${playbackState.underruns}")
                }
            }

            // 2. CPU Core Affinity Card (Winlator-style 8-core selector)
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
                        Text("Привязка ядер CPU (Core Affinity)", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Управление маской ядер для инференса. Для Helio G99: ядра 6-7 (Cortex-A76 Big) дают максимальную скорость, ядра 0-5 (Cortex-A55 Little) — энергосбережение.",
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
                            label = { Text("Все ядра", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = TextPrimary,
                                containerColor = ObsidianElevated
                            )
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyCpuPreset(CpuPreset.PERFORMANCE_ONLY) },
                            label = { Text("Big Cores (6-7)", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = CoralPrimary,
                                containerColor = ObsidianElevated
                            )
                        )
                        FilterChip(
                            selected = false,
                            onClick = { viewModel.applyCpuPreset(CpuPreset.EFFICIENCY_ONLY) },
                            label = { Text("Little (0-5)", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = MintVoice,
                                containerColor = ObsidianElevated
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 8-core grid
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
                                        color = if (core.isEnabled) {
                                            if (core.isPerformanceCore) CoralPrimary.copy(alpha = 0.2f) else MintVoice.copy(alpha = 0.2f)
                                        } else ObsidianElevated
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
                                                    text = "Ядро ${core.id}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = if (core.isEnabled) TextPrimary else TextSecondary
                                                )
                                                Text(
                                                    text = if (core.isPerformanceCore) "Big A76" else "Little A55",
                                                    fontSize = 10.sp,
                                                    color = if (core.isPerformanceCore) CoralPrimary else MintVoice
                                                )
                                            }
                                            Checkbox(
                                                checked = core.isEnabled,
                                                onCheckedChange = { isChecked ->
                                                    viewModel.toggleCore(core.id, isChecked)
                                                },
                                                colors = CheckboxDefaults.colors(
                                                    checkedColor = if (core.isPerformanceCore) CoralPrimary else MintVoice,
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
                        text = "Активно ядер для ONNX: $activeCount из ${cores.size}",
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
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MintVoice,
                            trackColor = ObsidianElevated
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(calibProgress, color = TextSecondary, fontSize = 12.sp)
                    } else {
                        Button(
                            onClick = { viewModel.runCpuCalibration() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("calibrate_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MintVoice),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = ObsidianBackground)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Запустить калибровку CPU", color = ObsidianBackground, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Calibration report table
                    report?.let { rep ->
                        Spacer(modifier = Modifier.height(14.dp))
                        Divider(color = ObsidianElevated)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Результаты калибровки (рекомендуется: ${rep.recommendedThreads} потока, RTF ${String.format("%.2f", rep.bestRtf)}):",
                            fontWeight = FontWeight.SemiBold,
                            color = CoralPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        rep.steps.forEach { step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${step.threadCount} потока(ов)", color = TextPrimary, fontSize = 12.sp)
                                Text("RTF: ${String.format("%.2f", step.rtf)}", color = MintVoice, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text("${step.synthesisTimeMs} мс", color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // 3. Floating Overlay Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = ObsidianCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Плавающая панель", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                            Text(
                                "Отображает компактную панель поверх других приложений при выделении текста",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Switch(
                            checked = overlayEnabled,
                            onCheckedChange = { checked ->
                                if (checked && !Settings.canDrawOverlays(context)) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                                viewModel.overlayEnabled.value = checked
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CoralPrimary,
                                checkedTrackColor = CoralPrimary.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = ObsidianElevated
                            )
                        )
                    }
                }
            }

            // 4. Cache Management Card
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
                        Icon(Icons.Default.Storage, contentDescription = null, tint = AmberWave)
                        Text("Кэш синтезированного аудио", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Позволяет мгновенно возобновлять повторное чтение уже озвученных предложений без повторного использования CPU.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            viewModel.clearCache()
                            cacheSizeBytes = 0L
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = TextSecondary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Очистить кэш PCM")
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
                        "• Версия весов: HuggingFace snapshot aafc6e32416a594460b32413efc49d7fe4ce6d46\n" +
                                "• Архитектура: G2P-free Flow Matching + ConvNeXt + Vocoder\n" +
                                "• Runtime: ONNX Runtime Android (JNI C++ CPU backend)\n" +
                                "• Локальность: 100% офлайн, нулевая телеметрия, текст не передается в сеть.",
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

private fun calculatePcmCacheSize(context: android.content.Context): Long {
    val dir = java.io.File(context.cacheDir, "pcm_cache")
    val files: Array<java.io.File>? = dir.listFiles()
    return files?.sumOf { it.length() } ?: 0L
}

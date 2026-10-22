package com.example.supertonic.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.supertonic.ui.MainViewModel
import com.example.supertonic.ui.SavedAudioTrack
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val tracks by viewModel.savedTracks.collectAsState()
    val playingTrack by viewModel.currentlyPlayingTrack.collectAsState()

    Scaffold(
        containerColor = ObsidianBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Аудиотека",
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${tracks.size} файлов",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (tracks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(ObsidianCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Text(
                            text = "Пока нет сохраненных записей",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = "Синтезируйте любой текст на вкладке «Читать» и нажмите «Экспорт»",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 32.dp),
                            lineHeight = 20.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tracks) { track ->
                        val isCurrent = playingTrack?.file == track.file
                        TrackItemCard(
                            track = track,
                            isPlaying = isCurrent,
                            onPlayToggle = { viewModel.playTrack(track) },
                            onOpenExternal = { viewModel.openInExternalPlayer(track.file, context) },
                            onShare = {
                                try {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        track.file
                                    )
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = if (track.format == "M4A") "audio/mp4" else "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Поделиться аудиозаписью"))
                                } catch (e: Exception) {
                                    android.util.Log.e("Library", "Share error", e)
                                }
                            },
                            onDelete = { viewModel.deleteTrack(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TrackItemCard(
    track: SavedAudioTrack,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlaying) ObsidianElevated else ObsidianCard
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play / Pause Icon Button
            IconButton(
                onClick = onPlayToggle,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) CoralPrimary else ObsidianElevated)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Остановить" else "Слушать",
                    tint = if (isPlaying) ObsidianBackground else TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Track details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Format badge
                    Text(
                        text = track.format,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (track.format == "M4A") MintVoice else AmberWave,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                (if (track.format == "M4A") MintVoice else AmberWave).copy(alpha = 0.15f)
                            )
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )

                    val mb = track.sizeBytes / (1024 * 1024.0)
                    val sizeStr = if (mb >= 1.0) String.format("%.2f МБ", mb) else "${track.sizeBytes / 1024} КБ"
                    val dateStr = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault()).format(Date(track.dateModified))
                    Text(
                        text = "$sizeStr · $dateStr",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            // Open in VLC button
            IconButton(onClick = onOpenExternal, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Launch, contentDescription = "Открыть в VLC", tint = MintVoice, modifier = Modifier.size(18.dp))
            }

            // Share button
            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = TextSecondary, modifier = Modifier.size(18.dp))
            }

            // Delete button
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить", tint = ErrorRed.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

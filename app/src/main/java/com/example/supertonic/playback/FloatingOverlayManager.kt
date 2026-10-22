package com.example.supertonic.playback

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.ui.theme.CoralPrimary
import com.example.ui.theme.MintVoice
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Isolated LifecycleOwner, SavedStateRegistryOwner, and ViewModelStoreOwner for Overlay ComposeView
 */
class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun onCreate() {
        savedStateRegistryController.performRestore(Bundle())
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onStart() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

class FloatingOverlayManager(private val context: Context, private val service: SupertonicPlaybackService) {
    private val tag = "FloatingOverlayManager"
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @Volatile
    var isShowing = false
        private set

    fun canShowOverlay(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    @SuppressLint("RtlHardcoded")
    fun show() {
        if (isShowing || !canShowOverlay()) return

        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 60
                y = 280
            }
            layoutParams = params

            val owner = OverlayLifecycleOwner().apply {
                onCreate()
                onStart()
            }
            lifecycleOwner = owner

            val composeView = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)

                setContent {
                    FloatingPillContent(
                        service = service,
                        onDrag = { dx, dy ->
                            params.x += dx.toInt()
                            params.y += dy.toInt()
                            try {
                                windowManager.updateViewLayout(this, params)
                            } catch (ignored: Exception) {}
                        },
                        onOpenApp = {
                            val intent = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            context.startActivity(intent)
                        },
                        onCloseOverlay = {
                            hide()
                        }
                    )
                }
            }

            windowManager.addView(composeView, params)
            overlayView = composeView
            isShowing = true
            Log.i(tag, "Floating overlay attached successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to create floating overlay", e)
            hide()
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            overlayView?.let { view ->
                view.disposeComposition()
                windowManager.removeViewImmediate(view)
            }
        } catch (e: Exception) {
            Log.w(tag, "Error removing floating overlay", e)
        } finally {
            overlayView = null
            lifecycleOwner?.onDestroy()
            lifecycleOwner = null
            isShowing = false
        }
    }
}

@Composable
fun FloatingPillContent(
    service: SupertonicPlaybackService,
    onDrag: (Float, Float) -> Unit,
    onOpenApp: () -> Unit,
    onCloseOverlay: () -> Unit
) {
    val playbackState by service.playbackState.collectAsState()
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .wrapContentSize()
            .padding(8.dp)
            .shadow(12.dp, shape = RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        color = ObsidianCard,
        contentColor = TextPrimary,
        tonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Main Compact Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when (playbackState.status) {
                                PlaybackStatus.PLAYING -> MintVoice
                                PlaybackStatus.BUFFERING -> CoralPrimary
                                PlaybackStatus.PAUSED -> Color(0xFFFBBF24)
                                else -> TextSecondary
                            }
                        )
                )

                // Current sentence / progress
                Column(modifier = Modifier.widthIn(min = 90.dp, max = 150.dp)) {
                    Text(
                        text = if (playbackState.status == PlaybackStatus.BUFFERING) "Буферизация..." else "${playbackState.currentChunkIndex + 1} / ${maxOf(1, playbackState.totalChunks)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = playbackState.currentSentenceText.ifBlank { "Supertonic 3" },
                        fontSize = 10.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Play / Pause Button
                IconButton(
                    onClick = {
                        if (playbackState.status == PlaybackStatus.PLAYING) {
                            service.pause()
                        } else {
                            service.resume()
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (playbackState.status == PlaybackStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Воспроизведение/Пауза",
                        tint = CoralPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Expand / Collapse details
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Подробнее",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Open full app
                IconButton(
                    onClick = onOpenApp,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Открыть приложение",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Expanded controls
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Divider(color = ObsidianElevated, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { service.skipPrevious() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Назад", tint = TextPrimary)
                        }

                        IconButton(
                            onClick = { service.stop() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Стоп", tint = Color(0xFFEF4444))
                        }

                        IconButton(
                            onClick = { service.skipNext() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Вперед", tint = TextPrimary)
                        }

                        IconButton(
                            onClick = onCloseOverlay,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Скрыть панель", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Запас: ${String.format("%.1f", playbackState.bufferedSeconds)}с | RTF: ${String.format("%.2f", playbackState.rtf)}",
                        fontSize = 10.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }
}

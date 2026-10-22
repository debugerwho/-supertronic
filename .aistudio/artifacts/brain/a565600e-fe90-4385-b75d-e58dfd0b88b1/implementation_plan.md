# Supertonic Reader — Advanced Audio Pipeline & Document Suite

Comprehensive architectural upgrade for Supertonic Reader addressing speech truncation at accelerated speeds, introducing an interactive seekable timeline with non-linear forward/backward priority synthesis, decoupled background audio file generation, external player routing (VLC/system), multi-format document import (TXT, PDF, EPUB), and CPU core affinity management (Cores 0–7).

## User Review & Critical Decisions

> [!IMPORTANT]
> Key architectural decisions confirmed during the clarification turn:

- **Confirmed Decision 1 (Zero-Loss Speed Control)**: Speed adjustment is completely decoupled from neural synthesis. The model always synthesizes at natural 1.0x rate without truncating vocoder samples, and speed (0.8x – 2.0x) is modulated non-destructively in `AudioTrack` via `PlaybackParams` / time-stretching.
- **Confirmed Decision 2 (Interactive Timeline & Non-Linear Seek)**: A bidirectional interactive timeline scrubber where backward scrub plays cached/spooled audio instantly, and forward scrub dynamically jumps the synthesis queue to the targeted chunk, continuing sequential synthesis from the new cursor. Direct sentence step buttons (`-1` / `+1`) and "Open in external player (VLC)" are provided.
- **Confirmed Decision 3 (Document Import)**: Built-in zero-dependency extractors for TXT, PDF (page-by-page extraction), and EPUB (ZIP + XHTML chapter parser) via Android Storage Access Framework.
- **Confirmed Decision 4 (Decoupled Background Synthesis & Playback)**: Batch audio file generation runs at uncapped CPU speed without requiring real-time playback pacing, and playing existing audio files never interrupts active text synthesis.
- **Confirmed Decision 5 (CPU Core Affinity Selection)**: An interactive core selector (Cores 0–7) allowing users to assign synthesis threads to specific cores (e.g. Big Cores 6–7 vs Little Cores 0–5 on Helio G99), similar to Winlator.

---

## 1. Overview & Core Concept

- **Problem Addressed**:
  1. Accelerated speech (1.3x) truncated vocoder samples due to artificial duration slicing, causing swallowed words and missing short phrases.
  2. Inability to seek through text during synthesis or jump ahead to un-synthesized sections.
  3. Real-time inference was entangled with playback and file export, causing mutual cancellations.
  4. Lack of direct book/document reading (PDF, EPUB, TXT).
  5. Inability to target specific CPU cluster cores on big.LITTLE architectures.
- **Deliverables**:
  - Full-fidelity vocoder sample preservation with zero truncation.
  - Interactive scrubbing timeline with chunk status indicators (Cached / Synthesizing / Queued).
  - Independent batch file generator + external player stream/export (`VLC`, etc.).
  - Document parser supporting `.txt`, `.pdf`, `.epub`.
  - CPU Core Mask affinity controller (Cores 0–7).

---

## 2. User Experience & Visual Design

### Key User Flows

1. **Document Import Flow**:
   - In Reader tab, tap "Импорт книги / документа" (`.txt`, `.pdf`, `.epub`).
   - App parses text/chapters with progress, displays chapter titles or total word count, and loads cleanly into the reader editor.
2. **Interactive Timeline & Non-Linear Scrubbing**:
   - Scrubber slider along the bottom of the reader displaying current sentence timecode and total progress.
   - User drags slider backward: Audio immediately seeks and plays from the already synthesized buffer/cache.
   - User drags slider forward: Audio player waits briefly in buffering state while synthesis worker aborts the old tail and starts immediately at the chosen chunk index.
   - Quick jump buttons: `[ -15 сек ]`, `[ Предыдущая фраза ]`, `[ Следующая фраза ]`, `[ +15 сек ]`.
3. **Decoupled Background Export & External Player**:
   - "Создать аудиофайл в фоне": runs fast-as-possible batch synthesis without audio track pacing.
   - "Открыть в стороннем плеере": fires `ACTION_VIEW` intent with `audio/*` and `FLAG_GRANT_READ_URI_PERMISSION` for playback in VLC, Poweramp, or system media player.
4. **CPU Core Affinity Settings**:
   - Visual 8-core diagram in Settings (Cores 0 to 7) with Cortex-A76 (Big) and Cortex-A55 (Little) tags.
   - Presets: "Все ядра (0-7)", "Производительные (6-7)", "Энергосберегающие (0-5)", "Пользовательские".

---

## 3. Technical Architecture & Component Mapping

### System Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SUPERTONIC READER SUITE                         │
│                                                                        │
│   ┌────────────────────┐   ┌────────────────────┐   ┌────────────────┐ │
│   │  Document Parser   │   │ Interactive Player │   │ CPU Affinity   │ │
│   │ (TXT, PDF, EPUB)   │   │ Timeline & Scrubber│   │ Cores (0..7)   │ │
│   └─────────┬──────────┘   └─────────┬──────────┘   └───────┬────────┘ │
│             │                        │                      │          │
│             ▼                        ▼                      │          │
│     Normalized Text          PlaybackSessionManager         │          │
│                                  │           │              │          │
│                 ┌────────────────┘           └────────┐     ▼          │
│                 ▼                                     ▼   sched_set    │
│      SynthesisWorker (Prioritized)             AudioTrackPlayer        │
│          ▲               │                   (Time-stretch 0.8-2.0x)   │
│          │               ▼                            ▲                │
│    SupertonicEngine  Bounded Channel<PcmChunk>        │                │
│   (Pristine 1.0x)    & Spool Cache ───────────────────┘                │
│                                                                        │
│   ┌───────────────────────────┐         ┌──────────────────────────┐   │
│   │ Independent Batch Creator │         │ External Player Launcher │   │
│   │ (Direct M4A / WAV to Disk)│         │ (VLC / System via SAF)   │   │
│   └───────────────────────────┘         └──────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

### Detailed Component Implementation Plan

1. **Fixing Audio Slicing (`SupertonicEngine.kt`)**:
   - Remove `targetSampleCount = min(rawSamples.size, (duration * sampleRate).toInt())` truncation when `speed > 1.0`.
   - Take the full `rawSamples` output from `vocoder.onnx` to preserve final consonant decays, trailing punctuation pauses, and full phrase endings.
   - Apply a small 50ms smooth linear fade-out on the very last samples to prevent pop artifacts while ensuring 100% of the speech signal is intact.
   - Pass synthesized audio to `AudioTrackPlayer` which handles playback rate via `PlaybackParams.setSpeed(speed)` with zero sample loss.

2. **Interactive Timeline & Non-Linear Queue (`SupertonicPlaybackService.kt`)**:
   - Maintain a list of chunk synthesis statuses: `enum class ChunkStatus { PENDING, SYNTHESIZING, READY, PLAYING }`.
   - Maintain a ring cache of synthesized PCM chunks indexed by chunk index.
   - Add `seekToChunk(targetIndex: Int)`:
     - If chunk is `READY`, immediately flush AudioTrack and feed from `targetIndex`.
     - If chunk is `PENDING`, cancel current synthesis job and restart synthesis queue starting at `targetIndex`.
   - Expose current playback position (seconds & chunk index) and estimated total duration.

3. **Multi-Format Document Parser (`DocumentParser.kt`)**:
   - **TXT**: Read via buffered UTF-8 reader with automatic UTF-16/Windows-1251 fallback.
   - **PDF**: Uses Android standard `android.graphics.pdf.PdfRenderer` and lightweight text stream scanner, extracting text page-by-page.
   - **EPUB**: Uses standard `java.util.zip.ZipInputStream` to parse `META-INF/container.xml`, locate `.opf`, extract the spine order, read XHTML chapters, and strip tags cleanly to text.

4. **Independent Batch Audio Generator (`BatchExporter.kt`)**:
   - Runs in a dedicated background coroutine without holding audio focus or playing sound.
   - Synthesizes all chunks sequentially at maximum CPU throughput.
   - Streams directly into `MediaCodecExporter.encodePcmToM4a` or `convertPcmToWav`.
   - Emits independent progress (`exportProgress`) and writes to library.

5. **External Player Launch (`ExternalPlayerLauncher.kt`)**:
   - Exports the current session spool to a temporary `.m4a` / `.wav` file.
   - Generates a secure `FileProvider` URI and launches `Intent(Intent.ACTION_VIEW)` with MIME `audio/*`.
   - Shows app chooser or directs straight to VLC if installed.

6. **CPU Core Affinity Mask (`CpuAffinityManager.kt`)**:
   - Queries `/sys/devices/system/cpu/present` and `/sys/devices/system/cpu/cpu*/topology/` or `Runtime.getRuntime().availableProcessors()`.
   - Allows user to set affinity mask bitfield (e.g. `0b11000000` for cores 6-7, or `0b11111111` for all cores) and applies it to ONNX Runtime worker threads and execution options.

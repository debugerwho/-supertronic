# Проверка сессии 2 (AI Studio) — код-ревью `cdbb498`

Дата: 2026-10-22
Проверяемый коммит: `cdbb498` («refactor: optimize model management and engine host»), родитель `743a720` (сессия 1).
Метод: статический разбор диффа (`git archive` → `/tmp/s2`), сверка с ТЗ и с `docs/AI_STUDIO_MASTER_2026-10-22.md`. Сборка в песочнице невозможна (нет JDK/SDK/wrapper jar) — **всё ниже получено чтением кода, а не запуском**.

---

## 1. Короткий вердикт

Сессия 2 **не завершена**, но и **не откатывается**: реально сделана бо́льшая часть архитектурного ядра (слой `audio/`, `EngineHost`, `SourceIndex`), и 5 из 5 замечаний «БЛОК 0» закрыты. При этом в новом коде есть **два блокирующих дефекта** — один в часовом механизме (после любого seek позиция уезжает на часы вперёд), второй в UI (тап по абзацу из состояния IDLE всегда начинает с начала) — плюс 263 строки мёртвого кода и невыполненный пункт про экспорт.

| Вопрос | Ответ |
|---|---|
| Можно ли закрыть сессию 2 как выполненную? | **Нет.** Нужна короткая сессия 2.1 (патч-задание, ~4 правки) |
| Нужно ли откатывать `cdbb498`? | **Нет.** Направление верное, дефекты локальные |
| Можно ли переходить к P3 (экспорт/языки/CPU)? | **Только после 2.1.** P3 начнётся с 3-фазного экспорта, а он должен опираться на рабочий `SourceIndex`/часы |
| Соответствует ли объём отчёту AI Studio? | Частично: архитектура — да, но «готово» по таймлайну/сику/экспорту — **нет** |

Прогресс относительно 4 промптов: P2 выполнен примерно на 60–70 % (слой RAM-аудио, планировщик, часы — есть; корректность сика, экспорт, обновление `AI_STUDIO_STATE.md` — нет).

---

## 2. Что действительно сделано (подтверждено кодом)

| Пункт ТЗ | Факт | Где |
|---|---|---|
| RAM-only промежуточное аудио, off-heap блоками | `PcmStore` на `ByteBuffer.allocateDirect` блоками 4 МБ, LRU-вытеснение блоков, счётчики hit/miss | `audio/PcmStore.kt:24-194` |
| Окно удержания 45 с назад / 10 мин вперёд | `PcmRing.WINDOW_BACK_SEC/WINDOW_FORWARD_SEC`, `calculateWindowIndices`, `advance` | `audio/PcmRing.kt:11-16,41-78` |
| Удаление старого плеера | `playback/AudioTrackPlayer.kt` удалён (−192), вместо него `audio/AudioTrackSink.kt` (1 `AudioTrack`, запись слайсами 100 мс = 8820 Б, `PlaybackParams` без изменения питча) | `audio/AudioTrackSink.kt:1-200` |
| Приоритеты синтеза P0–P4 | `SynthesisScheduler`: P0 текущий, P1 +3, P2 окно 10 мин, P3 цель сика, P4 остаток; счётчик поколений гасит устаревшие задачи; критическая ошибка прекращает очередь | `audio/SynthesisScheduler.kt:40-160` |
| Один экземпляр движка на процесс | `object EngineHost` с `Mutex`: `load`, `withEngine{}` (мьютекс удерживается на всё время инференса), `isLoaded/currentThreads/voiceStyles` как `StateFlow` | `engine/EngineHost.kt:13-52` |
| Честная многопоточность | `PerformanceCalibrator` грузит движок через `EngineHost.load(numThreads)`, прогон через `withEngine`, реальные `rtf/durationSec` без эвристик | `engine/PerformanceCalibrator.kt:78-105` |
| Таймлайн в сэмплах | `SourceIndex`: спаны `sampleStart/sampleLength`, `findChunkIndexBySample`, `findChunkIndexByParagraph`, `formatTimeline` c «≈» для оценки | `core/SourceIndex.kt:1-125` |
| Длительность без эвристик 1.05/0.18 | `duration = rawDuration / speed.coerceIn(0.2f, 3.0f)`, параметр `speed` реально используется | `engine/OnnxSpeechEngine.kt:205-215` |
| Шум без `Random()` на чанк | `ThreadLocalRandom.current().nextGaussian()` | `engine/OnnxSpeechEngine.kt` (зона `noisy_latent`) |
| Fade ≤ 3 мс на стыках | `maxFadeSamples = sampleRate*0.003`, мин(¼ длины) | `engine/OnnxSpeechEngine.kt:299-313` |
| Аудиофокус | `requestAudioFocus` (SPEECH/MEDIA), `AUDIOFOCUS_LOSS*` → `pause()`, автовозобновления нет, `abandonAudioFocus()` в `stop()`, при завершении и в `onDestroy` | `playback/SupertonicPlaybackService.kt:144-149,700-725,503,651,831` |
| `googleServices.missing.passthrough` | удалён из `gradle.properties` | `gradle.properties` |
| Проверка размера модели без «вечного HEAD» | кэш размеров в `SharedPreferences` (`size_rev1:`), TTL 7 дней, in-process guard 60 с, fallback на манифест ±2 %, правило «битый» <90 % / >110 % | `engine/ModelManager.kt:59-108,187-206` |
| Защита от OOM при экспорте | предупреждающий отказ при свободной RAM < 400 МБ с советом экспортировать по главам | `SupertonicPlaybackService.kt:533-542` |
| Тесты | +93 строки: `testPcmStore_offHeapAllocationAndRetrieval`, `testPcmRing_windowRetentionAndEviction`, `testSourceIndex_sampleMappingAndFormatting`, `testWavHeaderFormat`, сегментер/языки | `app/src/test/java/com/example/SupertonicCoreTest.kt:192-284` |

Итог по «настоящим» изменениям архитектуры: 18 файлов, +1688/−487. Зависимости (`app/build.gradle.kts`, версии) **не менялись** — новых библиотек нет, всё на стеке сессии 1.

---

## 3. Блокирующие дефекты

### C1. Часы воспроизведения ломаются после любого seek / «повтора» — позиция уходит на часы вперёд

Механика:

* `AudioTrackSink.flush()` (вызывается при сике) **не обнуляет** счётчик записанных кадров, а лишь вычисляет смещение:
  `headOffsetFrames = _totalWrittenFrames.get() - currentHead` — `audio/AudioTrackSink.kt:123-134`.
* Обнуление есть только в `stop()`: `_totalWrittenFrames.set(0)`, `headOffsetFrames = 0` — `audio/AudioTrackSink.kt:137-149`.
* `PlaybackClock.currentSample = baseSampleOffset + sink.getPlayedFrames()`, а `getPlayedFrames()` возвращает `headOffsetFrames + playbackHeadPosition` — `audio/PlaybackClock.kt:14-16`, `audio/AudioTrackSink.kt:168-175`.
* `seekToChunk()` делает `sink.flush()` и затем `clock.resetToSample(it.sampleStart)` — `SupertonicPlaybackService.kt:402-405`.

После seek: `currentSample = sampleStart + (totalWrittenFrames − 0) + rawHead`. То есть к позиции прибавляется **вся накопленная за сессию длительность** (для 20 минут чтения — +1 200 с). Следствия: таймлайн и прогресс показывают мусор, `jumpSeconds()` считает от неверной точки, `formatTimeline` рисует далеко за концом, `bufferedSeconds` скачет.

Дополнительно в `seekToChunk` флаш делается **дважды** (строка 402 и внутри `resetToSample`).

Правильная семантика: `flush()` должен полностью обнулять счётчики кадров (`_totalWrittenFrames = 0`, `headOffsetFrames = 0`, `rawHead = 0`), а абсолютную позицию держать **только** в `PlaybackClock` через `baseSampleOffset`. Тогда `currentSample = baseSampleOffset + playedFramesSinceFlush`.

### C2. Тап по абзацу из IDLE всегда начинает чтение с начала

`MainViewModel.readFromParagraph()` в ветке IDLE вызывает `service.playText(... startIndex = 0 ...)`, а **сразу за ним** — `service.seekToParagraph(paragraphIndex)` — `ui/MainViewModel.kt:314-336`.

`playText()` только запускает `prepJob` (сегментация, `SourceIndex`, `pcmRing.clear()` — `SupertonicPlaybackService.kt:238-300`), поэтому в момент вызова `seekToParagraph`:
* либо `sourceIndex == null` → `targetChunk = 0` (`SupertonicPlaybackService.kt:381-383`) → seek в начало, а затем подготовка всё равно стартует с `startIndex = 0`;
* либо `sourceIndex` — от **предыдущей** сессии, и seek уводит в чужой список чанков, который тут же будет перезаписан.

Итог: заявленная в сессии 2 фича «клик по абзацу» работает только если чтение уже идёт (ветка `else`). Нужно: либо передавать `startIndex` (чанк абзаца вычислять после сегментации внутри `playText`), либо сохранять `pendingSeekParagraph` и применять его в `prepJob` после построения индекса.

---

## 4. Существенные замечания

### M1. `pause()` → `resume()` повторяет озвучку чанка (до ~20 с)
`pause()` отменяет `playbackJob` и ставит `sink.pause()`, буфер в `AudioTrack` не сбрасывается (`SupertonicPlaybackService.kt:625-631`). `resume()` вызывает `startPlaybackLoop(gen, currentChunkIndex)`, а цикл **всегда** пишет чанк с `writeOffset = 0` (`SupertonicPlaybackService.kt:422-505`). Итог: хвост буфера проигрывается ещё раз + чанк начинается заново — слышимый повтор фразы. Нужно либо `sink.flush()` при паузе с сохранением смещения внутри чанка, либо продолжение записи с сохранённого `chunkWriteOffset`.

### M2. Экспорт по-прежнему копит всё в `List<ByteArray>` — требование «3 фазы» не выполнено
`val pcmChunks = mutableListOf<ByteArray>()` … `MediaCodecExporter.encodePcmChunksToM4a(pcmChunks, targetFile)` — `SupertonicPlaybackService.kt:559-594`. Сессия 1 помечала это как M2, и оно **не изменилось**: добавлен только отказ при нехватке RAM (>400 МБ свободно), то есть длинная книга теперь честно не экспортируется, но и не экспортируется вообще. 3-фазная схема (синтез → поток в муксер → финализация) остаётся на P3.

### M3. `domain/ReadSession.kt` — 263 строки мёртвого кода
Ни одной ссылки из `app/src` (проверено `grep -rn "ReadSession"`). Класс дублирует логику сервиса и вводит **второе** состояние `ReadSessionState` с другим набором полей — прямой источник будущих расхождений. Удалить в сессии 2.1.

### M4. Живой таймлайн обновляется только на границах чанков
`timelineDisplay`, `currentSeconds`, `playbackProgress` пишутся в `_playbackState` один раз перед записью очередного чанка (`SupertonicPlaybackService.kt:449-465`), внутри записи — нет. Прогресс прыгает по фразам; для «таймлайна в сэмплах» нужен тикер (например, каждые 200–250 мс во время записи). Отдельно: `mediaSession` не объявляет `ACTION_SEEK_TO` и отдаёт `PLAYBACK_POSITION_UNKNOWN` (`SupertonicPlaybackService.kt:727-752`), `jumpSeconds()` не подключён к UI — «сик в обе стороны» есть только как тап по абзацу/скип чанка.

### M5. `PcmStore`: границы 64–256 МБ объявлены, но не применяются
`MIN_CAPACITY_BYTES` и `MAX_CAPACITY_BYTES_UPPER` не используются нигде (`audio/PcmStore.kt:29-31`), `maxCapacityBytes` — публичный `var` без клампа и без пересчёта при изменении. Заявленный в мастер-доке бюджет 128 МБ держится только на дефолте. Плюс `get()` каждый раз копирует чанк в новый `ByteArray` (`:63-88`) — аллокации в хот-пути записи.

### M6. Точное сравнение с кэшированным HEAD-размером осталось
В `checkInstalledState()` при наличии кэша используется `actualLength == cachedExpected` (`engine/ModelManager.kt:187-192`), допуск ±2 % применяется только к манифесту. Если сервер отдаёт `Content-Length`, отличающийся от реального файла (перепаковка LFS/CDN), модель снова станет «повреждённой» — только теперь навсегда в пределах TTL кэша. Нужно и к кэшу применять допуск, а лучше — сверять размер и, при расхождении, перезапрашивать HEAD один раз.

### M7. Линейный поиск абзаца на каждый чанк
`paragraphs.indexOfFirst { ... }` внутри цикла по чанкам — `core/SourceIndex.kt:38-43`. O(N·M) на книгу в 3 000 абзацев/20 000 чанков. Индекс строится один раз, но на большом файле это заметный фриз в начале чтения. Двух указателей достаточно (смещения монотонны).

---

## 5. Мелочи

* `seekToChunk` дёргает `sink.flush()` вручную и затем повторно внутри `clock.resetToSample` (`:402`, `:405`, `PlaybackClock.kt:23-26`).
* `retrySynthesis()` делает `sink.flush()` без `clock.resetToSample` — после ретрая позиция тоже неверна (`:369`).
* `PcmRing.readyIndices` поддерживается, но нигде не читается (`isReady` смотрит в `pcmStore`) — либо использовать, либо убрать.
* `Speed` в планировщике жёстко `1.0f` (`audio/SynthesisScheduler.kt:124`) — **это правильно** (синтез фиксирован, скорость — только воспроизведение через `PlaybackParams`), в отличие от отчёта это не дефект; фиксирую, чтобы в 2.1 не «починили» лишнее.
* `PcmStore.put` не проверяет `pcm.size > BLOCK_SIZE`: при чанке больше 4 МБ будет `BufferOverflowException` (для ≤250 символов недостижимо, но защита нужна).
* `EngineHost.load()` не идемпотентен (нет раннего выхода, если движок уже загружен с теми же потоками) — потенциальная повторная загрузка ~400 МБ модели по каждому входу в чтение.
* `SupertonicPlaybackService.engine` отдаёт `EngineHost.rawEngine` в обход мьютекса (`:110`) — сейчас не используется, но это готовый обход single-flight инференса; убрать.
* `AI_STUDIO_STATE.md` **не обновлён**: в `cdbb498` он всё ещё описывает состояние после сессии 1. Для следующего захода нет машиночитаемой точки старта — процессное нарушение, а не код.

---

## 6. Статус критериев приёмки (из мастер-дока)

| Критерий | Статус |
|---|---|
| Промежуточное аудио только в RAM | ✅ выполнено |
| Один экземпляр движка | ✅ `EngineHost`, но с обходом через `rawEngine` (см. мелочи) |
| `synthesisSpeed = 1.0` + воспроизведение 1/1.3/1.5/2× | ✅ |
| Честная многопоточность (без выдуманного pinning) | ✅ в пределах AI Studio (D9) |
| Шаги 2/5/10 | ✅ |
| Таймлайн в сэмплах + двусторонний seek | ⚠️ частично: индекс есть, но часы ломаются после seek (C1), прогресс не тикает (M4), нет `ACTION_SEEK_TO` |
| Отдельный экспорт в 3 фазы | ❌ не сделано (M2) |
| 31 язык + `na` | ✅ без изменений с сессии 1 |
| 100 % офлайн после загрузки модели | ⚠️ кэш HEAD исправил «вечный HEAD», но точное сравнение (M6) может снова заблокировать офлайн |
| Нет необоснованных заявлений о производительности | ⚠️ отчёт сессии 2 заявляет «готовый таймлайн/сик», проверка это не подтверждает |

---

## 7. Что делать дальше (сессия 2.1 — патч, не переписывание)

Порядок обязательный, P3 запускать после:

1. **C1**: `AudioTrackSink.flush()` → полный сброс счётчиков кадров; убрать двойной флаш в `seekToChunk`; добавить `clock.reset` в `retrySynthesis`.
2. **C2**: `playText(..., startParagraphIndex)` либо `pendingSeekParagraph`, применяемый после сегментации.
3. **M1**: корректные пауза/резюм (сохранять `chunkWriteOffset` либо флашить на паузе).
4. **M3**: удалить `domain/ReadSession.kt`.
5. **M5, M7, мелочи**: кламп `PcmStore`, защита `put`, двухуказательный поиск абзаца, удаление `rawEngine`, идемпотентный `load`.
6. **M4**: тикер позиции 250 мс + `ACTION_SEEK_TO`/реальная позиция в `MediaSessionCompat`.
7. Обновить `AI_STUDIO_STATE.md` (обязательное условие закрытия сессии).
8. Тесты: на `PlaybackClock` (сброс после seek) и на применение `pendingSeekParagraph` — без них C1/C2 вернутся.

**P2 нужно переписать перед повторным запуском**: «БЛОК 0» уже выполнен, а половина основного задания закрыта — иначе AI Studio начнёт работу заново и перезапишет рабочий `audio/`-слой.

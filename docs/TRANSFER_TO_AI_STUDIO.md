# Как перенести исправления в проект AI Studio

Короткий ответ: **да, структура проектов одна и та же**. Это один и тот же репозиторий
`debugerwho/-supertronic`; ветка `arena/c3e0cbd7-supertronic` отличается от того, что сейчас
лежит в AI Studio, **только содержимым 12 файлов и отсутствием 2 файлов**. Каталоги, ресурсы,
gradle-файлы, `metadata.json`, `.aistudio/` — совпадают байт в байт.

Проверка на коммитах (последний коммит AI Studio, известный репозиторию, — `cdbb498`):

| Категория | Кол-во | Что это |
|---|---|---|
| Совпадают полностью | 58 | `metadata.json`, `.aistudio/**`, `.env.example`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `settings.gradle.kts`, `AndroidManifest.xml`, ресурсы, `MainActivity.kt`, `DocumentParser.kt`, `LanguageDetector.kt`, `TextSegmenter.kt`, `CpuAffinityManager.kt`, `PerformanceCalibrator.kt`, `OnnxSpeechEngine.kt`, `SpeechEngine.kt`, `SynthesisScheduler.kt`, `DocumentStore.kt`, `SessionStore.kt`, экраны `Library/ModelSetup/Diagnostic`, `FloatingOverlayManager.kt`, тесты-заглушки и т. д. |
| **Изменилось содержимое** | **12** | см. таблицу ниже — это и есть все исправления |
| **Нужно удалить в AI Studio** | **2** | `domain/ReadSession.kt` (мёртвый дубль состояния, 263 строки), `playback/PcmChunk.kt` (не используется) |
| Есть только у меня | 23 | `docs/**` и `prompts/**` — мои рабочие документы, в AI Studio **не нужны** (кроме `AI_STUDIO_STATE.md`, он из списка 12) |

## 1. Что именно поменялось (12 файлов)

| Файл | Суть изменения |
|---|---|
| `audio/AudioTrackSink.kt` | `flush()` полностью сбрасывает счётчики кадров (фикс C1: позиция после seek) |
| `audio/PlaybackClock.kt` | `baseSample`, `shiftBaseBy()`; позиция = база + кадры после флаша |
| `audio/PcmStore.kt` | кламп бюджета 64–256 МБ, защита `put()` от чанка > 4 МБ, `sizeOf`/`copyInto` |
| `audio/PcmRing.kt` | `copyChunkInto`/`sizeOf`, убран неиспользуемый набор `readyIndices` |
| `core/SourceIndex.kt` | курсор вместо `indexOfFirst`, бинарный поиск, пересчёт только хвоста |
| `engine/EngineHost.kt` | идемпотентный `load()`, `rawEngine` → `internal` |
| `engine/ModelManager.kt` | допуск ±2 % и к ожидаемому из кэша, инвалидация устаревшего кэша HEAD |
| `playback/MediaCodecExporter.kt` | потоковые `openM4aWriter`/`openWavWriter` вместо `List<ByteArray>` |
| `playback/SupertonicPlaybackService.kt` | C1/C2, пауза-резюм по байтовому offset, тикер позиции 250 мс, `ACTION_SEEK_TO`, `seekToSeconds`, `startParagraphIndex` |
| `ui/MainViewModel.kt` | `startParagraphIndex` вместо `seekToParagraph` сразу после старта, `seekToSeconds()` |
| `app/src/test/java/com/example/SupertonicCoreTest.kt` | +4 теста (сдвиг смещений, бинарный поиск, абзацы, `PcmStore`) |
| `AI_STUDIO_STATE.md` | добавлены разделы «Сессия 2» и «Сессия 2.1» |

Плюс удалить: `app/src/main/java/com/example/supertonic/domain/ReadSession.kt`,
`app/src/main/java/com/example/supertonic/playback/PcmChunk.kt`.

> Если просто оставить эти два файла — проект соберётся (они ни на что не влияют), но
> `ReadSession.kt` содержит вторую копию логики воспроизведения и будет путать следующую
> сессию AI Studio. Лучше удалить.

## 2. Три способа перенести

Коммит-источник: **`4b3b1df`** в ветке `arena/c3e0cbd7-supertronic`.

### Способ A. Патч (быстрее всего, если есть git или терминал)

В репозитории лежит готовый патч ровно на эти 14 файлов:
`docs/patches/2026-10-22-session-2.1.patch` (96 КБ).

```bash
# в корне проекта (AI Studio ZIP, распакованный локально, или локальный клон)
git apply docs/patches/2026-10-22-session-2.1.patch
# или без git:
patch -p1 < docs/patches/2026-10-22-session-2.1.patch
```

Патч удаляет оба лишних файла автоматически. Если `git apply` ругается на контекст —
значит проект в AI Studio уже разошёлся с `cdbb498`, тогда способ B.

Патч проверен: наложен на чистое дерево `cdbb498` — результат совпадает с веткой
`4b3b1df` побайтово по всему каталогу `app/` и по `AI_STUDIO_STATE.md`.

### Способ B. По файлам (для AI Studio в браузере — самый надёжный)

1. Скачайте ветку ZIP-архивом:
   `https://github.com/debugerwho/-supertronic/archive/refs/heads/arena/c3e0cbd7-supertronic.zip`
   (закреплённая версия: `https://github.com/debugerwho/-supertronic/archive/4b3b1df.zip`)
2. В дереве файлов AI Studio откройте каждый из 12 файлов из таблицы и **замените содержимое
   целиком** (копировать — вставить, не пытаться править фрагменты).
3. Удалите два файла: `domain/ReadSession.kt`, `playback/PcmChunk.kt` — через контекстное меню
   файла в дереве.
4. `docs/**` и `prompts/**` в AI Studio копировать не нужно.

Прямые ссылки на отдельный файл (тот же коммит, страница с кнопкой Raw):
`https://github.com/debugerwho/-supertronic/blob/4b3b1df/<путь>`
например
`https://github.com/debugerwho/-supertronic/blob/4b3b1df/app/src/main/java/com/example/supertonic/audio/PlaybackClock.kt`

### Способ C. Заменить всё дерево целиком (если хочется гарантии)

Так как 58 файлов совпадают байт в байт, полная замена безопасна:

1. Распакуйте ZIP ветки у себя.
2. В AI Studio замените каталог `app/` и файл `AI_STUDIO_STATE.md`.
3. **Не трогайте** `metadata.json` и `.aistudio/**` — они идентичны, но именно их AI Studio
   считает своей служебной частью; замена содержимого там не нужна и может сбросить
   capabilities.
4. Убедитесь, что старые `domain/ReadSession.kt` и `playback/PcmChunk.kt` исчезли из дерева.

## 3. Как убедиться, что перенос удался

Проверки в AI Studio (по содержимому файлов, не по отчёту модели):

| Проверка | Ожидание |
|---|---|
| `audio/PlaybackClock.kt` содержит `shiftBaseBy` | да |
| `audio/AudioTrackSink.kt`: в `flush()` есть `_totalWrittenFrames.set(0)` | да, дважды (flush и stop) |
| `audio/PcmStore.kt` содержит `copyInto` и `MIN_CAPACITY_BYTES` в сеттере | да |
| `audio/PcmRing.kt` содержит `copyChunkInto` и не содержит `readyIndices` | да |
| `playback/SupertonicPlaybackService.kt` содержит `startParagraphIndex`, `UI_TICK_MS`, `ACTION_SEEK_TO`, `publishPosition` | да |
| `playback/MediaCodecExporter.kt` содержит `openM4aWriter`/`openWavWriter` и **не** содержит `pcmChunks: List<ByteArray>` в экспортном пути | да |
| `engine/EngineHost.kt` содержит `loadedDirPath` (идемпотентность) | да |
| `core/SourceIndex.kt` содержит `recalculateOffsets` и бинарный поиск, **не** содержит `indexOfFirst` | да |
| `domain/` отсутствует; `playback/PcmChunk.kt` отсутствует | да |
| Сборка в AI Studio | зелёная |

## 4. Куда двигаться дальше (важно для сессий 3 и 4)

После переноса источником истины становится эта ветка. Дальше:

1. Запустите в AI Studio `prompts/P3_export_cpu_languages_docs.txt` (редакция 2) — в нём уже
   учтено, что экспорт стал потоковым, а БЛОК 0 запрещает ломать исправления 2.1.
2. После сессии пришлите ZIP проекта из AI Studio: я сравню его с `4b3b1df` — должны
   отличаться только файлы из задач A/B/C, любой лишний диff сразу видно.
3. Затем `prompts/P4_polish_ship_verify.txt` (редакция 2): оверлей, аудиотека, настройки,
   README/NOTICE, wrapper, 23 инварианта, чек-лист на устройстве.

Не перезапускайте `P2` — его задание выполнено, а повторный прогон перезапишет рабочий
`audio/`-слой и вернёт исправленные дефекты.

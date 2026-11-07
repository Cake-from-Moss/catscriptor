# Привязка логики к UI

Экраны не знают о реализации. Они получают данные только через интерфейсы из
`app/src/main/java/org/cfm/catscriptor/domain/app/`, а реализации собираются в одном месте — `AppGraph`.

```
ui/*Screen (без состояния) ← ui/*Route ← viewmodel/* ← domain/app (интерфейсы) ← AppGraph
```

## Сборки

| Build type | Граф | Зачем |
|---|---|---|
| `debug`, `release` | `ProductionAppGraph` | импорт и подготовка аудио (PREPARE_AUDIO) реальны через AudioProcessingService; запись с микрофона, плеер и экспорт пока недоступны |
| `demo` | `DemoAppGraph` (`app/src/demo`) | показ и проверка дизайна на данных из макета |

Демо-данные лежат только в `src/demo`. В `debug`, `release` и в APK для проверки их нет: это требование AGENTS.md (запрет моков в основном пути).

```bash
./gradlew assembleDemo   # app/build/outputs/apk/demo/app-demo.apk, пакет org.cfm.catscriptor.demo
# -S перезапускает приложение: без него Android вернёт уже открытую задачу и сценарий не применится
adb shell am start -S -n org.cfm.catscriptor.demo/org.cfm.catscriptor.demo.DemoControlActivity -e scenario empty
```

Сценарии: `default`, `empty`, `processing_error`, `models_missing`, `low_storage`.

## Как подключить реализацию

1. Реализовать нужный интерфейс из `domain/app`.
2. Подставить его в `ProductionAppGraph` (`CatscriptorApp.createGraph()`); честные заглушки остаются в `UnavailableAppGraph`.
3. Экраны и ViewModel не меняются.

| Интерфейс | Задача | Что отдаёт UI |
|---|---|---|
| `ProcessingController` | #3–#8 | очередь `ProcessingJob` с этапами, `importFile`, отмена, повтор, пропуск этапа, `modelsStatus` |
| `RecordingRepository` | хранение результатов | список с поиском и фильтром, `TranscriptDocument`, правки |
| `ExportGateway` | #2 | предпросмотр, сохранение в SAF, файл для share sheet |
| `PlaybackController` | #20 | позиция, воспроизведение, скорость |
| `RecorderController` | #21 | запись, уровень сигнала, Live-текст, отметки |
| `SettingsRepository` | — | пока в памяти (`InMemorySettingsRepository`) |

- Прогресс обработки должен приходить из Foreground Service через `observeJobs()`: это `Flow`, UI сам подписывается.
- `TranscriptMapping.kt` переводит `pipeline.Transcript` в `TranscriptDocument`. JSON-файл экспорта делает `DefaultTranscriptExporter`, его схема не меняется. TXT с именами, SRT и предпросмотр рендерит `TranscriptRendering.kt`.
- Удаление мягкое: `delete` прячет запись, `restore` возвращает её по «Отменить», `commitDelete` вызывается, когда Snackbar закрылся без отмены. Записи, которые остались «удалёнными» после смерти процесса, репозиторий удаляет при следующем запуске.
- `ExportOptions.includeAudio` («Сохранять с аудиофайлом») есть в макете, но не в описании дизайна. Флаг доходит до `ExportGateway`, а что он делает (архив или второй файл), нужно решить вместе с реальным экспортом.
- «Переобработать фрагмент» в меню реплики показан выключенным: порта для него пока нет.
- Разрешение `RECORD_AUDIO` пока объявлено только в демо-манифесте. Вместе с #21 его нужно перенести в основной манифест и выставить `RecorderController.isAvailable = true`.

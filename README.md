# catscriptor

Android-приложение для хакатона Nuclear IT Hack. Превращает аудиозаписи разговоров в расшифровку со спикерами и таймкодами. Вся обработка выполняется на устройстве, без интернета.

**Статус:** подготовлен каркас проекта. Пайплайн обработки пока не реализован.

## Сборка

Нужен Android Studio с установленными SDK, NDK и CMake.

```bash
./gradlew assembleDebug   # debug APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # юнит-тесты
```

Приложение поддерживает только архитектуру `arm64-v8a`.

## Модели

Модели не хранятся в git. Их нужно положить в папку `app/src/main/assets/models/`.

| Назначение | Модель | Где скачать | Контрольная сумма |
|------------|--------|-------------|-------------------|
| Распознавание речи | GigaAM v3 через sherpa-onnx, конкретный вариант ещё выбирается | [релиз asr-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) | Будет указана после выбора варианта |

## Замеры

Результаты замеров находятся в [docs/MEASUREMENTS.md](docs/MEASUREMENTS.md).

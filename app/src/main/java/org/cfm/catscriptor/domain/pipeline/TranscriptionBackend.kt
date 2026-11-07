package org.cfm.catscriptor.domain.pipeline

import org.cfm.catscriptor.native.models.ModelResource

/** Граница заменяемой реализации ASR; альтернативный движок реализует тот же контракт. */
interface   TranscriptionBackend : ModelResource {
    /**
     * Распознать один сегмент VAD, читая аудио ограниченными порциями; вернуть исходные слова с метками.
     * Перевести локальные метки движка в метки от начала записи с учётом смещения сегмента.
     * Если речь не распознана, вернуть пустой список слов. Если пословная привязка недоступна,
     * сообщить об ошибке, а не выдумывать метки. Не закрывать источник аудио вызывающего кода.
     */
    suspend fun transcribe(audio: PcmAudioSource, segment: SpeechSegment): AsrSegment
}

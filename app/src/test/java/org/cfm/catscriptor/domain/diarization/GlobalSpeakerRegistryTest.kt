package org.cfm.catscriptor.domain.diarization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlobalSpeakerRegistryTest {
    private fun e(vararg v: Float, ms: Long = 5_000) = LocalSpeakerEmbedding(v, ms)

    @Test fun sameVoiceKeepsGlobalIdAcrossWindows() {
        val registry = GlobalSpeakerRegistry()
        val first = registry.assign(listOf(e(1f, 0f), e(0f, 1f)))
        val second = registry.assign(listOf(e(0.05f, 1f), e(1f, 0.1f)))
        assertEquals(first[0], second[1])
        assertEquals(first[1], second[0])
        assertEquals(2, registry.size)
    }

    @Test fun newVoiceCreatesNewSpeaker() {
        val registry = GlobalSpeakerRegistry()
        registry.assign(listOf(e(1f, 0f, 0f)))
        val next = registry.assign(listOf(e(0f, 0f, 1f)))
        assertEquals(1, next[0])
        assertEquals(2, registry.size)
    }

    @Test fun twoLocalSpeakersOfOneWindowNeverShareGlobalSpeaker() {
        val registry = GlobalSpeakerRegistry()
        registry.assign(listOf(e(1f, 0f)))
        val ids = registry.assign(listOf(e(1f, 0.01f), e(0.99f, 0.02f)))
        assertNotEquals(ids[0], ids[1])
    }

    @Test fun unreliableShortVoiceAttachesToClosestInsteadOfCreatingSpeaker() {
        val registry = GlobalSpeakerRegistry()
        registry.assign(listOf(e(1f, 0f), e(0f, 1f)))
        val ids = registry.assign(listOf(e(1f, 1f, ms = 300)))
        assertEquals(2, registry.size)
        assertEquals(1, ids.size)
    }

    @Test fun centroidsAreStableForEqualInputs() {
        fun run(): List<Int> {
            val registry = GlobalSpeakerRegistry()
            return registry.assign(listOf(e(1f, 0f), e(0f, 1f))) + registry.assign(listOf(e(0f, 1f), e(1f, 0f)))
        }
        assertEquals(run(), run())
    }
}

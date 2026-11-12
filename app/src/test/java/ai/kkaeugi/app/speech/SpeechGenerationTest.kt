package ai.kkaeugi.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGenerationTest {
    @Test fun `onStart 뒤의 완료만 인정한다`() {
        val g = SpeechGeneration()
        g.begin()
        assertFalse("onStart 전 완료는 버림", g.acceptEnd())
        g.onStart()
        assertTrue(g.acceptEnd())
        assertFalse("한 번 받은 뒤 중복 완료는 버림", g.acceptEnd())
        assertFalse(g.speaking)
    }

    @Test fun `앞 문장의 늦은 onDone 은 새 문장의 완료가 아니다`() {
        val g = SpeechGeneration()
        g.begin(); g.onStart()          // 문장 A 읽는 중
        assertEquals(2, g.begin())      // 문장 B 시작(A 는 stop)
        assertFalse("A 의 onStop/onDone", g.acceptEnd())
        g.onStart()                     // B 시작됨
        assertTrue("B 의 onDone", g.acceptEnd())
    }

    @Test fun `stop 으로 끝낸 뒤 온 콜백은 버린다`() {
        val g = SpeechGeneration()
        g.begin(); g.onStart(); g.end()
        assertFalse(g.acceptEnd())
        assertFalse(g.speaking)
    }
}

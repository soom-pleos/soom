package ai.kkaeugi.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 읽을 문장 만들기(괄호 빼기)와 제안 오버레이 안내 문구 */
class SpeechTextTest {
    @Test fun `괄호와 그 안 내용은 읽지 않는다`() {
        assertEquals(
            "차 안 공기가 탁해진 것 같아요. 2분만 바깥 공기로 바꿀까요?",
            speechText("차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?"),
        )
        assertEquals(
            "4명이 타서 공기가 빨리 탁해져요. 바깥 공기로 바꿔 둘까요?",
            speechText("4명이 타서 공기가 빨리 탁해져요(추정 2,000ppm). 바깥 공기로 바꿔 둘까요?"),
        )
        assertEquals("바깥 공기인데 바람이 약해요. 풍량을 올릴까요?", speechText("바깥 공기인데 바람이 약해요(추정 2,100ppm). 풍량을 올릴까요?"))
    }

    @Test fun `괄호가 없으면 그대로`() {
        val t = "졸음 경고가 떴어요. 찬 바람과 창문 조금, 12km 앞 경부선 석적 졸음쉼터 경유까지 할까요?"
        assertEquals(t, speechText(t))
        assertEquals("", speechText(""))
    }

    @Test fun `괄호 앞뒤 공백과 문장부호 정리`() {
        assertEquals("석적 졸음쉼터. 15초만 쉬고 가세요", speechText("석적 졸음쉼터. 15초만 쉬고 가세요(데모)"))
        assertEquals("추정치가 내려갔어요. 원래대로 돌렸어요.", speechText("추정치가 내려갔어요 (추정 900ppm) . 원래대로 돌렸어요."))
        assertEquals("앞 뒤", speechText("앞 (가운데) 뒤"))
    }

    @Test fun `전각 괄호와 겹친 괄호, 닫히지 않은 괄호`() {
        assertEquals("공기가 탁해요.", speechText("공기가 탁해요（추정 2,000ppm）."))
        assertEquals("가 나", speechText("가(안(더 안)쪽) 나"))
        assertEquals("끝까지 읽지 않아요", speechText("끝까지 읽지 않아요(추정 2,000ppm"))
        assertEquals("닫는 괄호만) 있으면 그대로", speechText("닫는 괄호만) 있으면 그대로"))
    }

    @Test fun `읽는 시간 추정은 읽을 글자 수 기준`() {
        val shown = "차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?"
        assertEquals(speechText(shown).length * 180L + 500L, speechEstimateMs(shown))
        assertTrue(speechEstimateMs(shown) < shown.length * 180L + 500L)
    }

    @Test fun `오버레이 안내 - 읽는 동안은 말하는 중`() {
        assertEquals("말하는 중...", proposalHint(null, voiceAnswer = true))
        assertEquals("말하는 중...", proposalHint(null, voiceAnswer = false))
    }

    @Test fun `오버레이 안내 - 음성 인식과 마이크 권한이 있을 때만 말로 답하라고 한다`() {
        assertEquals("6초 뒤 넘어가요  ·  \"응\" 또는 \"괜찮아\"라고 말해도 돼요", proposalHint(6, voiceAnswer = true))
        val buttons = proposalHint(6, voiceAnswer = false)
        assertEquals("6초 뒤 넘어가요  ·  버튼으로 답해 주세요", buttons)
        assertFalse(buttons.contains("말해도"))
    }
}

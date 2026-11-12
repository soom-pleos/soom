package ai.kkaeugi.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** STT 동의/거부 분류. 부분 문자열 오탐("없어", "어디야", "네비")이 없어야 한다. */
class AnswerClassifierTest {

    private fun yes(s: String) = assertEquals("\"$s\" 는 동의여야 함", true, AnswerClassifier.classify(s))
    private fun no(s: String) = assertEquals("\"$s\" 는 거부여야 함", false, AnswerClassifier.classify(s))
    private fun none(s: String) = assertNull("\"$s\" 는 판정하지 않아야 함", AnswerClassifier.classify(s))

    @Test fun `단독 동의어`() { listOf("응", "어", "네", "예", "그래", "좋아", "해줘", "응응", "오케이", "넣어줘", "넵", "넹", "오케", "콜", "좋지", "그러자", "해").forEach { yes(it) } }
    @Test fun `문장부호와 공백이 있어도 동의`() { yes(" 응. "); yes("네!"); yes("그래~") }
    @Test fun `첫 어절이 동의어면 동의`() { yes("응 해줘"); yes("어 넣어줘"); yes("네 좋아요") }
    @Test fun `분절된 응응`() { yes("응 응"); yes("네 네") }

    @Test fun `단독 거부어`() { listOf("괜찮아", "아니", "됐어", "싫어", "하지마", "안 해", "노", "아뇨", "아니오", "노노", "안해", "그만", "싫").forEach { no(it) } }
    @Test fun `거부가 동의보다 우선`() { no("아니 괜찮아"); no("응 아니"); no("어 됐어"); no("그래 안 해"); no("응 하지 마") }
    @Test fun `거부어가 뒤에 있어도 거부`() { no("지금은 괜찮아"); no("오늘은 아니") }

    @Test fun `없어 는 동의가 아니다`() { none("없어") }
    @Test fun `어디야 는 동의가 아니다`() { none("어디야"); none("어 어디야") }
    @Test fun `네비 는 동의가 아니다`() { none("네비"); none("네비 켜줘"); none("어 네비 켜줘") }
    @Test fun `어 가 들어간 다른 말은 동의가 아니다`() { none("어제"); none("어머"); none("더워") }
    @Test fun `네 가 들어간 다른 말은 동의가 아니다`() { none("네가"); none("네시") }
    @Test fun `응 이 들어간 다른 말은 동의가 아니다`() { none("응급실"); none("반응") }
    @Test fun `감탄사 앞머리는 떼고 다시 판정`() { yes("아 네"); yes("음 응"); yes("어 그래"); no("아 아니"); none("음 글쎄") }
    @Test fun `3어절 초과는 판정 안 함`() { none("응 그래 좋아 해줘"); none("아니 아니 아니 아니") }
    @Test fun `빈 문자열`() { none(""); none("   ") }
    @Test fun `무관한 말`() { none("휴게소 어디"); none("음악 틀어줘"); none("괜찮았어") }

    @Test fun `존댓말 동의`() { yes("그래요"); yes("좋아요"); yes("좋습니다"); yes("해줘요"); yes("네 해줘요") }
    @Test fun `존댓말 거부`() { no("아니요"); no("괜찮아요"); no("됐어요"); no("싫어요"); no("괜찮습니다") }
    @Test fun `반복형 동의와 거부`() { yes("네네"); yes("어어"); yes("그래그래"); yes("응응응"); no("노노"); no("아니아니") }
    @Test fun `나뉜 거부어는 붙여서 본다`() { no("하지 마"); no("안 해"); no("하지 마요"); no("안 해요") }
    @Test fun `존댓말 꼬리를 떼도 다른 말은 판정 안 함`() { none("없어요"); none("어디예요"); none("네비요"); none("요") }

    // ---- 정규화 단계 ----
    @Test fun `존댓말 꼬리 제거`() {
        assertEquals("아니", AnswerClassifier.stripPolite("아니요"))
        assertEquals("좋아", AnswerClassifier.stripPolite("좋아요"))
        assertEquals("해줘", AnswerClassifier.stripPolite("해줘용"))
        assertEquals("좋", AnswerClassifier.stripPolite("좋습니다"))
        assertEquals("요", AnswerClassifier.stripPolite("요")) // 다 떼면 아무것도 안 남으니 그대로
    }
    @Test fun `같은 음절 반복 접기`() {
        assertEquals("네", AnswerClassifier.collapseRepeat("네네"))
        assertEquals("어", AnswerClassifier.collapseRepeat("어어"))
        assertEquals("그래", AnswerClassifier.collapseRepeat("그래그래"))
        assertEquals("응", AnswerClassifier.collapseRepeat("응응응"))
        assertEquals("네비", AnswerClassifier.collapseRepeat("네비"))
        assertEquals("아니오", AnswerClassifier.collapseRepeat("아니오"))
    }

    /**
     * 2차 리뷰 실측 표 25개: Pleos/Android STT 가 실제로 내놓은 꼴(존댓말, 반복, 감탄사, 띄어쓰기)과 기대 판정.
     * 이전 분류기는 이 중 존댓말·반복형 전부를 판정 불가로 처리했다.
     */
    @Test fun `실측 표 25개`() {
        val table: List<Pair<String, Boolean?>> = listOf(
            // 동의 12
            "네" to true,
            "네네" to true,
            "네 네" to true,
            "넵" to true,
            "응응" to true,
            "어어" to true,
            "그래요" to true,
            "좋아요" to true,
            "좋습니다" to true,
            "해줘요" to true,
            "아 네" to true,
            "음 응 해줘" to true,
            // 거부 10
            "아니요" to false,
            "아뇨" to false,
            "아니오" to false,
            "괜찮아요" to false,
            "됐어요" to false,
            "싫어요" to false,
            "하지 마요" to false,
            "안 해요" to false,
            "노노" to false,
            "아 아니요 괜찮아요" to false,
            // 판정 불가 3
            "없어요" to null,
            "어디야" to null,
            "휴게소 어디 있어요" to null,
        )
        assertEquals(25, table.size)
        for ((text, expected) in table) assertEquals("\"$text\"", expected, AnswerClassifier.classify(text))
    }
}

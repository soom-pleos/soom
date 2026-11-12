package ai.kkaeugi.app.speech

/**
 * STT 인식 문장을 동의/거부로 분류하는 순수 함수. Android 의존 없음(JVM 테스트 대상).
 *
 * 규칙
 *  - 정규화: 양끝 공백 제거, 문장부호 제거, 어절 단위로 나눈 뒤 어절마다
 *      (1) 끝의 존댓말 꼬리 "요/용/욤/습니다" 를 뗀다("아니요"→"아니", "좋아요"→"좋아", "해줘요"→"해줘")
 *      (2) 같은 음절 묶음 반복을 하나로 접는다("네네"→"네", "어어"→"어", "그래그래"→"그래", "응응응"→"응")
 *  - 어절 수가 MAX_TOKENS(3) 를 넘으면 판정하지 않는다(null). 긴 문장은 운전자끼리의 대화일 가능성이 높다.
 *  - 거부가 먼저다: NO 토큰이 어절(또는 연속 어절을 붙인 것)로 정확히 들어 있으면 false("아니 괜찮아", "응 아니", "하지 마", "안 해").
 *  - 동의: 발화 전체가 YES 토큰이거나, 첫 어절이 YES 토큰이면 true("응 해줘", "어 넣어줘").
 *  - 감탄사 앞머리: 첫 어절이 1음절 감탄사("아", "음", "어", "오", "흠", "엄")이고 뒤에 다른 어절이 있으면 떼고 다시 판정한다("아 네", "음 응").
 *    "어" 는 YES 토큰이기도 해서 뒤에 어절이 있을 때만 감탄사로 본다.
 *  - 부분 문자열 매칭은 하지 않는다. "없어"(어 포함), "어디야", "네비" 는 null.
 */
object AnswerClassifier {
    const val MAX_TOKENS = 3

    val YES: Set<String> = setOf(
        "응", "어", "네", "예", "그래", "좋아", "해줘", "응응", "오케이", "넣어줘",
        "넵", "넹", "오케", "콜", "좋지", "그러자", "해",
        // "좋습니다" 에서 "습니다" 를 뗀 형태
        "좋",
    )
    val NO: Set<String> = setOf(
        "괜찮아", "아니", "됐어", "싫어", "하지마", "안 해", "노",
        "아뇨", "아니오", "노노", "안해", "그만", "싫",
        // 맡겨 둔 환기의 예고를 물릴 때
        "취소", "취소해", "취소해줘",
        // "괜찮습니다" 에서 "습니다" 를 뗀 형태
        "괜찮",
    )

    /** 첫 어절에서만 떼는 1음절 감탄사 */
    val INTERJECTIONS: Set<String> = setOf("아", "음", "어", "오", "흠", "엄")

    /** 공백이 들어간 NO 토큰("안 해")도 한 어절로 비교하기 위해 공백을 뺀 형태 */
    private val noCompact = NO.map { it.replace(" ", "") }.toSet()

    private val politeTails = listOf("습니다", "용", "욤", "요")

    /** 문장 → 정규화된 어절 목록 */
    fun normalize(text: String): List<String> =
        text.trim()
            .replace(Regex("[.,!?~…\"'「」]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { collapseRepeat(stripPolite(it)) }
            .filter { it.isNotBlank() }

    /** 끝의 존댓말 꼬리 하나를 뗀다. 떼고 나서 아무것도 안 남으면 그대로 둔다("요" 단독) */
    fun stripPolite(token: String): String {
        for (tail in politeTails) {
            if (token.length > tail.length && token.endsWith(tail)) return token.dropLast(tail.length)
        }
        return token
    }

    /** "네네"→"네", "그래그래"→"그래", "응응응"→"응". 가장 짧은 반복 단위로 접는다. 반복이 아니면 그대로 */
    fun collapseRepeat(token: String): String {
        val n = token.length
        for (p in 1..n / 2) {
            if (n % p != 0) continue
            val unit = token.substring(0, p)
            if ((0 until n step p).all { token.regionMatches(it, unit, 0, p) }) return unit
        }
        return token
    }

    /** true = 동의, false = 거부, null = 판정 안 함 */
    fun classify(text: String): Boolean? = classifyTokens(normalize(text))

    private fun classifyTokens(tokens: List<String>): Boolean? {
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return null
        // "안 해", "하지 마" 처럼 나뉜 NO 는 연속 어절을 붙여서도 본다
        for (i in tokens.indices) for (j in i until tokens.size) {
            if (tokens.subList(i, j + 1).joinToString("") in noCompact) return false
        }
        val joined = tokens.joinToString("")
        if (tokens.size == 1 && tokens[0] in YES) return true
        if (collapseRepeat(joined) in YES) return true // "응 응", "네 네" 같은 분절
        // 감탄사 앞머리를 떼고 다시: "아 네", "음 응", "어 그래". "어 어디야" 는 "어디야" 로 다시 봐서 판정 안 함
        if (tokens.size >= 2 && tokens[0] in INTERJECTIONS) return classifyTokens(tokens.drop(1))
        if (tokens[0] in YES) return true
        return null
    }
}

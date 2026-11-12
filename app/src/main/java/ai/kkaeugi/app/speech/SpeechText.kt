package ai.kkaeugi.app.speech

/**
 * 화면 문장을 읽을 문장으로 바꾼다(순수 함수, JVM 테스트 대상).
 *
 * 엔진 문장의 괄호는 화면용이다. "차 안 공기가 탁해진 것 같아요(추정 2,000ppm). 2분만 바깥 공기로 바꿀까요?" 를
 * 그대로 읽으면 길고 숫자가 귀에 걸린다. 말할 때는 괄호와 그 안 내용을 뺀다.
 *  - 반각 () 와 전각 （） 모두. 괄호 안에 괄호가 있어도 바깥 괄호까지 통째로 뺀다.
 *  - 닫는 괄호가 없으면 여는 괄호부터 끝까지 뺀다(읽다 마는 것보다 낫다).
 *  - 빼고 남은 겹친 공백, 문장부호 앞 공백은 정리한다.
 */
fun speechText(text: String): String {
    val sb = StringBuilder(text.length)
    var depth = 0
    for (ch in text) {
        when (ch) {
            '(', '（' -> depth++
            ')', '）' -> if (depth > 0) depth-- else sb.append(ch)
            else -> if (depth == 0) sb.append(ch)
        }
    }
    return sb.toString()
        .replace(Regex("\\s+"), " ")
        .replace(Regex(" ([.,?!])"), "$1")
        .trim()
}

/** TTS 완료 콜백이 없을 때 쓰는 읽는 시간 추정(ms): 읽을 글자 수 × 0.18초 + 0.5초. Speaker.estimateMs 와 같은 식 */
fun speechEstimateMs(text: String): Long = speechText(text).length * 180L + 500L

/**
 * 제안 오버레이 맨 아래 한 줄(순수 함수).
 *  - 읽는 동안(remainingSec == null): "말하는 중..."
 *  - 다 읽은 뒤: 남은 초 + 답하는 방법. 음성 인식기가 있고 마이크 권한도 있을 때만 말로 답하라고 안내한다.
 *    모의 모드(음성 인식기 없음)나 마이크 권한이 없을 때는 버튼만 안내한다.
 */
fun proposalHint(remainingSec: Int?, voiceAnswer: Boolean): String = when {
    remainingSec == null -> "말하는 중..."
    voiceAnswer -> "${remainingSec}초 뒤 넘어가요  ·  \"응\" 또는 \"괜찮아\"라고 말해도 돼요"
    else -> "${remainingSec}초 뒤 넘어가요  ·  버튼으로 답해 주세요"
}

/** 예고 문장에서 말로 거절하라고 안내하는 부분과, 말로 답할 수 없을 때 대신 쓰는 부분 */
const val ANNOUNCE_VOICE_PART = "싫으면 '아니'라고 하세요"
const val ANNOUNCE_BUTTON_PART = "싫으면 취소를 누르세요"

/**
 * 예고 문장을 지금 환경에 맞춘다(순수 함수). 음성 인식기가 없거나 마이크 권한이 없으면 "아니"라고 말해도 듣지 못한다.
 * 그때는 읽을 때와 화면 모두 "싫으면 취소를 누르세요"로 바꾼다. 말로 답할 수 있으면 엔진 문장 그대로다
 */
fun announceText(text: String, voiceAnswer: Boolean): String =
    if (voiceAnswer) text else text.replace(ANNOUNCE_VOICE_PART, ANNOUNCE_BUTTON_PART)

/**
 * 예고 카드 맨 아래 한 줄(순수 함수). 맡겨 둔 환기는 묻지 않고 예고한 뒤 취소 창(5초)을 둔다.
 *  - 읽는 동안(remainingSec == null): "말하는 중..."
 *  - 다 읽은 뒤: 남은 초. 화면에는 [취소] 하나뿐이고, 바로 하라는 답은 말("응")로만 받는다
 */
fun announceHint(remainingSec: Int?, voiceAnswer: Boolean): String = when {
    remainingSec == null -> "말하는 중..."
    voiceAnswer -> "${remainingSec}초 뒤 바꿔요  ·  바로 하려면 \"응\", 싫으면 \"아니\""
    else -> "${remainingSec}초 뒤 바꿔요  ·  싫으면 취소를 눌러 주세요"
}

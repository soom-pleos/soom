package ai.kkaeugi.app

import ai.kkaeugi.engine.ArrivalSummary
import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 기기 안에만 남는 주행 기록. 앱 내부 저장소의 trip_log.jsonl 에 한 줄 = 한 항목(JSON lines), 최근 MAX 개만 유지한다.
 * 항목: 세션 요약(summary)과 동의/거부(answer). 서버로 보내지 않는다. 설정 화면 "최근 주행 기록" 에 5줄 보여 준다.
 */
class TripLog(context: Context) {
    @Serializable
    data class Entry(
        /** 벽시계 epoch ms(사람이 볼 시각이라 여기서만 벽시계를 쓴다) */
        val at: Long,
        /** "summary" | "answer" */
        val kind: String,
        val text: String,
        val tripMinutes: Int = 0,
        val ventilations: Int = 0,
        val drowsySignals: Int = 0,
        val restStopsVisited: Int = 0,
        val declined: Int = 0,
        /** 숨 모델: 추정 최고 ppm, 한도 위 누적 분 */
        val peakPpm: Int = 0,
        val minutesOverLimit: Int = 0,
    ) {
        fun line(): String {
            val when_ = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(at))
            return if (kind == "summary") "$when_  ${tripMinutes}분 주행, 환기 ${ventilations}회, 졸음 ${drowsySignals}회, 쉼 ${restStopsVisited}회, 거절 ${declined}회${if (peakPpm > 0) ", 최고 ${"%,d".format(peakPpm)}ppm 추정" else ""}"
            else "$when_  $text"
        }
    }

    private val file = File(context.filesDir, "trip_log.jsonl")
    private val json = Json { ignoreUnknownKeys = true }
    private val entries = mutableListOf<Entry>()

    init { load() }

    val all: List<Entry> get() = entries.toList()
    /** 최근 주행 요약 n 개(최신 먼저). 동의/거절(answer) 줄은 뺀다 */
    fun recent(n: Int = 5): List<Entry> = recentSummaries(entries, n)

    fun appendSummary(s: ArrivalSummary) = append(Entry(
        at = System.currentTimeMillis(), kind = "summary", text = s.headline,
        tripMinutes = s.tripMinutes, ventilations = s.ventilations, drowsySignals = s.drowsySignals,
        restStopsVisited = s.restStopsVisited, declined = s.declined,
        peakPpm = s.peakPpm, minutesOverLimit = s.minutesOverLimit,
    ))

    fun appendAnswer(kindLabel: String, yes: Boolean, via: String) = append(Entry(
        at = System.currentTimeMillis(), kind = "answer", text = "$kindLabel 제안 ${if (yes) "동의" else "거절"}($via)",
    ))

    @Synchronized
    private fun append(e: Entry) {
        entries += e
        while (entries.size > MAX) entries.removeAt(0)
        runCatching {
            file.writeText(entries.joinToString("\n") { json.encodeToString(it) } + "\n")
        }.onFailure { Log.w(TAG, "trip_log 저장 실패: ${it.message}") }
    }

    @Synchronized
    private fun load() {
        if (!file.exists()) return
        runCatching {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { l -> runCatching { json.decodeFromString<Entry>(l) }.getOrNull() }
        }.onSuccess { entries.clear(); entries += it.takeLast(MAX) }
            .onFailure { Log.w(TAG, "trip_log 읽기 실패: ${it.message}") }
    }

    companion object {
        const val TAG = "Kkaeugi.TripLog"; const val MAX = 30
        /** 순수 함수(JVM 테스트 대상): 주행 요약만 골라 최신 먼저 n 개 */
        fun recentSummaries(entries: List<Entry>, n: Int): List<Entry> = entries.filter { it.kind == "summary" }.takeLast(n).reversed()
    }
}

package ai.kkaeugi.app.gleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** adb 로 부르는 실측 명령의 순수 로직: what 값, 좌석 값 읽기, 파일 경로, 상태 글, 답 한 번만 보내기 */
class AdbToolsTest {

    @Test fun `what 은 공백과 대소문자를 가리지 않고 없으면 all 이다`() {
        assertEquals("all", AdbCommand.normalize(null))
        assertEquals("all", AdbCommand.normalize("  "))
        assertEquals("state", AdbCommand.normalize(" State "))
        assertEquals(listOf("fresh", "fan", "window", "all", "result", "diag", "diagresult", "state", "seats", "help"), AdbCommand.ALL)
        assertTrue(AdbCommand.ALL.all { AdbCommand.isKnown(it) })
        assertFalse(AdbCommand.isKnown("door"))
    }

    @Test fun `도움말에는 명령이 전부 있고 adb 한 줄 예가 붙는다`() {
        val help = AdbCommand.help("ai.kkaeugi.app")
        for (w in AdbCommand.ALL - "help") assertTrue(w, help.contains(w))
        assertTrue(help.contains("adb shell am broadcast -n ai.kkaeugi.app/.gleo.VerifyReceiver -a ai.kkaeugi.app.VERIFY --es what state"))
        assertFalse("긴 대시를 쓰지 않는다", help.contains("\u2014") || help.contains("\u2013"))
    }

    @Test fun `좌석 값은 다섯 칸을 쉼표나 공백으로 받고 붙여 써도 된다`() {
        val expected = AdbCommand.SeatsArg.Manual(listOf(true, false, true, false, false))
        assertEquals(expected, AdbCommand.parseSeats("1,0,1,0,0"))
        assertEquals(expected, AdbCommand.parseSeats(" 1, 0, 1, 0, 0 "))
        assertEquals(expected, AdbCommand.parseSeats("1 0 1 0 0"))
        assertEquals(expected, AdbCommand.parseSeats("10100"))
        assertEquals(expected, AdbCommand.parseSeats("O,X,O,X,X"))
        // 운전석 0 도 그대로 받는다(주행 중에는 엔진이 운전석을 탄 것으로 센다)
        assertEquals(AdbCommand.SeatsArg.Manual(listOf(false, true, true, true, true)), AdbCommand.parseSeats("0,1,1,1,1"))
        assertEquals("1,0,1,0,0", AdbCommand.seatsText(expected.seats))
        assertEquals("1,?,0,?,1", AdbCommand.seatsText(listOf(true, null, false, null, true)))
    }

    @Test fun `좌석 값이 sensor 면 센서로 돌아가고 모양이 틀리면 이유를 알려 준다`() {
        for (w in listOf("sensor", "SENSOR", "off", "auto")) assertEquals(w, AdbCommand.SeatsArg.Sensor, AdbCommand.parseSeats(w))
        for (bad in listOf(null, "", "1,0,1", "1,0,1,0,0,1", "1,0,2,0,0", "abcde")) {
            val r = AdbCommand.parseSeats(bad)
            assertTrue("$bad → $r", r is AdbCommand.SeatsArg.Bad)
            assertTrue((r as AdbCommand.SeatsArg.Bad).reason.isNotBlank())
        }
    }

    @Test fun `외부 파일 경로는 첫 사용자면 sdcard 로 줄이고 다른 사용자면 그대로 적는다`() {
        assertEquals(
            "/sdcard/Android/data/ai.kkaeugi.app/files/verify_latest.txt",
            ReportFiles.adbPath("/storage/emulated/0/Android/data/ai.kkaeugi.app/files/verify_latest.txt"),
        )
        // 차량용 안드로이드는 운전자가 사용자 10 인 경우가 많다. 그때 /sdcard 로 적으면 다른 사용자의 폴더를 가리킨다
        val user10 = "/storage/emulated/10/Android/data/ai.kkaeugi.app/files/verify_latest.txt"
        assertEquals(user10, ReportFiles.adbPath(user10))
        assertEquals(
            "내부 저장 됨 · 읽기: adb shell cat /sdcard/Android/data/ai.kkaeugi.app/files/diag_latest.txt",
            ReportFiles.Saved(true, "/storage/emulated/0/Android/data/ai.kkaeugi.app/files/diag_latest.txt", null).text(),
        )
        val failed = ReportFiles.Saved(true, null, "IllegalStateException: 외부 저장소 없음").text()
        assertTrue(failed, failed.contains("외부 저장 실패") && failed.contains("--es what result"))
    }

    @Test fun `상태 글은 머리말 아래 이름과 값 한 줄씩이다`() {
        val text = StateReport.format("숨 상태 v0.7.4", listOf("기어" to "GEAR_PARK", "공조 전원" to "꺼짐"))
        assertEquals(listOf("숨 상태 v0.7.4", "기어: GEAR_PARK", "공조 전원: 꺼짐"), text.lines())
    }

    @Test fun `답은 한 번만 나간다 - 끝난 뒤의 시간 초과는 무시된다`() {
        val reply = AdbReply(null)
        reply.interim = "아직 도는 중"
        assertFalse(reply.isFinished)
        reply.finish("끝")
        assertTrue(reply.isFinished)
        reply.timeout()   // 예외 없이 지나간다
        assertTrue(AdbReply.HOLD_MS < 10_000L)
        assertEquals(listOf(0, 1, 2), listOf(AdbReply.CODE_REFUSED, AdbReply.CODE_DONE, AdbReply.CODE_RUNNING))
    }
}

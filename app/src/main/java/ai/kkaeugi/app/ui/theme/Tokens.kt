package ai.kkaeugi.app.ui.theme

import ai.kkaeugi.app.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 디자인 토큰. 화면 코드는 색, 글자 크기, 간격, 모서리를 여기서만 가져온다(화면에 Color(0x..) 나 sp 를 직접 쓰지 않는다).
 *
 * 톤: 조용하고 정돈된 금융 앱 같은 화면. 회색 단계는 적고 정확하게, 위계는 굵은 제목과 보통 굵기 본문의 대비로 만든다.
 * 화면에 나오는 색은 파랑 하나, 빨강 하나, 회색 단계뿐이다. 글자에는 색을 거의 입히지 않는다
 * (링크처럼 누르는 글자 "펼치기"와 한도 숫자 정도). 나머지 강조는 굵기와 크기로 한다.
 * 낮(밝음)이 기본이고 밤은 시스템 야간 모드를 따른다
 */
@Immutable
data class KColors(
    /** 화면 바탕 */
    val bg: Color,
    /** 면(주인공 영역, 시트, 설정 목록) */
    val surface: Color,
    /** 글자 1단계: 본문. 큰 숫자, 제목, 값 */
    val ink: Color,
    /** 글자 2단계: 보조. 설명, 이름표 */
    val sub: Color,
    /** 글자 3단계: 흐림. 단위, 주석, 눈금 */
    val faint: Color,
    /** 가는 구분선, 진행선의 바탕 */
    val line: Color,
    /** 글자가 아닌 흐린 윤곽: 빈 좌석, 꺼진 스위치, "지금" 세로선, 비교 막대 */
    val outline: Color,
    /** 보조 버튼의 회색 면(흰 면 위에서) */
    val fill: Color,
    /** 보조 버튼의 회색 면(화면 바탕 위에서. 바탕과 같은 회색이면 보이지 않아 한 단계 진하다) */
    val fillOnBg: Color,
    /** 강조 파랑. 하나뿐이다: 실제 곡선과 예보, 켜진 스위치, 상태 점, 누르는 글자 */
    val accent: Color,
    /** 주 버튼 바탕 파랑과 그 위 글자. 밤에도 흰 글자가 읽히도록 낮과 같은 파랑을 쓴다 */
    val primary: Color,
    val onPrimary: Color,
    /** 한도와 경고의 빨강. 하나뿐이다 */
    val limit: Color,
    val dark: Boolean,
)

val DayColors = KColors(
    bg = Color(0xFFF2F4F6), surface = Color(0xFFFFFFFF),
    ink = Color(0xFF191F28), sub = Color(0xFF4E5968), faint = Color(0xFF8B95A1),
    line = Color(0xFFE5E8EB), outline = Color(0xFFD1D6DB), fill = Color(0xFFF2F4F6), fillOnBg = Color(0xFFE5E8EB),
    accent = Color(0xFF3182F6), primary = Color(0xFF3182F6), onPrimary = Color(0xFFFFFFFF),
    limit = Color(0xFFF04452), dark = false,
)

val NightColors = KColors(
    bg = Color(0xFF101214), surface = Color(0xFF17191C),
    ink = Color(0xFFF2F4F6), sub = Color(0xFFB0B8C1), faint = Color(0xFF6B7684),
    line = Color(0xFF2B2F36), outline = Color(0xFF3C424B), fill = Color(0xFF2B2F36), fillOnBg = Color(0xFF2B2F36),
    accent = Color(0xFF4C9AFF), primary = Color(0xFF3182F6), onPrimary = Color(0xFFFFFFFF),
    limit = Color(0xFFFF6B76), dark = true,
)

/** Pretendard(SIL OFL 1.1). 굵기 셋만 싣는다: 400, 600, 700 */
val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
)

/** 고정폭 숫자. 값이 바뀌어도 글자 폭이 흔들리지 않는다 */
private const val TNUM = "tnum"

/**
 * 글자 크기는 다섯 단계뿐이고 한 화면에서는 많아야 넷을 쓴다.
 *  표제 숫자 96 / 제목 30 / 본문 22 / 보조 18 / 주석 14.
 * 굵기는 둘이다: 굵게(700)와 보통(400). 표제 숫자와 제목은 굵게, 본문 아래는 보통이 기본이고
 * 강조할 때 .bold 를 붙인다. 중간 굵기(600, .semi)는 버튼 글자와 목록 행 제목에만 쓴다.
 * 큰 한글은 자간을 조금 좁히고(-0.02em) 줄 높이는 1.4 안팎으로 둔다.
 * 단위("ppm 추정", "km", "명")는 숫자보다 두 단계 작게, 흐림 색으로 숫자 기준선에 붙인다(KNumberUnit)
 */
@Immutable
data class KType(
    /** 표제 숫자: 주행 중 ppm, 쉼 타이머 */
    val display: TextStyle,
    /** 제목: 질문, 요약 한 문장, 레일의 숫자 */
    val title: TextStyle,
    /** 본문 */
    val body: TextStyle,
    /** 보조 */
    val sub: TextStyle,
    /** 주석 */
    val note: TextStyle,
    /** 자가 진단과 로그의 고정폭 글자 */
    val mono: TextStyle,
)

val KTypeScale = KType(
    display = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 96.sp, lineHeight = 104.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),
    title = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 42.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TNUM),
    body = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 32.sp, letterSpacing = (-0.01).em, fontFeatureSettings = TNUM),
    sub = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 26.sp, fontFeatureSettings = TNUM),
    note = TextStyle(fontFamily = Pretendard, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = TNUM),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
)

/** 굵게(700): 제목과 강조 */
val TextStyle.bold: TextStyle get() = copy(fontWeight = FontWeight.Bold)
/** 중간 굵기(600): 버튼 글자와 목록 행 제목에만 */
val TextStyle.semi: TextStyle get() = copy(fontWeight = FontWeight.SemiBold)
/** 보통(400) */
val TextStyle.regular: TextStyle get() = copy(fontWeight = FontWeight.Normal)

/**
 * 간격. 줄 사이는 8, 16, 24 만 쓴다. 화면 가장자리와 면 안쪽은 32.
 * 붙어 있는 두 줄(이름표와 값)은 간격 없이 줄 높이로만 띄운다
 */
object KSpace {
    val xs = 8.dp
    val sm = 16.dp
    val md = 24.dp
    /** 화면 가장자리, 면 안쪽 */
    val lg = 32.dp
}

/** 모서리: 면은 24, 버튼과 작은 조각은 16. 이 둘뿐이다 */
object KRadius {
    val surface = 24.dp
    val control = 16.dp
}

/** 크기 규칙 */
object KSize {
    /** 주행 중 터치 대상의 최소 높이 */
    val touchMin = 76.dp
    /** 제안 시트의 답 버튼 높이 */
    val answer = 96.dp
    /** 설정 목록 한 행 */
    val row = 72.dp
    /** 정차 화면의 작은 버튼 높이 */
    val control = 56.dp
    /** 위 막대 */
    val topBar = 48.dp
    /** 도착 카드의 [확인], 주행 홈의 "바깥 공기로" 같은 한 개짜리 큰 버튼의 너비 */
    val wideButton = 280.dp
    /** 가는 선 */
    val hairline = 1.dp
    /** 아이콘 선 굵기 */
    val iconStroke = 2.dp
}

/** 움직임 길이(ms) */
object KMotion {
    const val SHEET_MS = 250
    const val NUMBER_MS = 180
    const val BREATH_MS = 3000
}

private val LocalKColors = staticCompositionLocalOf { DayColors }
private val LocalKType = staticCompositionLocalOf { KTypeScale }
/** 움직임을 켤지. 스크린샷 테스트와 프리뷰에서는 꺼진다 */
val LocalMotion = staticCompositionLocalOf { true }

/** 화면 코드에서 K.colors, K.type 으로 꺼낸다 */
object K {
    val colors: KColors @Composable @ReadOnlyComposable get() = LocalKColors.current
    val type: KType @Composable @ReadOnlyComposable get() = LocalKType.current
}

/**
 * 창 크기에 맞춘 배율(순수 함수). 화면은 1280x720 을 기준으로 그렸다. 실제 창은 이보다 넓거나 높다(Pleos 에뮬레이터에서 대략 1350x1000).
 * 가로와 세로 가운데 덜 늘어난 쪽의 비율만큼 글자와 여백을 함께 키운다. 넓은 창에서 글자만 작게 남지 않게 하되
 * 한없이 커지지 않게 1.3 배에서 멈춘다. 기준보다 좁은 창에서는 0.8 배까지 줄인다
 */
object KWindow {
    const val BASE_WIDTH = 1280f
    const val BASE_HEIGHT = 720f
    const val MIN_SCALE = 0.8f
    const val MAX_SCALE = 1.3f

    fun scale(widthDp: Float, heightDp: Float): Float =
        minOf(widthDp / BASE_WIDTH, heightDp / BASE_HEIGHT).coerceIn(MIN_SCALE, MAX_SCALE)
}

/**
 * 안쪽 내용을 창 크기에 비례해 키운다. 글자 크기와 여백과 버튼이 같은 비율로 커진다(밀도를 바꿔서 한다. 화면 코드는 그대로다)
 */
@Composable
fun KWindowScaled(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scale = KWindow.scale(maxWidth.value, maxHeight.value)
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale), content = content)
    }
}

/**
 * 숨 테마. dark 를 주지 않으면 시스템 야간 모드를 따른다(AAOS 는 야간에 자동으로 바꾼다).
 * Material3 부품도 같은 토큰을 쓰게 색 구성표를 맞춰 둔다
 */
@Composable
fun KkaeugiTheme(
    dark: Boolean = isSystemInDarkTheme(),
    motion: Boolean = !LocalInspectionMode.current,
    content: @Composable () -> Unit,
) {
    val c = if (dark) NightColors else DayColors
    val scheme = if (dark) darkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, onSecondary = c.onPrimary,
        background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.fill, onSurfaceVariant = c.sub, outline = c.outline, outlineVariant = c.line, error = c.limit,
    ) else lightColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, onSecondary = c.onPrimary,
        background = c.bg, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.fill, onSurfaceVariant = c.sub, outline = c.outline, outlineVariant = c.line, error = c.limit,
    )
    val t = KTypeScale
    val typography = Typography(
        displayLarge = t.display, headlineMedium = t.title, titleLarge = t.title, titleMedium = t.body,
        bodyLarge = t.body, bodyMedium = t.sub, bodySmall = t.note, labelLarge = t.sub, labelMedium = t.note, labelSmall = t.note,
    )
    CompositionLocalProvider(LocalKColors provides c, LocalKType provides t, LocalMotion provides motion) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

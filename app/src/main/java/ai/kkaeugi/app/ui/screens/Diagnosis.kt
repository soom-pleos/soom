package ai.kkaeugi.app.ui.screens

import ai.kkaeugi.app.DiagUi
import ai.kkaeugi.app.ui.DIAG_COLUMNS
import ai.kkaeugi.app.ui.components.KSecondaryButton
import ai.kkaeugi.app.ui.components.KPrimaryButton
import ai.kkaeugi.app.ui.diagStatusLine
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.bold
import ai.kkaeugi.app.vehicle.DiagLine
import ai.kkaeugi.app.vehicle.DiagReport
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 자가 진단 보고서(정차 중에만). 한 화면에 다 보이게 세 단으로 나눠 고정폭 글꼴로 적는다(캡처용).
 * 글자는 선택해서 복사할 수 있고, [복사]는 전체를 클립보드에 넣는다.
 * 실패한 줄만 빨강, 보류와 대체는 흐림 색, 묶음 제목은 보조 색. 같은 줄이 Logcat(Kkaeugi.Diag)과 diag_latest.txt 에도 있다
 */
@Composable
internal fun DiagScreen(diag: DiagUi, onClose: () -> Unit) {
    val c = K.colors
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) {}.padding(start = KSpace.lg, end = KSpace.lg, top = KSpace.sm, bottom = KSpace.lg)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(diag.header, style = K.type.sub.bold, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(diagStatusLine(diag), style = K.type.note, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(KSpace.sm))
            Text(diag.summary, style = K.type.body.bold, color = c.ink, maxLines = 1)
            Spacer(Modifier.width(KSpace.sm))
            KSecondaryButton("복사", { clipboard.setText(AnnotatedString(diag.report)) }, Modifier.width(112.dp).height(KSize.control), style = K.type.sub, onBg = true)
            Spacer(Modifier.width(KSpace.xs))
            KPrimaryButton("닫기", onClose, Modifier.width(112.dp).height(KSize.control), style = K.type.sub)
        }
        Spacer(Modifier.height(KSpace.xs))
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            Row(
                Modifier.fillMaxSize().background(c.surface, RoundedCornerShape(KRadius.surface)).padding(horizontal = KSpace.lg, vertical = KSpace.md).verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(KSpace.sm),
            ) {
                DiagReport.columns(diag.lines, DIAG_COLUMNS).forEach { column ->
                    Column(Modifier.weight(1f)) { column.forEach { DiagRow(it) } }
                }
            }
        }
    }
}

@Composable
private fun DiagRow(line: DiagLine) {
    val c = K.colors
    val color = when (line.kind) {
        DiagLine.Kind.OK -> c.ink
        DiagLine.Kind.FAIL -> c.limit
        DiagLine.Kind.HOLD, DiagLine.Kind.ALT -> c.faint
        DiagLine.Kind.SECTION -> c.sub
    }
    Text(line.text(), style = K.type.mono, color = color)
}

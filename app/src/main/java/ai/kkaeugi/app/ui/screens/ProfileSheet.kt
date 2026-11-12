package ai.kkaeugi.app.ui.screens

import ai.kkaeugi.app.ui.SeatText
import ai.kkaeugi.app.ui.components.KSecondaryButton
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KRadius
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.semi
import ai.kkaeugi.engine.SeatProfile
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 자리 설정 시트(v0.7.3). 정차 중에 좌석 지도의 자리를 누르면 아래에서 올라온다.
 * 위: "조수석 설정"과 이 설정이 무엇인지 한 줄, 오른쪽에 [닫기].
 * 아래: 여섯 칸(기본, 어린이, 임산부, 어르신, 호흡기 주의, 심혈관 주의). 칸마다 이름과 "무엇이 바뀌는지" 한 줄.
 * 고른 칸은 본문 색 바탕에 흰 글자다(파랑은 쓰지 않는다). 누르면 바로 고르고 닫는다.
 * 프로필은 설정이다. 그래서 칸에는 바뀌는 순서와 여유만 적는다
 */
@Composable
internal fun ProfileSheet(seat: Int, current: SeatProfile, manual: Boolean, onPick: (SeatProfile) -> Unit, onClose: () -> Unit) {
    val c = K.colors
    BottomSheet(
        top = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SeatText.sheetTitle(seat), style = K.type.title, color = c.ink, maxLines = 1)
                    Text(SeatText.PROFILE_NOTE, style = K.type.sub, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(KSpace.md))
                KSecondaryButton("닫기", onClose, Modifier.width(160.dp).height(KSize.control), style = K.type.body)
            }
        },
        bottom = {
            SeatProfile.entries.chunked(3).forEachIndexed { i, rowItems ->
                if (i > 0) Spacer(Modifier.height(KSpace.sm))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KSpace.sm)) {
                    rowItems.forEach { p ->
                        val on = p == current
                        Column(
                            Modifier.weight(1f).height(100.dp).clip(RoundedCornerShape(KRadius.control)).background(if (on) c.ink else c.fill)
                                .clickable(role = Role.RadioButton) { onPick(p) }.padding(horizontal = KSpace.md),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(p.label, style = K.type.body.semi, color = if (on) c.surface else c.ink, maxLines = 1)
                            Text(SeatText.effect(p), style = K.type.sub, color = if (on) c.surface else c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(KSpace.sm))
            Text(
                if (manual) "${SeatText.STICKY_NOTE} ${SeatText.MANUAL_NOTE}" else SeatText.STICKY_NOTE,
                style = K.type.note, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

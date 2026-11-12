package ai.kkaeugi.app.ui.screens

import ai.kkaeugi.app.UiState
import ai.kkaeugi.app.WakeViewModel
import ai.kkaeugi.app.signals.DemoScenario
import ai.kkaeugi.app.signals.PmChoice
import ai.kkaeugi.app.ui.cabinStateLine
import ai.kkaeugi.app.ui.components.KChip
import ai.kkaeugi.app.ui.components.KSecondaryButton
import ai.kkaeugi.app.ui.components.KPrimaryButton
import ai.kkaeugi.app.ui.components.KTextButton
import ai.kkaeugi.app.ui.theme.K
import ai.kkaeugi.app.ui.theme.KSize
import ai.kkaeugi.app.ui.theme.KSpace
import ai.kkaeugi.app.ui.theme.bold
import ai.kkaeugi.engine.Gear
import ai.kkaeugi.engine.Telltale
import ai.kkaeugi.engine.TurnSignalState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ---------------- 데모 콘솔 ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DemoConsole(vm: WakeViewModel, ui: UiState, modifier: Modifier, onClose: (() -> Unit)? = null) {
    val m = vm.mock
    val c = K.colors
    Row(modifier.background(c.surface)) {
        Box(Modifier.width(KSize.hairline).fillMaxHeight().background(c.line))
    Column(Modifier.padding(KSpace.sm).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("데모 콘솔", style = K.type.sub.bold, color = c.ink, modifier = Modifier.weight(1f))
            onClose?.let { KTextButton("닫기", it) }
        }
        Text("Pleos 가 없는 기기에서 버추얼 콘솔 대신 신호를 넣어요. 판단은 전부 엔진이 해요", style = K.type.note, color = c.sub)
        Spacer(Modifier.height(KSpace.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            KPrimaryButton("시연 시나리오(${DemoScenario.FAMILY_LENGTH_LABEL})", vm::runDemo, Modifier.weight(1f).height(KSize.control), style = K.type.note)
            KSecondaryButton("중단", vm::stopDemo, Modifier.height(KSize.control), style = K.type.note)
        }
        ui.demoStep?.let { Text("진행: $it", style = K.type.note.bold, color = c.ink, modifier = Modifier.padding(top = KSpace.xs)) }
        Spacer(Modifier.height(KSpace.xs))

        Section("기어")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("P") { m.gear(Gear.PARK) }; Chip("R") { m.gear(Gear.REVERSE) }; Chip("N") { m.gear(Gear.NEUTRAL) }; Chip("D") { m.gear(Gear.DRIVE) }
        }
        Section("공조")
        // 지금 차 상태. 환기에 동의하면 내외기와 풍량이 실제로 바뀌는 것이 여기 보인다
        Text(cabinStateLine(ui), style = K.type.note, color = c.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("내기순환 ON") { m.recirculation(true) }; Chip("내기순환 OFF") { m.recirculation(false) }
            Chip("공조 전원 OFF") { m.hvacPower(false) }; Chip("공조 전원 ON") { m.hvacPower(true) }
        }
        Section("탑승 인원 (숨 모델)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("1명") { m.occupants(1) }; Chip("2명") { m.occupants(2) }; Chip("3명") { m.occupants(3) }; Chip("4명") { m.occupants(4) }
            Chip("풍량 0/8") { m.fanSpeed(0) }; Chip("풍량 3/8") { m.fanSpeed(3) }; Chip("풍량 6/8") { m.fanSpeed(6) }
            Chip("창문 0%") { m.windowOpen(0) }; Chip("창문 15%") { m.windowOpen(15) }
        }
        Section("자리별 값·문 (자리 설정, 앉은 시간)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("가족 4명 자리") { m.seats(DemoScenario.SEATS_FAMILY); m.occupants(4); m.frontPassenger(true) }
            Chip("혼자 자리") { m.seats(DemoScenario.SEATS_SOLO); m.occupants(1); m.frontPassenger(false) }
            Chip("자리별 값 없음") { m.seats(null) }
            Chip("문 열림") { m.door(true) }; Chip("문 닫힘") { m.door(false) }
            Chip("트립시간 +90분(걷기 쉼)") { m.addTripMinutes(90) }
        }
        Section("맡기기·조수석·차 자동 공조·도착 예정")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("맡기기 켜기(예고 장면)") { vm.setTrustDelegated(true) }; Chip("맡기기 끄기") { vm.setTrustDelegated(false) }
            Chip("조수석 태우기") { m.frontPassenger(true) }; Chip("조수석 비우기") { m.frontPassenger(false) }
            Chip("차 자동 공조 ON") { m.autoClimate(true) }; Chip("차 자동 공조 OFF") { m.autoClimate(false) }
            Chip("도착 4분 전") { m.eta(4) }; Chip("도착 40분 전") { m.eta(40) }; Chip("목적지 없음") { m.eta(null) }
        }
        Section("바깥 미세먼지·시각 (숨 설계)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            PmChoice.entries.forEach { choice -> KChip(choice.label, { vm.setPmChoice(choice) }, selected = ui.pmChoice == choice) }
            Chip("시각: 새벽 3시") { m.clock(3 * 60) }; Chip("시각: 오후 3시") { m.clock(15 * 60) }; Chip("시각: 실제") { m.clock(null) }
        }
        Section("핸들")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("핸들 급보정 3회") { vm.demoSteering() }
        }
        Section("쉼터")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("쉼터 도착(300m)") { vm.tracker.mockWithin() }
            Chip("쉼터 지나침") { vm.tracker.mockPassed() }
        }
        Section("경고등")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("DRIVER_ATTENTION ON") { m.telltale(Telltale.DRIVER_ATTENTION, true) }
            Chip("DRIVER_ATTENTION OFF") { m.telltale(Telltale.DRIVER_ATTENTION, false) }
            Chip("LKA 점멸") { m.telltale(Telltale.LKA, true, blink = true); m.telltale(Telltale.LKA, false) }
            Chip("LKA 상시 ON") { m.telltale(Telltale.LKA, true) }; Chip("LKA OFF") { m.telltale(Telltale.LKA, false) }
            Chip("FCA ON") { m.telltale(Telltale.FCA, true) }; Chip("FCA OFF") { m.telltale(Telltale.FCA, false) }
            Chip("주차브레이크 ON") { m.telltale(Telltale.PARKING_BRAKE, true) }; Chip("주차브레이크 OFF") { m.telltale(Telltale.PARKING_BRAKE, false) }
        }
        Section("날씨·등화·방향지시등")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("비 ON(와이퍼)") { m.wiper(true) }; Chip("비 OFF") { m.wiper(false) }
            Chip("전조등 ON") { m.lowBeam(true) }; Chip("전조등 OFF") { m.lowBeam(false) }
            Chip("깜빡이 좌") { m.turnSignal(TurnSignalState.LEFT) }; Chip("깜빡이 우") { m.turnSignal(TurnSignalState.RIGHT) }; Chip("깜빡이 끔") { m.turnSignal(TurnSignalState.OFF) }
        }
        Section("트립 시간·위치")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(KSpace.xs), verticalArrangement = Arrangement.spacedBy(KSpace.xs)) {
            Chip("트립시간 +30분") { m.addTripMinutes(30) }
            Chip("트립시간 +120분") { m.addTripMinutes(120) }
            Chip("위치: 경부선 칠곡(모의 기본)") { m.location(35.95, 128.45, 95.0) }
            Chip("위치: 경부선 안성") { m.location(37.02, 127.22, 100.0) }
        }
    }
    }
}

@Composable
private fun Section(title: String) { Text(title, style = K.type.note, color = K.colors.sub, modifier = Modifier.padding(top = KSpace.sm, bottom = KSpace.xs)) }

@Composable
private fun Chip(text: String, onClick: () -> Unit) = KChip(text, onClick)

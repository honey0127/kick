package com.example.safezone.data.analysis

import com.example.safezone.data.model.AnalysisResult
import com.example.safezone.data.model.ParkingStatus
import kotlinx.coroutines.delay

/**
 * ⚠️ UI 확인용 임시 분석기. 사진을 전혀 보지 않는다.
 *
 * 실제 AI 분석이 연결되기 전까지 결과 화면 3가지 상태를 확인할 수 있도록
 * 요청할 때마다 SAFE → UNSAFE → UNCERTAIN 순서로 정해진 결과를 돌려준다.
 * 모든 결과에 isMock = true를 붙여 화면에서 실제 분석과 구분되게 한다.
 */
class MockPhotoAnalyzer(
    private val delayMillis: Long = 2_000,
) : PhotoAnalyzer {

    private var requestCount = 0

    override suspend fun analyze(photoUri: String): AnalysisResult {
        // 로딩 화면을 확인할 수 있도록 분석하는 척 잠시 기다린다.
        delay(delayMillis)
        val status = ParkingStatus.entries[requestCount % ParkingStatus.entries.size]
        requestCount++
        return AnalysisResult(status = status, findings = sampleFindings(status), isMock = true)
    }

    // 명세서에 예시로 나온 근거 문구. 실제 분석 결과가 아니다.
    private fun sampleFindings(status: ParkingStatus): List<String> = when (status) {
        ParkingStatus.SAFE -> listOf(
            "보행 공간 침범이 확인되지 않음",
            "점자블록 침범이 확인되지 않음",
            "출입구 및 통행 방해가 확인되지 않음",
        )
        ParkingStatus.UNSAFE -> listOf(
            "보행 공간 침범 가능성",
            "점자블록 침범 가능성",
            "통행 방해 가능성",
        )
        ParkingStatus.UNCERTAIN -> listOf(
            "사진 촬영 각도",
            "킥보드 주변 공간 확인 어려움",
            "이미지 품질 부족",
        )
    }
}

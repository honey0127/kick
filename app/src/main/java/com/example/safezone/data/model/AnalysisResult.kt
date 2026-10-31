package com.example.safezone.data.model

/** 주차 상태 판단 결과. 결과 화면은 이 값 하나로 아이콘·색·문구·버튼을 정한다. */
enum class ParkingStatus {
    /** 주차 적합 가능성 */
    SAFE,

    /** 주차 부적합 가능성 */
    UNSAFE,

    /** 판단 보류 (사진만으로 판단하기 어려움) */
    UNCERTAIN,
}

/**
 * 분석 결과.
 *
 * @property findings 분석 근거. 분석기가 실제로 확인해 돌려준 항목만 담는다.
 *   결과 화면은 이 목록에 있는 것만 표시하고, 없는 항목을 지어내 채우지 않는다.
 * @property isMock 실제 AI 분석이 아닌 UI 확인용 임시 결과인지. true면 결과 화면에 그 사실을 표시한다.
 */
data class AnalysisResult(
    val status: ParkingStatus,
    val findings: List<String>,
    val isMock: Boolean,
)

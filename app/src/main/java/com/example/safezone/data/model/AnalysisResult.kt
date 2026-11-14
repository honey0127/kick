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

/** 판정 근거 이미지의 종류. 결과 화면이 종류에 맞는 설명을 붙인다. */
enum class EvidenceImageKind {
    /** 사진 위에 AI가 인식한 영역(보도·차도·점자블록·주차한 기기)을 색으로 칠한 그림 */
    OVERLAY,

    /** 위에서 내려다본 그림(조감도). 점자블록으로 실제 길이를 잴 수 있었을 때만 온다. */
    BIRDS_EYE,
}

/**
 * 분석 서버가 판정 근거로 보내 준 이미지 한 장.
 *
 * @property jpeg JPEG 파일 내용 그대로. 화면에 그릴 때 디코딩한다.
 */
class EvidenceImage(val kind: EvidenceImageKind, val jpeg: ByteArray)

/**
 * 분석 결과.
 *
 * @property findings 분석 근거. 분석기가 실제로 확인해 돌려준 항목만 담는다.
 *   결과 화면은 이 목록에 있는 것만 표시하고, 없는 항목을 지어내 채우지 않는다.
 * @property isMock 실제 AI 분석이 아닌 UI 확인용 임시 결과인지. true면 결과 화면에 그 사실을 표시한다.
 * @property evidenceImages 분석 서버가 보내 준 판정 근거 이미지. 임시 분석기 결과에는 없다.
 */
data class AnalysisResult(
    val status: ParkingStatus,
    val findings: List<String>,
    val isMock: Boolean,
    val evidenceImages: List<EvidenceImage> = emptyList(),
)

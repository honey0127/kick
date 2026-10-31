package com.example.safezone.data.analysis

import com.example.safezone.data.model.AnalysisResult

/**
 * 주차 사진을 분석하는 기능의 약속(인터페이스).
 * 화면은 이 인터페이스만 알고, 실제 AI 분석이 준비되면 구현체만 바꿔 끼운다.
 */
interface PhotoAnalyzer {
    /**
     * @param photoUri 분석할 사진의 URI 문자열 (content:// 또는 file://)
     * @throws Exception 분석에 실패한 경우. 화면은 오류 안내와 재시도를 보여 준다.
     */
    suspend fun analyze(photoUri: String): AnalysisResult
}

package com.example.planna

/**
 * Cheap pre-filter for messaging notifications. Runs offline, no network.
 * Goal: skip ~70-80% of obviously non-schedule notifications (ads, receipts,
 * delivery confirmations) so we don't waste an LLM call on each one.
 *
 * Permissive on purpose — when in doubt, send to backend. The LLM is the
 * source of truth via has_schedule.
 */
object ScheduleHeuristic {

    // 발신자/문구로 명백히 일정과 무관한 알림. 빠르게 거름.
    private val SKIP_PATTERNS = listOf(
        "[광고]", "(광고)", "광고 ", "Web발신",
        "할인", "쿠폰", "프로모션", "이벤트 안내",
        "결제 완료", "결제완료", "결제 승인", "승인 금액",
        "주문 완료", "주문완료", "배송 완료", "배송완료",
        "출고", "배송 시작", "배송중",
        "충전 완료", "포인트 적립", "마일리지",
        "구독 갱신", "구독 안내",
        "월 사용량", "데이터 알림",
        "스팸", "spam",
    ).map { it.lowercase() }

    // 일정의 핵심 신호 — 시간/날짜 표현
    private val DATE_TIME_PATTERNS = listOf(
        Regex("""\d{1,2}\s*[시:]\s*\d{0,2}"""),               // 3시, 14:30
        Regex("""\d{1,2}\s*월\s*\d{1,2}\s*일"""),             // 5월 7일
        Regex("""\d{4}[-./]\d{1,2}[-./]\d{1,2}"""),          // 2026-05-07
        Regex("""(오전|오후|AM|PM|am|pm)\s*\d{1,2}"""),       // 오후 3
        Regex("""(내일|모레|글피|오늘 저녁|오늘 밤|이번 주|다음 주|이번 달|다음 달)"""),
        Regex("""(월|화|수|목|금|토|일)요일"""),
        Regex("""\b\d{1,2}/\d{1,2}\b"""),                    // 5/7
    )

    // 일정/이벤트 명사·동사
    private val EVENT_KEYWORDS = listOf(
        "회의", "미팅", "약속", "예약", "면접", "면담", "상담",
        "참석", "참여", "참가", "방문", "오시", "와주", "만나", "뵙",
        "교육", "세미나", "전시", "박람회", "워크숍", "워크샵",
        "강의", "강연", "특강", "수업", "공연", "콘서트",
        "행사", "포럼", "컨퍼런스", "심포지엄", "발표",
        "출장", "여행", "출발", "도착", "비행",
        "병원", "치과", "검진", "진료", "수술",
        "결혼식", "장례", "돌잔치", "환갑",
        "신청", "접수", "마감", "오리엔테이션",
        "초대", "초청",
    )

    fun looksLikeSchedule(title: String, text: String): Boolean {
        val combined = ("$title $text").trim()
        if (combined.isBlank()) return false

        val lower = combined.lowercase()
        if (SKIP_PATTERNS.any { lower.contains(it) }) return false

        val hasDateTime = DATE_TIME_PATTERNS.any { it.containsMatchIn(combined) }
        val hasEvent = EVENT_KEYWORDS.any { combined.contains(it) }

        // 시간/날짜 패턴이 있고 + 일정 키워드도 있으면 일정으로 판단
        // 시간/날짜만 있는 경우도 통과 (LLM이 최종 판단)
        return hasDateTime || hasEvent
    }
}

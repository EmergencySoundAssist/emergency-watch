package com.example.emergencywatch.ble

import java.util.UUID

/**
 * Jetson <-> 워치 BLE 통신 '약속'(프로토콜).
 *
 * 이 파일의 UUID와 바이트 포맷은 **Jetson 쪽 Python 코드와 반드시 동일**해야 한다.
 * (Jetson: emergency-sound-assist/notify/protocol.py 에 같은 값으로 작성 예정)
 *
 * ── 역할 분담 ────────────────────────────────────────────────
 *   워치   = GATT 서버(주변기기). SERVICE_UUID 를 광고(advertise)한다.
 *   Jetson = 클라이언트(중앙기기). 워치를 찾아 연결 후 ALERT_CHAR_UUID 에 4바이트를 write.
 *   워치가 write 를 받으면 -> 진동.
 *
 * ── 페이로드(4바이트) ────────────────────────────────────────
 *   byte[0] sound   : 0=일반, 1=사이렌, 2=경적
 *   byte[1] dir     : 0=전방, 1=후방, 2=좌, 3=우, 0xFF=미상
 *   byte[2] motion  : 0=접근, 1=멀어짐, 2=유지, 0xFF=미상
 *   byte[3] conf    : 신뢰도 0~100 (퍼센트)
 */
object AlertProtocol {

    // 우리만의 고유 식별자. Jetson 코드에도 똑같이 박아야 서로를 알아본다.
    val SERVICE_UUID: UUID = UUID.fromString("e7a10000-2c5f-4b9a-8d3e-1f0a9b8c7d60")
    val ALERT_CHAR_UUID: UUID = UUID.fromString("e7a10001-2c5f-4b9a-8d3e-1f0a9b8c7d60")

    // 광고에 실어 보내는 기기 이름(선택). Jetson이 이름으로도 식별 가능.
    const val DEVICE_NAME = "EmergencyWatch"

    /** 4바이트 페이로드를 사람이 다루기 쉬운 객체로 디코드. 형식이 안 맞으면 null. */
    fun decode(bytes: ByteArray?): AlertData? {
        if (bytes == null || bytes.size < 4) return null
        return AlertData(
            sound = Sound.from(bytes[0].toInt() and 0xFF),
            direction = Direction.from(bytes[1].toInt() and 0xFF),
            motion = Motion.from(bytes[2].toInt() and 0xFF),
            confidence = bytes[3].toInt() and 0xFF,
        )
    }
}

/** 디코드된 알림 1건. */
data class AlertData(
    val sound: Sound,
    val direction: Direction,
    val motion: Motion,
    val confidence: Int,
) {
    /** 워치 화면에 보여줄 한국어 한 줄. 예: "사이렌, 우측, 접근 (87%)" */
    fun toKorean(): String =
        "${sound.ko}, ${direction.ko}, ${motion.ko} (${confidence}%)"

    /** 긴급 상황인지(진동 줄지 말지 판단). */
    val isEmergency: Boolean get() = sound == Sound.SIREN || sound == Sound.HORN
}

enum class Sound(val code: Int, val ko: String) {
    NORMAL(0, "일반"), SIREN(1, "사이렌"), HORN(2, "경적"), UNKNOWN(0xFF, "미상");
    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class Direction(val code: Int, val ko: String) {
    FRONT(0, "전방"), REAR(1, "후방"), LEFT(2, "좌측"), RIGHT(3, "우측"), UNKNOWN(0xFF, "미상");
    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class Motion(val code: Int, val ko: String) {
    APPROACHING(0, "접근"), RECEDING(1, "멀어짐"), STEADY(2, "유지"), UNKNOWN(0xFF, "미상");
    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

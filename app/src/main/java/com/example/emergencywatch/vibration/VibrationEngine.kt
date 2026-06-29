package com.example.emergencywatch.vibration

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.emergencywatch.ble.AlertData
import com.example.emergencywatch.ble.Direction
import com.example.emergencywatch.ble.Motion

/**
 * 상황별 고유 진동 패턴 엔진 (요구사항 ②).
 *
 * 설계 원칙:
 *  - 방향(전/후/좌/우)  -> 서로 다른 '리듬'(진동 마디 개수·길이)으로 구분
 *  - 접근/멀어짐        -> '세기'와 '반복'으로 급박함 표현 (접근=강하고 반복, 멀어짐=약하게 1회)
 *
 * VibrationEffect.createWaveform(timings, amplitudes, repeat) 사용:
 *  - timings[i]    : i번째 구간 지속시간(ms). 짝수 인덱스=대기(off), 홀수=진동(on) 이 아니라
 *                    amplitudes 와 1:1로 매칭된다. amplitude 0 이면 그 구간은 쉬는 구간.
 *  - amplitudes[i] : 그 구간의 세기 0~255 (0=정지). 기기가 세기조절 미지원이면 0/255로만 동작.
 *  - repeat        : -1 = 반복 안 함 / 0 = 인덱스0부터 무한반복
 */
class VibrationEngine(context: Context) {

    private val vibrator: Vibrator = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /** 이 기기가 진동 세기 조절을 지원하는지. 미지원이면 모든 amplitude는 사실상 on/off. */
    val hasAmplitudeControl: Boolean get() = vibrator.hasAmplitudeControl()

    /** 알림 1건에 대응하는 진동 재생. */
    fun play(alert: AlertData) {
        if (!alert.isEmergency) return          // 긴급음 아니면 진동 안 함
        play(alert.direction, alert.motion)
    }

    /** 방향·움직임으로 패턴을 구성해 재생. (테스트 버튼에서도 직접 호출) */
    fun play(direction: Direction, motion: Motion) {
        val base = directionPattern(direction)          // 방향별 리듬
        val amp = motionAmplitude(motion)               // 움직임별 세기
        val repeat = if (motion == Motion.APPROACHING) 0 else -1  // 접근이면 무한반복(끊을 때까지)

        val amplitudes = base.map { (_, on) -> if (on) amp else 0 }.toIntArray()
        val timings = base.map { (ms, _) -> ms }.toLongArray()

        val effect = VibrationEffect.createWaveform(timings, amplitudes, repeat)
        vibrator.cancel()
        vibrator.vibrate(effect)
    }

    /** 진동 멈춤 (접근 무한반복을 끊을 때 사용). */
    fun stop() = vibrator.cancel()

    /**
     * 방향별 고유 리듬.
     * 각 항목 = (지속시간 ms, 진동 여부). 진동 여부 false 면 그만큼 쉬는 구간.
     *  - 전방 : 짧게 2번  (· ·)
     *  - 후방 : 길게 1번   (—)
     *  - 좌측 : 짧고-길게  (· —)
     *  - 우측 : 길고-짧게  (— ·)
     */
    private fun directionPattern(direction: Direction): List<Pair<Long, Boolean>> = when (direction) {
        Direction.FRONT -> listOf(120L to true, 100L to false, 120L to true, 300L to false)
        Direction.REAR  -> listOf(450L to true, 300L to false)
        Direction.LEFT  -> listOf(120L to true, 100L to false, 400L to true, 300L to false)
        Direction.RIGHT -> listOf(400L to true, 100L to false, 120L to true, 300L to false)
        Direction.UNKNOWN -> listOf(200L to true, 150L to false, 200L to true, 300L to false)
    }

    /** 움직임별 세기(0~255). 접근=강함, 유지=중간, 멀어짐=약함. */
    private fun motionAmplitude(motion: Motion): Int = when (motion) {
        Motion.APPROACHING -> 255
        Motion.STEADY      -> 160
        Motion.RECEDING    -> 90
        Motion.UNKNOWN     -> 160
    }
}

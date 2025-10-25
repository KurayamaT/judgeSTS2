// GaitAnalyzer.kt  （新規 / 方針A：Prominence + Width 導入版）
// 元コードへの無許可変更は行っていません。IMUService 等には追加・削除を加えていません。
// 本ファイルは独立モジュールとして追加してください。

package com.example.judgeSTS_ver2

import kotlin.math.*

class GaitAnalyzer(
    private val fs: Int = 120,
    private val standThresholdDeg: Double = 65.0,
    private val sitThresholdDeg: Double = 30.0
) {
    // ---- セッション全体のバッファ（累計算出のために全履歴保持） ----
    private val ts = ArrayList<Long>()
    private val ax = ArrayList<Float>()
    private val ay = ArrayList<Float>()
    private val az = ArrayList<Float>()

    // ---- バターワース係数（Fs=120固定・MATLAB相当） ----
    // 角度用 LPF(2 Hz, order 4)
    private val bLP = doubleArrayOf(
        6.57854320e-06, 2.63141728e-05, 3.94712592e-05, 2.63141728e-05, 6.57854320e-06
    )
    private val aLP = doubleArrayOf(
        1.0, -3.72641450, 5.21604820, -3.25001826, 0.76048982
    )

    // 歩行用 BPF(0.5–6 Hz, order 4)
    private val bBP = doubleArrayOf(
        0.00030291, 0.0, -0.00121164, 0.0, 0.00181746, 0.0, -0.00121164, 0.0, 0.00030291
    )
    private val aBP = doubleArrayOf(
        1.0, -7.21869892, 22.84720478, -41.41616376, 47.03681425, -34.27473824,
        15.64958851, -4.09373642, 0.46972981
    )

    fun append(tMillis: Long, ax_: Float, ay_: Float, az_: Float) {
        ts.add(tMillis); ax.add(ax_); ay.add(ay_); az.add(az_)
    }

    data class Result(
        val sitToStandCount: Int,
        val stepCount: Int
    )

    fun compute(): Result {
        val n = ts.size
        if (n < fs * 3) return Result(0, 0) // データ不足時は0

        // ---------- 角度計算（LPF後） ----------
        val absAx = DoubleArray(n) { abs(ax[it].toDouble()) }
        val absAy = DoubleArray(n) { abs(ay[it].toDouble()) }
        val absAz = DoubleArray(n) { abs(az[it].toDouble()) }

        val fltAx = filtfilt(bLP, aLP, absAx)
        val fltAy = filtfilt(bLP, aLP, absAy)
        val fltAz = filtfilt(bLP, aLP, absAz)

        val thighAngle = DoubleArray(n) {
            val denom = sqrt(fltAx[it] * fltAx[it] + fltAz[it] * fltAz[it]).coerceAtLeast(1e-9)
            abs(Math.toDegrees(atan(fltAy[it] / denom)))
        }

        // ---------- 初期状態 ----------
        var state = if (thighAngle.take(min(n, fs)).average() < 40.0) "sitting" else "standing"

        // ---------- 起立/着座検出（0.5s平均条件） ----------
        val stdTimes = ArrayList<Int>() // 座位→起立
        val sitTimes = ArrayList<Int>() // 立位→座位
        val halfSec = (0.5 * fs).toInt()

        for (i in 1 until n) {
            // sitting → standing crossing
            if (state == "sitting" && thighAngle[i - 1] < standThresholdDeg && thighAngle[i] >= standThresholdDeg) {
                val endIdx = min(n - 1, i + halfSec)
                val meanSeg = avg(thighAngle, i, endIdx)
                if (meanSeg >= standThresholdDeg) {
                    stdTimes.add(i)
                    state = "standing"
                }
            }
            // standing → sitting crossing
            if (state == "standing" && thighAngle[i - 1] > sitThresholdDeg && thighAngle[i] <= sitThresholdDeg) {
                val endIdx = min(n - 1, i + halfSec)
                val meanSeg = avg(thighAngle, i, endIdx)
                if (meanSeg <= sitThresholdDeg) {
                    sitTimes.add(i)
                    state = "sitting"
                }
            }
        }

        // ---------- 近接イベント（2秒）マージ ----------
        fun mergeClose(events: List<Int>, win: Int): List<Int> {
            if (events.isEmpty()) return events
            val out = ArrayList<Int>()
            var last = events[0]
            out.add(last)
            for (k in 1 until events.size) {
                if (events[k] - last > win) {
                    last = events[k]
                    out.add(last)
                }
            }
            return out
        }
        val mergeWin = 2 * fs
        val stdM = mergeClose(stdTimes, mergeWin)
        val sitM = mergeClose(sitTimes, mergeWin)

        // ---------- 着座→起立ペア（30°未満を一度でも通過） ----------
        val validSit = ArrayList<Int>()
        val validStd = ArrayList<Int>()
        if (sitM.isNotEmpty() && stdM.isNotEmpty()) {
            val m = min(sitM.size - 1, stdM.size - 1)
            for (i in 0..m) {
                val s = sitM[i]
                val e = stdM[min(i + 1, stdM.size - 1)]
                if (e > s && anyBelow(thighAngle, s, e, sitThresholdDeg)) {
                    validSit.add(s)
                    validStd.add(e)
                }
            }
        }
        // 起立回数（座位→起立の確定イベント数）
        val sitToStandCount = validStd.size

        // ---------- standing マスク ----------
        val standingMask = BooleanArray(n) { thighAngle[it] > 40.0 }
        val allowedIntervals = maskToIntervals(standingMask)

        // ---------- 歩行検出（Y軸、BPF→abs→ピーク） ----------
        val y = DoubleArray(n) { ay[it].toDouble() }
        val yInterp = interpolateLinearNaN(y)
        val yBP = filtfilt(bBP, aBP, yInterp)
        val yAbs = DoubleArray(n) { abs(yBP[it]) }

        // --- MATLAB の findpeaks に寄せる条件（方針A） ---
        val minPeakHeight = 11.8            // ≈ 1.2 g
        val minProminence = 6.0             // ≈ 0.6 g
        val minWidth = (0.30 * fs).toInt()  // ≈ 0.3 s
        val minPeakDist = (fs / 2).toInt().coerceAtLeast(1)

        val allPeaks = ArrayList<Int>()
        for ((s, e) in allowedIntervals) {
            if (e - s + 1 < fs / 2) continue
            var lastIdx = -1_000_000
            var k = s + 1
            while (k <= e - 1) {
                if (k - lastIdx < minPeakDist) { k++; continue }

                if (isPeakWithProminenceAndWidth(
                        idx = k,
                        arr = yAbs,
                        start = s,
                        end = e,
                        minHeight = minPeakHeight,
                        minProm = minProminence,
                        minWidthSamples = minWidth
                    )
                ) {
                    allPeaks.add(k)
                    lastIdx = k
                    k += minPeakDist
                    continue
                }
                k++
            }
        }

        // ---------- 除外1：起立/着座 ±2秒 ----------
        val exclMask = BooleanArray(n)
        val exclWin = (2.0 * fs).toInt()
        for (idx in validStd) {
            val ss = (idx - exclWin).coerceAtLeast(0)
            val ee = (idx + exclWin).coerceAtMost(n - 1)
            for (i in ss..ee) exclMask[i] = true
        }
        for (idx in validSit) {
            val ss = (idx - exclWin).coerceAtLeast(0)
            val ee = (idx + exclWin).coerceAtMost(n - 1)
            for (i in ss..ee) exclMask[i] = true
        }

        // ---------- 除外2：ステップ±0.1秒が全て角度<40° ----------
        val stepWin = (0.1 * fs).toInt().coerceAtLeast(1)
        val kept = ArrayList<Int>()
        for (p in allPeaks) {
            if (exclMask[p]) continue
            val ss = (p - stepWin).coerceAtLeast(0)
            val ee = (p + stepWin).coerceAtMost(n - 1)
            if (anyGE(thighAngle, ss, ee, 40.0)) kept.add(p)
        }
        val stepCount = kept.size

        return Result(sitToStandCount = sitToStandCount, stepCount = stepCount)
    }

    // ===== utils =====

    private fun maskToIntervals(mask: BooleanArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < mask.size) {
            while (i < mask.size && !mask[i]) i++
            if (i >= mask.size) break
            val s = i
            while (i < mask.size && mask[i]) i++
            val e = i - 1
            out.add(Pair(s, e))
        }
        return out
    }

    private fun interpolateLinearNaN(x: DoubleArray): DoubleArray {
        // 今回は NaN はほぼ来ない想定だが安全策として実装
        val y = x.copyOf()
        var i = 0
        while (i < y.size) {
            if (y[i].isNaN()) {
                var j = i + 1
                while (j < y.size && y[j].isNaN()) j++
                val v0 = if (i == 0) 0.0 else y[i - 1]
                val v1 = if (j >= y.size) v0 else y[j]
                val span = (j - i).coerceAtLeast(1)
                for (k in 0 until span) {
                    y[i + k] = v0 + (v1 - v0) * (k.toDouble() / span.toDouble())
                }
                i = j
            } else i++
        }
        return y
    }

    // IIR 1D（前後適用の簡易 filtfilt）
    private fun filtfilt(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
        val y1 = lfilter(b, a, x)
        val rev = y1.reversedArray()
        val y2 = lfilter(b, a, rev)
        return y2.reversedArray()
    }

    private fun lfilter(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
        val na = a.size; val nb = b.size; val n = x.size
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            for (k in 0 until nb) {
                val xi = if (i - k >= 0) x[i - k] else 0.0
                acc += b[k] * xi
            }
            for (k in 1 until na) {
                val yi = if (i - k >= 0) y[i - k] else 0.0
                acc -= a[k] * yi
            }
            y[i] = acc / a[0]
        }
        return y
    }

    private fun avg(arr: DoubleArray, s: Int, e: Int): Double {
        var sum = 0.0
        var cnt = 0
        val ss = s.coerceAtLeast(0)
        val ee = e.coerceAtMost(arr.size - 1)
        for (i in ss..ee) { sum += arr[i]; cnt++ }
        return if (cnt > 0) sum / cnt else 0.0
    }

    private fun anyBelow(arr: DoubleArray, s: Int, e: Int, thr: Double): Boolean {
        val ss = s.coerceAtLeast(0)
        val ee = e.coerceAtMost(arr.size - 1)
        for (i in ss..ee) if (arr[i] < thr) return true
        return false
    }

    private fun anyGE(arr: DoubleArray, s: Int, e: Int, thr: Double): Boolean {
        val ss = s.coerceAtLeast(0)
        val ee = e.coerceAtMost(arr.size - 1)
        for (i in ss..ee) if (arr[i] >= thr) return true
        return false
    }

    // --- 方針A：Prominence + Width 付きピーク判定（MATLAB findpeaks 近似） ---
    private fun isPeakWithProminenceAndWidth(
        idx: Int,
        arr: DoubleArray,
        start: Int,
        end: Int,
        minHeight: Double,
        minProm: Double,
        minWidthSamples: Int
    ): Boolean {
        if (idx <= start || idx >= end) return false
        val v = arr[idx]
        if (v < minHeight) return false
        // 局所最大
        if (!(v > arr[idx - 1] && v >= arr[idx + 1])) return false

        // Prominence（左右の下がり具合を見る）
        val leftMin = localMin(arr, max(start, idx - minWidthSamples), idx)
        val rightMin = localMin(arr, idx, min(end, idx + minWidthSamples))
        val prom = v - max(leftMin, rightMin)
        if (prom < minProm) return false

        // Width（左右に一定幅の谷が存在する＝歩行っぽい周期）
        val half = v - minProm / 2.0
        val leftCross = crossLeft(arr, idx, half, start)
        val rightCross = crossRight(arr, idx, half, end)
        val width = (rightCross - leftCross).coerceAtLeast(0)
        if (width < minWidthSamples) return false

        return true
    }

    private fun localMin(arr: DoubleArray, sIn: Int, eIn: Int): Double {
        val s = sIn.coerceAtLeast(0)
        val e = eIn.coerceAtMost(arr.size - 1)
        var m = Double.POSITIVE_INFINITY
        for (i in s..e) if (arr[i] < m) m = arr[i]
        return if (m.isFinite()) m else arr[s]
    }

    private fun crossLeft(arr: DoubleArray, idx: Int, level: Double, start: Int): Int {
        var i = idx
        while (i > start && arr[i] > level) i--
        return i
    }

    private fun crossRight(arr: DoubleArray, idx: Int, level: Double, end: Int): Int {
        var i = idx
        while (i < end && arr[i] > level) i++
        return i
    }
}

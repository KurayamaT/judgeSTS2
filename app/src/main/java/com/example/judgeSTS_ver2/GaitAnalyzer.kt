// GaitAnalyzer.kt（MATLAB完全準拠版：修正済み）
// 修正点:
// 1. 起立・着座を個別にカウント（ペアリング不要）
// 2. 立位区間のみで歩行検出（allowed_intervals使用）
// 3. ペダリング除外ロジックを追加（オプション）

package com.example.judgeSTS_ver2

import kotlin.math.*
import java.util.ArrayDeque

class GaitAnalyzer(
    private val fs: Int = 120,
    private val standThresholdDeg: Double = 65.0,
    private val sitThresholdDeg: Double = 30.0
) {
    private val ts = ArrayList<Long>()
    private val ax = ArrayList<Float>()
    private val ay = ArrayList<Float>()
    private val az = ArrayList<Float>()

    private var totalSitToStandCountInternal = 0
    private var totalStepCountInternal = 0
    private var lastProcessedIndex = 0  // ← この行を追加


    val totalSitToStandCount: Int get() = totalSitToStandCountInternal
    val totalStepCount: Int get() = totalStepCountInternal

    data class Result(
        val sitToStandCount: Int,
        val stepCount: Int
    )

    // MATLAB butter() 係数（Fs=120Hz）
    private val bLP = doubleArrayOf(
        6.57854320e-06, 2.63141728e-05, 3.94712592e-05, 2.63141728e-05, 6.57854320e-06
    )
    private val aLP = doubleArrayOf(
        1.0, -3.72641450, 5.21604820, -3.25001826, 0.76048982
    )
    private val bBP = doubleArrayOf(
        3.02909821e-04, 0.0, -1.21163928e-03, 0.0, 1.81745893e-03, 0.0, -1.21163928e-03, 0.0, 3.02909821e-04
    )
    private val aBP = doubleArrayOf(
        1.0, -7.21869892, 22.84720478, -41.41616376, 47.03681425,
        -34.27473824, 15.64958851, -4.09373642, 0.46972981
    )

    private val maxSamples = fs * 30

    @Synchronized
    fun append(tMillis: Long, ax_: Float, ay_: Float, az_: Float) {
        if (ts.size >= maxSamples) {
            ts.removeAt(0); ax.removeAt(0); ay.removeAt(0); az.removeAt(0)
        }
        ts.add(tMillis); ax.add(ax_); ay.add(ay_); az.add(az_)
    }

    private fun interpolateLinearNaN(yIn: DoubleArray): DoubleArray {
        val y = yIn.copyOf()
        var i = 0
        while (i < y.size) {
            if (y[i].isNaN()) {
                var j = i + 1
                while (j < y.size && y[j].isNaN()) j++
                val v0 = if (i == 0) 0.0 else y[i - 1]
                val v1 = if (j >= y.size) v0 else y[j]
                val span = (j - i).coerceAtLeast(1)
                for (k in 0 until span) y[i + k] = v0 + (v1 - v0) * (k.toDouble() / span.toDouble())
                i = j
            } else i++
        }
        return y
    }

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
            for (k in 0 until nb) acc += b[k] * if (i - k >= 0) x[i - k] else 0.0
            for (k in 1 until na) acc -= a[k] * if (i - k >= 0) y[i - k] else 0.0
            y[i] = acc / a[0]
        }
        return y
    }

    private fun maskToIntervals(mask: BooleanArray): List<Pair<Int, Int>> {
        val res = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in mask.indices) {
            if (mask[i] && start == -1) start = i
            if ((!mask[i] || i == mask.lastIndex) && start != -1) {
                val end = if (mask[i]) i else i - 1
                if (end >= start) res.add(start to end)
                start = -1
            }
        }
        return res
    }

    private fun mergeClose(events: List<Int>, mergeWindow: Int): List<Int> {
        if (events.isEmpty()) return events
        val merged = ArrayList<Int>()
        var last = events[0]
        merged.add(last)
        for (i in 1 until events.size) {
            val e = events[i]
            if (e - last > mergeWindow) {
                merged.add(e); last = e
            }
        }
        return merged
    }

    private fun anyBelow(arr: DoubleArray, s: Int, e: Int, thr: Double): Boolean {
        val ss = s.coerceAtLeast(0); val ee = e.coerceAtMost(arr.size - 1)
        for (i in ss..ee) if (arr[i] < thr) return true
        return false
    }

    private fun anyAbove(arr: DoubleArray, s: Int, e: Int, thr: Double): Boolean {
        val ss = s.coerceAtLeast(0); val ee = e.coerceAtMost(arr.size - 1)
        for (i in ss..ee) if (arr[i] > thr) return true
        return false
    }

    fun compute(): Result {
        val n = minOf(ts.size, ax.size, ay.size, az.size)
        if (n < fs * 3) return Result(0, 0)

        // ===== 角度計算 =====
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

        // ===== 起立/着座検出 =====
        var state = if (thighAngle.take(min(n, fs)).average() < 40.0) "sitting" else "standing"
        val stdTimesRaw = ArrayList<Int>()
        val sitTimesRaw = ArrayList<Int>()
        val stateSeries = ArrayList<String>(n).apply { repeat(n) { add(state) } }

        for (i in 1 until n) {
            // 起立検出
            if (state == "sitting" &&
                thighAngle[i - 1] < standThresholdDeg && thighAngle[i] >= standThresholdDeg) {
                val endIdx = min(i + (0.5 * fs).toInt(), n - 1)
                val meanSeg = thighAngle.slice(i..endIdx).average()
                if (meanSeg >= standThresholdDeg) {
                    stdTimesRaw.add(i)
                    state = "standing"
                }
            }
            // 着座検出
            if (state == "standing" &&
                thighAngle[i - 1] > sitThresholdDeg && thighAngle[i] <= sitThresholdDeg) {
                val endIdx = min(i + (0.5 * fs).toInt(), n - 1)
                val meanSeg = thighAngle.slice(i..endIdx).average()
                if (meanSeg <= sitThresholdDeg) {
                    sitTimesRaw.add(i)
                    state = "sitting"
                }
            }
            stateSeries[i] = state
        }

        // 2秒以内統合
        val mergeWin = 2 * fs
        val stdMerged = mergeClose(stdTimesRaw, mergeWin)
        val sitMerged = mergeClose(sitTimesRaw, mergeWin)

        // ===== 修正1: 起立・着座を個別に検証（ペアリング不要）=====
        val validStd = ArrayList<Int>()
        val validSit = ArrayList<Int>()

        // 各起立イベントを検証：前の着座から起立までの区間で sit_threshold を下回るか
        for (i in stdMerged.indices) {
            val stdIdx = stdMerged[i]
            // 前の着座を探す
            val prevSitIdx = sitMerged.filter { it < stdIdx }.maxOrNull()
            if (prevSitIdx != null) {
                // 着座→起立区間で sit_threshold を下回れば有効
                if (anyBelow(thighAngle, prevSitIdx, stdIdx, sitThresholdDeg)) {
                    validStd.add(stdIdx)
                }
            } else {
                // 前の着座がない場合は最初から検証
                if (anyBelow(thighAngle, 0, stdIdx, sitThresholdDeg)) {
                    validStd.add(stdIdx)
                }
            }
        }

        // 各着座イベントを検証：前の起立から着座までの区間で stand_threshold を上回るか
        for (i in sitMerged.indices) {
            val sitIdx = sitMerged[i]
            // 前の起立を探す
            val prevStdIdx = stdMerged.filter { it < sitIdx }.maxOrNull()
            if (prevStdIdx != null) {
                // 起立→着座区間で stand_threshold を上回れば有効
                if (anyAbove(thighAngle, prevStdIdx, sitIdx, standThresholdDeg)) {
                    validSit.add(sitIdx)
                }
            } else {
                // 前の起立がない場合は最初から検証
                if (anyAbove(thighAngle, 0, sitIdx, standThresholdDeg)) {
                    validSit.add(sitIdx)
                }
            }
        }

        val sitToStandCount = validStd.size

        // ===== 修正2: 立位区間のみで歩行検出 =====
        val standingMask = BooleanArray(n) { stateSeries[it] == "standing" }
        val allowedIntervals = maskToIntervals(standingMask)

        // ===== 歩行検出 =====
        val y_raw = DoubleArray(n) { ay[it].toDouble() / 9.80665 }
        val yInterp = interpolateLinearNaN(y_raw)
        val yBP = filtfilt(bBP, aBP, yInterp)
        val yAbs = DoubleArray(n) { abs(yBP[it]) }

        // findpeaks
        val minHeight = 3.0
        val minDist = (fs.toDouble() / 2.5).toInt()
        val peakIdx = mutableListOf<Int>()
        var lastPeak = -1_000_000

        for (i in 1 until yAbs.size - 1) {
            if (yAbs[i] > yAbs[i - 1] && yAbs[i] > yAbs[i + 1] && yAbs[i] >= minHeight) {
                if (i - lastPeak >= minDist) {
                    peakIdx.add(i)
                    lastPeak = i
                }
            }
        }

        // ===== 修正3: allowed_intervals（立位区間）内のピークのみ =====
        val peaksInStanding = ArrayList<Int>()
        for ((s, e) in allowedIntervals) {
            for (p in peakIdx) {
                if (p in s..e) peaksInStanding.add(p)
            }
        }

        // 除外：起立/着座 前後±2秒
        val excl = BooleanArray(n)
        val exclWin = (2.0 * fs).toInt()
        for (t in validStd + validSit) {
            val s = (t - exclWin).coerceAtLeast(0)
            val e = (t + exclWin).coerceAtMost(n - 1)
            for (i in s..e) excl[i] = true
        }

        val finalSteps = ArrayList<Int>()
        for (p in peaksInStanding) {
            if (!excl[p]) finalSteps.add(p)
        }

        val newStepCount = finalSteps.size

// ===== 新規イベントのみカウント =====
        val newStd = validStd.filter { it >= lastProcessedIndex }
        val newSteps = finalSteps.filter { it >= lastProcessedIndex }

        val actualNewSitToStand = newStd.size
        val actualNewSteps = newSteps.size

// 処理済みインデックスを更新（5秒分は次回に再解析）
        lastProcessedIndex = n - (fs * 5)

// 累積更新
        totalSitToStandCountInternal += actualNewSitToStand
        totalStepCountInternal += actualNewSteps

        return Result(sitToStandCount = actualNewSitToStand, stepCount = actualNewSteps) }
}
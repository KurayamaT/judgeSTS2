// GaitAnalyzer.kt（MATLAB準拠版：Fs=121Hz, GPS不使用, ペダリング除外無効）
// ・detectSitStandPositions: 角度スキャン（0.5秒平均）＋2秒以内イベント統合（merge）
// ・detectPeriodicSteps: BPF(0.5–6Hz, 4次) → abs → findPeaks(≥1.2G, MinDistance=fs/2.5)
// ・立位(state=="standing")区間のみ歩行検出
// ・ステップ除外: 起立/着座±2秒, ステップ±0.1秒で角度が全て40°未満
// ・butter(4, 2/(Fs/2), 'low'), butter(4, [0.5,6]/(Fs/2), 'bandpass') を Fs=121 で算出した係数を使用
// ・累積カウンタ（表示用）は現行仕様に合わせて維持（compute()毎に加算）※MATLABの逐次処理化のため

package com.example.judgeSTS_ver2

import kotlin.math.*
import java.util.ArrayDeque

class GaitAnalyzer(
    private val fs: Int = 121,                     // ★ MATLABと同じ121 Hz
    private val standThresholdDeg: Double = 65.0,  // 起立閾値（通過点＋0.5s平均）
    private val sitThresholdDeg: Double = 30.0     // 着座閾値（通過点＋0.5s平均）
) {
    // ---- 全履歴（最新30秒ぶんは解析窓として使用） ----
    private val ts = ArrayList<Long>()
    private val ax = ArrayList<Float>()
    private val ay = ArrayList<Float>()
    private val az = ArrayList<Float>()

    // ---- 累積起立回数・累積歩数（オーバーレイ表示用）----
    private var totalSitToStandCountInternal = 0
    private var totalStepCountInternal = 0

    // 公開Getter
    val totalSitToStandCount: Int get() = totalSitToStandCountInternal
    val totalStepCount: Int get() = totalStepCountInternal

    data class Result(
        val sitToStandCount: Int,  // 今回compute窓で新たに検出した起立回数
        val stepCount: Int         // 今回compute窓で新たに検出した歩数
    )

    // ====== MATLAB butter() と同じ係数（Fs=121Hz）======
    // Low-pass 2 Hz, order 4
    private val bLP = doubleArrayOf(
        6.37057949e-06, 2.54823180e-05, 3.82234770e-05, 2.54823180e-05, 6.37057949e-06
    )
    private val aLP = doubleArrayOf(
        1.0, -3.72867455, 5.22222781, -3.25566572, 0.76221439
    )
    // Band-pass 0.5–6 Hz, order 4
    private val bBP = doubleArrayOf(
        2.9381e-04, 0.0, -1.17523e-03, 0.0, 1.76285e-03, 0.0, -1.17523e-03, 0.0, 2.9381e-04
    )
    private val aBP = doubleArrayOf(
        1.0, -7.22536035, 22.88864124, -41.52666998, 47.20065027,
        -34.42061919, 15.72761984, -4.11695797, 0.47269615
    )

    // ---- 解析バッファ上限（30秒分）----
    private val maxSamples = fs * 30

    fun append(tMillis: Long, ax_: Float, ay_: Float, az_: Float) {
        if (ts.size >= maxSamples) {
            ts.removeAt(0); ax.removeAt(0); ay.removeAt(0); az.removeAt(0)
        }
        ts.add(tMillis); ax.add(ax_); ay.add(ay_); az.add(az_)
    }

    // 欠損補間（NaNがある場合の線形補間）※安全に動作するよう念のため維持
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

    // IIR 前進後退フィルタ（簡易 filtfilt）
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

    // 真偽マスク → 区間 [start,end] 群へ
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

    // 近接イベント統合（≤ mergeWindow）
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

    private fun allBelow(arr: DoubleArray, s: Int, e: Int, thr: Double): Boolean {
        val ss = s.coerceAtLeast(0); val ee = e.coerceAtMost(arr.size - 1)
        for (i in ss..ee) if (arr[i] >= thr) return false
        return true
    }

    fun compute(): Result {
        val n = ts.size
        if (n < fs * 3) return Result(0, 0) // 最低限のデータ長

        // ===== 角度計算（LPF 2 Hz → 大腿角度）=====
        val absAx = DoubleArray(n) { abs(ax[it].toDouble()) }
        val absAy = DoubleArray(n) { abs(ay[it].toDouble()) }
        val absAz = DoubleArray(n) { abs(az[it].toDouble()) }

        val fltAx = filtfilt(bLP, aLP, absAx)
        val fltAy = filtfilt(bLP, aLP, absAy)
        val fltAz = filtfilt(bLP, aLP, absAz)

        // ★ MATLABと同じ：thigh_angle = abs(atan( Ay / sqrt(Ax^2 + Az^2) )) [deg]
        val thighAngle = DoubleArray(n) {
            val denom = sqrt(fltAx[it] * fltAx[it] + fltAz[it] * fltAz[it]).coerceAtLeast(1e-9)
            abs(Math.toDegrees(atan(fltAy[it] / denom)))
        }

        // ===== 起立/着座（通過点＋0.5秒平均の安定条件）=====
        var state = if (thighAngle.take(min(n, fs)).average() < 40.0) "sitting" else "standing"
        val stdTimesRaw = ArrayList<Int>() // 起立（sit->stand）
        val sitTimesRaw = ArrayList<Int>() // 着座（stand->sit）
        val stateSeries = ArrayList<String>(n).apply { repeat(n) { add(state) } }

        for (i in 1 until n) {
            if (state == "sitting" &&
                thighAngle[i - 1] < standThresholdDeg && thighAngle[i] >= standThresholdDeg) {
                val endIdx = min(i + (0.5 * fs).toInt(), n - 1)
                val meanSeg = thighAngle.slice(i..endIdx).average()
                if (meanSeg >= standThresholdDeg) {
                    stdTimesRaw.add(i); state = "standing"
                }
            }
            if (state == "standing" &&
                thighAngle[i - 1] > sitThresholdDeg && thighAngle[i] <= sitThresholdDeg) {
                val endIdx = min(i + (0.5 * fs).toInt(), n - 1)
                val meanSeg = thighAngle.slice(i..endIdx).average()
                if (meanSeg <= sitThresholdDeg) {
                    sitTimesRaw.add(i); state = "sitting"
                }
            }
            stateSeries[i] = state
        }

        // 2秒以内の近接イベント統合（MATLAB: mergeCloseEvents）
        val mergeWin = 2 * fs
        val stdM = mergeClose(stdTimesRaw, mergeWin)
        val sitM = mergeClose(sitTimesRaw, mergeWin)

        // 着座→起立区間の整合（any(thigh_angle < sit_threshold) を満たすペアのみ）
        val validSit = ArrayList<Int>()
        val validStd = ArrayList<Int>()
        val m = min(sitM.size - 1, stdM.size - 1)
        for (i in 0..m) {
            val s = sitM[i]
            val e = stdM[min(i + 1, stdM.size - 1)]
            if (e > s && anyBelow(thighAngle, s, e, sitThresholdDeg)) {
                validSit.add(s); validStd.add(e)
            }
        }
        val sitToStandCount = validStd.size

        // 立位区間（state=="standing"）→ allowed_intervals
        val standingMask = BooleanArray(n) { stateSeries[it] == "standing" }
        val allowedIntervals = maskToIntervals(standingMask)

        /// ===== 歩行検出（MATLAB完全再現版 detectPeriodicSteps）=====
        val fsd = fs.toDouble()
        val y_raw = DoubleArray(n) { ay[it].toDouble() / 9.80665 } // [m/s²→G]

        // 欠損補間
        val yInterp = interpolateLinearNaN(y_raw)

        // --- MATLAB butter(4,[0.5,6]/(121/2),'bandpass') と同一係数 ---
        val b = doubleArrayOf(
            0.00029381, 0.0, -0.00117523, 0.0, 0.00176285, 0.0, -0.00117523, 0.0, 0.00029381
        )
        val a = doubleArrayOf(
            1.0, -7.22536035, 22.88864124, -41.52666998, 47.20065027,
            -34.42061919, 15.72761984, -4.11695797, 0.47269615
        )

        // --- ゼロ位相フィルタ ---
        val yBP = filtfilt(b, a, yInterp)
        val yAbs = DoubleArray(n) { abs(yBP[it]) }

        // --- findpeaks相当 (MinPeakHeight=1.2G, MinPeakDistance=fs/2.5) ---
        val minHeight = 1.2
        val minDist = (fsd / 2.5).toInt() // ≈0.4秒
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

        // allowed_intervals内に制限
        val peaks = ArrayList<Int>()
        for ((s, e) in allowedIntervals) {
            for (p in peakIdx) {
                if (p in s..e) peaks.add(p)
            }
        }




        // 除外1：起立/着座 前後±2秒
        val excl1 = BooleanArray(n)
        val exclWin = (2.0 * fs).toInt()
        for (t in validStd) {
            val s = (t - exclWin).coerceAtLeast(0)
            val e = (t + exclWin).coerceAtMost(n - 1)
            for (i in s..e) excl1[i] = true
        }
        for (t in validSit) {
            val s = (t - exclWin).coerceAtLeast(0)
            val e = (t + exclWin).coerceAtMost(n - 1)
            for (i in s..e) excl1[i] = true
        }

        val kept1 = ArrayList<Int>()
        for (p in peaks) if (!excl1[p]) kept1.add(p)

        // 除外2：ステップ±0.1秒区間の角度が「全て」40°未満なら除外
        val kept2 = ArrayList<Int>()
        val stepAngleWin = (0.1 * fs).toInt()
        for (p in kept1) {
            val s = (p - stepAngleWin).coerceAtLeast(0)
            val e = (p + stepAngleWin).coerceAtMost(n - 1)
            if (!allBelow(thighAngle, s, e, 40.0)) kept2.add(p)
        }

        // ★ GPS/ペダリング除外は無効（MATLAB準拠）
        val newStepCount = kept2.size

        // ===== 累積更新（現行UI仕様維持のため）=====
        totalSitToStandCountInternal += sitToStandCount
        totalStepCountInternal += newStepCount

        return Result(sitToStandCount = sitToStandCount, stepCount = newStepCount)
    }
}

private fun List<Double>.standardDeviation(): Double {
    val mean = this.average()
    val sumSq = this.fold(0.0) { acc, v -> acc + (v - mean).pow(2.0) }
    return sqrt(sumSq / this.size)
}


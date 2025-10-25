// GaitAnalyzer.kt（GPS距離判定＋累積歩数対応版）
// 元の仕様（起立回数検出＋歩数検出）に加え、
// ・GPS移動距離チェック（minMoveDistance＝3.0m/10秒）
// ・歩数を累積保持（0に戻らない）
// を追加しました。

package com.example.judgeSTS_ver2

import kotlin.math.*

class GaitAnalyzer(
    private val fs: Int = 120,
    private val standThresholdDeg: Double = 65.0,
    private val sitThresholdDeg: Double = 30.0
) {
    // ---- セッション全体のバッファ（累積算出のために全履歴保持） ----
    private val ts = ArrayList<Long>()
    private val ax = ArrayList<Float>()
    private val ay = ArrayList<Float>()
    private val az = ArrayList<Float>()

    // ---- GPS位置バッファ（IMUServiceからセット） ----
    private var gpsLat: List<Double> = listOf()
    private var gpsLon: List<Double> = listOf()

    fun setGpsLocations(lat: List<Double>, lon: List<Double>) {
        gpsLat = lat
        gpsLon = lon
    }

    fun append(tMillis: Long, ax_: Float, ay_: Float, az_: Float) {
        ts.add(tMillis)
        ax.add(ax_)
        ay.add(ay_)
        az.add(az_)
    }

    data class Result(
        val sitToStandCount: Int,
        val stepCount: Int
    )

    // ---- 累積カウンタ ----
    private var totalStepCount: Int = 0

    // ---- バターワース係数（MATLAB 相当） ----
    private val bLP = doubleArrayOf(
        6.57854320e-06, 2.63141728e-05, 3.94712592e-05, 2.63141728e-05, 6.57854320e-06
    )
    private val aLP = doubleArrayOf(
        1.0, -3.72641450, 5.21604820, -3.25001826, 0.76048982
    )
    private val bBP = doubleArrayOf(
        0.00030291, 0.0, -0.00121164, 0.0, 0.00181746, 0.0, -0.00121164, 0.0, 0.00030291
    )
    private val aBP = doubleArrayOf(
        1.0, -7.21869892, 22.84720478, -41.41616376, 47.03681425,
        -34.27473824, 15.64958851, -4.09373642, 0.46972981
    )

    fun compute(): Result {
        val n = ts.size
        if (n < fs * 3) return Result(0, totalStepCount)

        // ---------- 角度計算（LPF 2 Hz） ----------
        val absAx = DoubleArray(n) { abs(ax[it].toDouble()) }
        val absAy = DoubleArray(n) { abs(ay[it].toDouble()) }
        val absAz = DoubleArray(n) { abs(az[it].toDouble()) }

        val fltAx = filtfilt(bLP, aLP, absAx)
        val fltAy = filtfilt(bLP, aLP, absAy)
        val fltAz = filtfilt(bLP, aLP, absAz)

        val thighAngle = DoubleArray(n) {
            val denom = sqrt(fltAx[it]*fltAx[it] + fltAz[it]*fltAz[it]).coerceAtLeast(1e-9)
            abs(Math.toDegrees(atan(fltAy[it]/denom)))
        }

        // ---------- 起立／着座判定 ----------
        var state = if (thighAngle.take(min(n, fs)).average() < 40.0) "sitting" else "standing"
        val stdTimes = ArrayList<Int>()
        val sitTimes = ArrayList<Int>()
        val halfSec = (0.5 * fs).toInt()

        for (i in 1 until n) {
            // 座位 → 立位
            if (state=="sitting" && thighAngle[i-1]<standThresholdDeg && thighAngle[i]>=standThresholdDeg) {
                val endIdx = min(n-1, i+halfSec)
                if (avg(thighAngle, i, endIdx)>=standThresholdDeg) {
                    stdTimes.add(i); state="standing"
                }
            }
            // 立位 → 座位
            if (state=="standing" && thighAngle[i-1]>sitThresholdDeg && thighAngle[i]<=sitThresholdDeg) {
                val endIdx = min(n-1, i+halfSec)
                if (avg(thighAngle, i, endIdx)<=sitThresholdDeg) {
                    sitTimes.add(i); state="sitting"
                }
            }
        }

        fun mergeClose(ev: List<Int>, w: Int): List<Int> {
            if (ev.isEmpty()) return ev
            val out = ArrayList<Int>(); var last = ev[0]; out.add(last)
            for (k in 1 until ev.size) {
                if (ev[k]-last > w) {
                    last = ev[k]; out.add(last)
                }
            }
            return out
        }
        val mergeWin = 2*fs
        val stdM = mergeClose(stdTimes, mergeWin)
        val sitM = mergeClose(sitTimes, mergeWin)

        val validSit = ArrayList<Int>(); val validStd = ArrayList<Int>()
        val m = min(sitM.size-1, stdM.size-1)
        for (i in 0..m) {
            val s = sitM[i]; val e = stdM[min(i+1, stdM.size-1)]
            if (e>s && anyBelow(thighAngle, s, e, sitThresholdDeg)) {
                validSit.add(s); validStd.add(e)
            }
        }
        val sitToStandCount = validStd.size

        // ---------- 立位マスク ----------
        val standingMask = BooleanArray(n) { thighAngle[it] > 40.0 }
        val allowedIntervals = maskToIntervals(standingMask)

        // ---------- 歩行検出（加速度 Y軸、BPF→abs→ピーク） ----------
        val y = DoubleArray(n) { ay[it].toDouble() }
        val yInterp = interpolateLinearNaN(y)
        val yBP = filtfilt(bBP, aBP, yInterp)
        val yAbs = DoubleArray(n) { abs(yBP[it]) }

        val minHeight = 11.8
        val minProm = 6.0
        val minW = (0.30 * fs).toInt()
        val minDist = (fs/2).toInt().coerceAtLeast(1)

        val peaks = ArrayList<Int>()
        for ((s,e) in allowedIntervals) {
            if (e-s+1 < fs/2) continue
            var last = -1_000_000
            var k = s+1
            while (k <= e-1) {
                if (k-last >= minDist && isPeakWithProminenceAndWidth(
                        idx = k, arr = yAbs, start = s, end = e,
                        minHeight = minHeight, minProm = minProm, minWidthSamples = minW
                    )) {
                    peaks.add(k); last = k; k += minDist; continue
                }
                k++
            }
        }

        // ---------- 除外1：起立／着座 ±2秒 ----------
        val excl1 = BooleanArray(n)
        val w2 = (2.0 * fs).toInt()
        for (idx in validStd) {
            val ss = (idx-w2).coerceAtLeast(0); val ee = (idx+w2).coerceAtMost(n-1)
            for (i in ss..ee) excl1[i] = true
        }
        for (idx in validSit) {
            val ss = (idx-w2).coerceAtLeast(0); val ee = (idx+w2).coerceAtMost(n-1)
            for (i in ss..ee) excl1[i] = true
        }

        // ---------- 除外2：ステップ±0.1秒角度<40° ----------
        val stepWin = (0.1 * fs).toInt().coerceAtLeast(1)
        val kept = ArrayList<Int>()
        for (p in peaks) {
            if (excl1[p]) continue
            val ss = (p-stepWin).coerceAtLeast(0); val ee = (p+stepWin).coerceAtMost(n-1)
            if (anyGE(thighAngle, ss, ee, 40.0)) kept.add(p)
        }

        // ---------- 除外3：GPS移動量チェック ----------
        val keptGps = ArrayList<Int>()
        val timeWindow = 10.0
        val minMoveDist = 3.0  // あなた指定値：3m／10秒
        if (gpsLat.size == n && gpsLon.size == n) {
            for (p in kept) {
                val tCur = ts[p]
                val tStart = (tCur - (timeWindow * 1000)).toLong()
                var dist = 0.0
                var prevLat = gpsLat[p]; var prevLon = gpsLon[p]
                var idx = p-1
                while (idx >= 0 && ts[idx] >= tStart) {
                    dist += haversine(prevLat, prevLon, gpsLat[idx], gpsLon[idx])
                    prevLat = gpsLat[idx]; prevLon = gpsLon[idx]
                    idx--
                }
                if (dist >= minMoveDist) {
                    keptGps.add(p)
                }
            }
        }
        // 累積歩数更新
        totalStepCount += keptGps.size
        val stepCount = totalStepCount

        return Result(sitToStandCount = sitToStandCount, stepCount = stepCount)
    }

    // ===== utilities =====

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
        val y = x.copyOf()
        var i = 0
        while (i < y.size) {
            if (y[i].isNaN()) {
                var j = i + 1
                while (j < y.size && y[j].isNaN()) j++
                val v0 = if (i == 0) 0.0 else y[i-1]
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
                acc += b[k] * if (i-k >= 0) x[i-k] else 0.0
            }
            for (k in 1 until na) {
                acc -= a[k] * if (i-k >= 0) y[i-k] else 0.0
            }
            y[i] = acc / a[0]
        }
        return y
    }

    private fun avg(arr: DoubleArray, s: Int, e: Int): Double {
        var sum = 0.0; var cnt = 0
        val ss = s.coerceAtLeast(0); val ee = e.coerceAtMost(arr.size-1)
        for (i in ss..ee) { sum += arr[i]; cnt++ }
        return if (cnt>0) sum/cnt else 0.0
    }

    private fun anyBelow(arr: DoubleArray, s: Int, e: Int, thr: Double): Boolean {
        val ss = s.coerceAtLeast(0); val ee = e.coerceAtMost(arr.size-1)
        for (i in ss..ee) if (arr[i] < thr) return true
        return false
    }

    private fun anyGE(arr: DoubleArray, s: Int, e: Int, thr: Double): Boolean {
        val ss = s.coerceAtLeast(0); val ee = e.coerceAtMost(arr.size-1)
        for (i in ss..ee) if (arr[i] >= thr) return true
        return false
    }

    private fun isPeakWithProminenceAndWidth(
        idx: Int, arr: DoubleArray, start: Int, end: Int,
        minHeight: Double, minProm: Double, minWidthSamples: Int
    ): Boolean {
        if (idx <= start || idx >= end) return false
        val v = arr[idx]
        if (v < minHeight) return false
        if (!(v > arr[idx-1] && v >= arr[idx+1])) return false

        val leftMin = localMin(arr, max(start, idx-minWidthSamples), idx)
        val rightMin = localMin(arr, idx, min(end, idx+minWidthSamples))
        val prom = v - max(leftMin, rightMin)
        if (prom < minProm) return false

        val half = v - (minProm / 2.0)
        val lc = crossLeft(arr, idx, half, start)
        val rc = crossRight(arr, idx, half, end)
        if ((rc-lc) < minWidthSamples) return false

        return true
    }

    private fun localMin(arr: DoubleArray, sIn: Int, eIn: Int): Double {
        val s = sIn.coerceAtLeast(0); val e = eIn.coerceAtMost(arr.size-1)
        var m = Double.POSITIVE_INFINITY
        for (i in s..e) if (arr[i] < m) m = arr[i]
        return if (m.isFinite()) m else arr[s]
    }

    private fun crossLeft(arr: DoubleArray, idx: Int, level: Double, start: Int): Int {
        var j = idx
        while (j > start && arr[j] > level) j--
        return j
    }

    private fun crossRight(arr: DoubleArray, idx: Int, level: Double, end: Int): Int {
        var j = idx
        while (j < end && arr[j] > level) j++
        return j
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) *
                cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

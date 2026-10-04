package io.github.ohsync.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hook -> 主进程的最小序列化格式（org.json，零依赖）。
 *
 * 用 JSON 而不是 Parcelable/Protobuf：调试时可以直接在 logcat 里读到全貌，
 * 排障成本远低于一个自定义二进制格式。
 */
object PayloadCodec {

    fun encode(records: List<SyncRecord>): String {
        val arr = JSONArray()
        for (r in records) {
            val o = JSONObject()
            o.put("k", r.sourceKey)
            o.put("s", r.startTime)
            o.put("e", r.endTime)
            if (r.zoneOffsetSeconds != null) o.put("z", r.zoneOffsetSeconds)
            if (r.values.isNotEmpty()) {
                val v = JSONObject()
                r.values.forEach { (k, d) -> v.put(k, d) }
                o.put("v", v)
            }
            if (r.metadata.isNotEmpty()) {
                val m = JSONObject()
                r.metadata.forEach { (k, s) -> m.put(k, s) }
                o.put("m", m)
            }
            // 睡眠分段：[startMs, endMs, stage]，用数组省体积
            if (r.stages.isNotEmpty()) {
                val st = JSONArray()
                r.stages.forEach { g -> st.put(JSONArray().put(g.start).put(g.end).put(g.stage)) }
                o.put("st", st)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun decode(type: String, payload: String): List<SyncRecord> {
        val arr = JSONArray(payload)
        val out = ArrayList<SyncRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val values = HashMap<String, Double>()
            o.optJSONObject("v")?.let { v ->
                v.keys().forEach { k -> values[k] = v.getDouble(k) }
            }
            val meta = HashMap<String, String>()
            o.optJSONObject("m")?.let { m ->
                m.keys().forEach { k -> meta[k] = m.getString(k) }
            }
            val stages = ArrayList<SleepStage>()
            o.optJSONArray("st")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val g = arr.getJSONArray(i)
                    stages += SleepStage(g.getLong(0), g.getLong(1), g.getInt(2))
                }
            }
            out.add(
                SyncRecord(
                    type = type,
                    sourceKey = o.getString("k"),
                    startTime = o.getLong("s"),
                    endTime = o.getLong("e"),
                    values = values,
                    zoneOffsetSeconds = if (o.has("z")) o.getInt("z") else null,
                    metadata = meta,
                    stages = stages,
                )
            )
        }
        return out
    }
}

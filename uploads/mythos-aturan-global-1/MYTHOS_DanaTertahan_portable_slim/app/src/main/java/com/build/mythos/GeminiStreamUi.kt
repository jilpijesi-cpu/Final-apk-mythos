package com.build.mythos

import org.json.JSONObject

/** Salurkan kejadian Gemini ke UI yang sama dengan Codex: teks mengalir, alat tampil sebagai baris aktivitas. */
internal fun MainActivity.applyGeminiEvents(events: List<GeminiEvent>) {
    for (e in events) when (e) {
        GeminiEvent.SegmentStart -> runOnUiThread { codexActivityUi.accept(JSONObject().put("type", "message_start")) }
        is GeminiEvent.Text -> codexActivityUi.delta(e.delta)
        is GeminiEvent.ToolStart -> runOnUiThread { codexActivityUi.accept(JSONObject().put("type", "item_started").put("item", e.item)) }
        is GeminiEvent.ToolEnd -> runOnUiThread { codexActivityUi.accept(JSONObject().put("type", "item_completed").put("item", e.item)) }
        is GeminiEvent.Failure -> runOnUiThread { addNote("Gemini: ${e.message}") }
        is GeminiEvent.Done -> runOnUiThread {
            codexActivityUi.accept(JSONObject().put("type", "turn_completed").put("item", JSONObject().put("status", if (e.ok) "completed" else "failed")))
        }
    }
}

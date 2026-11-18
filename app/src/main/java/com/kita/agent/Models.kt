package com.kita.agent

import android.view.ViewGroup
import org.json.JSONObject

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
internal const val USER = 0
internal const val AI = 1
internal const val KIND_HIDE = -3
internal const val KIND_DOTS = -1

internal class MsgMeta(val role: Int, var text: String, var time: String = "")

internal class SetupStep(
    val label: String,
    val ok: Boolean,
    val help: String = "",
    val acts: List<Pair<String, () -> Unit>> = emptyList()
)

internal class Cancelled : RuntimeException("Dihentikan")

class Call(val id: String, val name: String, val args: JSONObject)

class HistImage(val path: String) {
    @Volatile internal var cachedPayload: Any? = null
}

class Hist(
    val role: Int,
    val text: String = "",
    val calls: List<Call> = emptyList(),
    val results: List<Pair<String, String>> = emptyList(),
    val raw: JSONObject? = null,
    val images: List<HistImage> = emptyList()
)

internal class Preset(val name: String, val fmt: String, val base: String, val model: String)

enum class AgentState { Idle, Busy, Error }
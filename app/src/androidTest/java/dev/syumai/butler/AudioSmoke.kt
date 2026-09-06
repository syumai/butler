package dev.syumai.butler

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in API smoke test. Sends fixed text only; microphone stays disabled. */
object AudioSmoke {
    fun run(test: Instrumentation) {
        val finished = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        var client: RealtimeClient? = null
        var requested = false
        var audioResponse = false
        var playbackStarted = false
        var playbackStopped = false
        var responseDone = false
        var resultCode = Activity.RESULT_CANCELED
        val result = Bundle()
        fun maybeFinish() { if (responseDone && playbackStopped) finished.countDown() }
        val main = Handler(Looper.getMainLooper())
        var peak = 0
        var route = "unknown"
        var frames = 0
        val sampleOutput = object : Runnable {
            override fun run() {
                runCatching {
                    val device = RealtimeClient::class.java.getDeclaredField("device").apply { isAccessible = true }.get(client)!!
                    val output = device.javaClass.getField("audioOutput").get(device)!!
                    val buffer = (output.javaClass.getDeclaredField("byteBuffer").apply { isAccessible = true }.get(output) as ByteBuffer)
                        .duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until buffer.capacity() - 1 step 2) peak = maxOf(peak, kotlin.math.abs(buffer.getShort(i).toInt()))
                    val track = output.javaClass.getDeclaredField("audioTrack").apply { isAccessible = true }.get(output) as android.media.AudioTrack
                    route = "deviceType=${track.routedDevice?.type}, usage=${track.audioAttributes.usage}, state=${track.playState}"
                    frames = track.playbackHeadPosition
                }
                main.postDelayed(this, 40)
            }
        }
        var bytes = 0.0
        var energy = 0.0
        try {
            test.runOnMainSync {
                client = RealtimeClient(test.targetContext, Settings(test.targetContext), { event ->
                    when (event.optString("type")) {
                        "session.created" -> {
                            // Also validate the production tool definitions on Android's JSON implementation.
                            val service = AssistantService()
                            AssistantService::class.java.getDeclaredField("settings").apply { isAccessible = true }
                                .set(service, Settings(test.targetContext))
                            val definitions = AssistantService::class.java.getDeclaredMethod("definitions").apply { isAccessible = true }
                                .invoke(service) as JSONArray
                            check(client!!.send(JSONObject().put("type", "session.update").put("session",
                                JSONObject().put("type", "realtime").put("tools", definitions))))
                        }
                        "session.updated" -> if (!requested) {
                            requested = true
                            check(client!!.send(JSONObject().put("type", "conversation.item.create").put("item",
                                JSONObject().put("type", "message").put("role", "user").put("content", JSONArray().put(
                                    JSONObject().put("type", "input_text").put("text", "「音声の再生テストです」とだけ日本語で言ってください。ツールは使わないでください。"))))))
                            check(client!!.send(JSONObject().put("type", "response.create")))
                        }
                        "output_audio_buffer.started" -> playbackStarted = true
                        "response.output_audio.done" -> audioResponse = true
                        "response.done" -> {
                            responseDone = true
                            val response = event.getJSONObject("response")
                            if (response.optString("status") != "completed") {
                                error.set("Response status: ${response.optString("status")}"); finished.countDown()
                            }
                            val output = response.optJSONArray("output") ?: JSONArray()
                            for (i in 0 until output.length()) {
                                val content = output.getJSONObject(i).optJSONArray("content") ?: continue
                                for (j in 0 until content.length()) {
                                    if (content.getJSONObject(j).optString("type") in listOf("audio", "output_audio")) audioResponse = true
                                }
                            }
                            maybeFinish()
                        }
                        "output_audio_buffer.stopped" -> { playbackStopped = true; maybeFinish() }
                        "error" -> { error.set("API error: ${event.optJSONObject("error")?.optString("code")}"); finished.countDown() }
                    }
                }, { error.set(it); finished.countDown() })
                client!!.start()
                main.post(sampleOutput)
            }
            check(finished.await(40, TimeUnit.SECONDS)) { "No completed playback within 40 seconds" }
            check(error.get() == null) { error.get().orEmpty() }
            check(audioResponse && playbackStarted) { "Missing generated audio=$audioResponse or playback=$playbackStarted" }
            val statsReady = CountDownLatch(1)
            test.runOnMainSync {
                val peer = RealtimeClient::class.java.getDeclaredField("peer").apply { isAccessible = true }.get(client) as PeerConnection
                peer.getStats { report ->
                    report.statsMap.values.filter { it.type == "inbound-rtp" }.forEach {
                        bytes += (it.members["bytesReceived"] as? Number)?.toDouble() ?: 0.0
                        energy += (it.members["totalAudioEnergy"] as? Number)?.toDouble() ?: 0.0
                    }
                    statsReady.countDown()
                }
            }
            check(statsReady.await(5, TimeUnit.SECONDS))
            check(bytes > 0 && energy > 0) { "No non-silent received audio: bytes=$bytes energy=$energy" }
            resultCode = Activity.RESULT_OK
            result.putString("stream", "\nPASS: Japanese audio response, playback start/stop, bytes=$bytes energy=$energy peak=$peak frames=$frames $route; microphone disabled.\n")
        } catch (e: Throwable) {
            result.putString("stream", "\nFAIL: ${e.javaClass.simpleName}: ${e.message}\n")
        } finally { test.runOnMainSync { main.removeCallbacks(sampleOutput); client?.close() } }
        test.finish(resultCode, result)
    }
}

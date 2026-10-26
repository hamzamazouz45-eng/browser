package local.qwen.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** عميل بسيط لأي خادم متوافق مع واجهة OpenAI (Ollama, LM Studio, llama.cpp ...). */
object Ai {

    class ChatMessage(val role: String, val content: String)

    /** يحوّل العنوان الذي يكتبه المستخدم إلى عنوان أساسي ينتهي بـ /v1 عند الحاجة. */
    fun normalizeEndpoint(input: String): String {
        var raw = input.trim()
        require(raw.isNotEmpty()) { "عنوان النموذج فارغ" }
        if (!raw.startsWith("http://", true) && !raw.startsWith("https://", true)) raw = "http://$raw"
        val u = URL(raw)
        val path = u.path.trimEnd('/')
        val port = if (u.port != -1) ":${u.port}" else ""
        return "${u.protocol}://${u.host}$port${if (path.isEmpty()) "/v1" else path}"
    }

    private fun open(url: String, method: String, apiKey: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $apiKey")
        return c
    }

    private fun readBody(c: HttpURLConnection, error: Boolean): String {
        val stream = (if (error) c.errorStream else c.inputStream) ?: return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** قائمة أسماء النماذج المتاحة. تُستدعى من خيط خلفي. */
    fun listModels(endpoint: String, apiKey: String): List<String> {
        val c = open(normalizeEndpoint(endpoint) + "/models", "GET", apiKey)
        c.readTimeout = 8000
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val data = JSONObject(readBody(c, false)).optJSONArray("data") ?: JSONArray()
            val out = ArrayList<String>()
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotEmpty()) out.add(id)
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    /** يمنع ظهور كتل <think>…</think> (تفكير Qwen3) حتى لو انقسم الوسم بين دفعتين. */
    class ThinkStripper {
        private var buf = ""
        private var inThink = false

        private fun partial(s: String, tag: String): Int {
            var k = minOf(tag.length - 1, s.length)
            while (k > 0) {
                if (s.endsWith(tag.substring(0, k))) return k
                k--
            }
            return 0
        }

        fun push(chunk: String): String {
            buf += chunk
            val out = StringBuilder()
            while (true) {
                if (inThink) {
                    val i = buf.indexOf("</think>")
                    if (i == -1) {
                        buf = buf.takeLast(7)
                        return out.toString()
                    }
                    buf = buf.substring(i + 8).trimStart()
                    inThink = false
                } else {
                    val i = buf.indexOf("<think>")
                    if (i == -1) {
                        val keep = partial(buf, "<think>")
                        out.append(buf, 0, buf.length - keep)
                        buf = buf.substring(buf.length - keep)
                        return out.toString()
                    }
                    out.append(buf, 0, i)
                    buf = buf.substring(i + 7)
                    inThink = true
                }
            }
        }

        fun flush(): String {
            val rest = if (inThink) "" else buf
            buf = ""
            return rest
        }
    }

    /** يمسك الاتصال الجاري ليمكن إيقافه من خيط آخر. */
    class Handle {
        @Volatile var cancelled = false
        @Volatile var conn: HttpURLConnection? = null
        fun cancel() {
            cancelled = true
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** يرسل المحادثة ويبث الرد عبر [onToken] (من الخيط الحالي). */
    fun streamChat(
        endpoint: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        handle: Handle,
        onToken: (String) -> Unit,
    ) {
        require(model.isNotEmpty()) { "لم يتم اختيار نموذج" }
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("temperature", 0.7)
            .put(
                "messages",
                JSONArray().also { arr ->
                    for (m in messages) arr.put(JSONObject().put("role", m.role).put("content", m.content))
                },
            )
        val c = open(normalizeEndpoint(endpoint) + "/chat/completions", "POST", apiKey)
        handle.conn = c
        c.readTimeout = 0 // النماذج المحلية قد تتأخر قبل أول كلمة
        c.doOutput = true
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) {
                val detail = readBody(c, true).take(300)
                throw IOException("HTTP ${c.responseCode}" + if (detail.isNotEmpty()) " — $detail" else "")
            }
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val data = t.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val json = try { JSONObject(data) } catch (_: Exception) { continue }
                    json.optJSONObject("error")?.let { throw IOException(it.optString("message", "error")) }
                    val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    // على أندرويد، optString لقيمة null تعيد النص "null"، لذلك نفحصها أولاً
                    if (delta == null || delta.isNull("content")) continue
                    val text = delta.optString("content", "")
                    if (text.isNotEmpty()) onToken(text)
                }
            }
        } finally {
            c.disconnect()
        }
    }
}

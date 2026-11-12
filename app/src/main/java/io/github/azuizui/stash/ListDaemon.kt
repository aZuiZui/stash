package io.github.azuizui.stash

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * One long-running `rclone rcd` used for folder listings.
 *
 * Listing used to start a new rclone process per folder, which also rebuilt the remote each
 * time (Google Drive: token + root lookups). The daemon keeps remotes warm between listings.
 * It listens on 127.0.0.1 with a random user/password, is restarted when rclone.conf changes
 * (new remote, new login), and callers fall back to the old per-process listing on any failure.
 */
object ListDaemon {

    /** How to start rclone: the full command line for the given rcd flags, and its environment. */
    interface Launcher {
        fun command(extra: Array<String>): Array<String>
        fun env(): Array<String>
    }

    private var process: Process? = null
    private var port = 0
    private var user = ""
    private var pass = ""
    private var confStamp = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun token(): String {
        val b = ByteArray(18)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun alive() = process?.isAlive == true

    @Synchronized
    private fun ensure(launcher: Launcher, conf: File): Boolean {
        if (alive()) {
            if (conf.lastModified() == confStamp) return true
            // rclone re-reads a changed config by itself (token refreshes rewrite it all the time);
            // only drop cached remotes so an edited or re-added remote is rebuilt. No restart:
            // reconnecting to e.g. Google Drive is the slow part we're avoiding.
            confStamp = conf.lastModified()
            if (call("fscache/clear", JSONObject()) != null) return true
        }
        stop()
        return try {
            port = ServerSocket(0).use { it.localPort }
            user = token(); pass = token()
            confStamp = conf.lastModified()
            val cmd = launcher.command(arrayOf("rcd", "--rc-addr", "127.0.0.1:$port", "--rc-user", user, "--rc-pass", pass))
            process = Runtime.getRuntime().exec(cmd, launcher.env())
            // drain output so the process never blocks on a full pipe
            process?.let { p ->
                Thread { try { p.inputStream.copyTo(java.io.OutputStream.nullOutputStream()) } catch (_: Exception) {} }.start()
                Thread { try { p.errorStream.copyTo(java.io.OutputStream.nullOutputStream()) } catch (_: Exception) {} }.start()
            }
            val deadline = System.currentTimeMillis() + 6000
            while (System.currentTimeMillis() < deadline && alive()) {
                if (call("rc/noopauth", JSONObject()) != null) return true
                Thread.sleep(100)
            }
            stop(); false
        } catch (e: Exception) {
            stop(); false
        }
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }

    private fun call(method: String, body: JSONObject): JSONObject? = try {
        val req = Request.Builder()
            .url("http://127.0.0.1:$port/$method")
            .header("Authorization", Credentials.basic(user, pass))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) JSONObject(r.body!!.string()) else null }
    } catch (e: Exception) {
        null
    }

    /**
     * Lists folder [path] of fs [fs] (e.g. fs "gdrive:", path "Photos/2026"). Returns items in
     * `rclone lsjson` form (Path = name inside the folder), or null to fall back.
     */
    @JvmStatic
    fun list(launcher: Launcher, conf: File, fs: String, path: String): JSONArray? {
        if (!ensure(launcher, conf)) return null
        val res = call("operations/list", JSONObject().put("fs", fs).put("remote", path)) ?: return null
        val list = res.optJSONArray("list") ?: return null
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            o.put("Path", o.optString("Name"))
        }
        return list
    }
}

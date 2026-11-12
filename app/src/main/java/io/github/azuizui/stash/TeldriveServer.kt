package io.github.azuizui.stash

import android.content.Context
import android.content.SharedPreferences
import ca.pkay.rcloneexplorer.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The bundled teldrive server (libteldrive.so, built from teldrive 1.8.3 plus teldrive/patches).
 *
 * It runs on 127.0.0.1:[PORT] against the NAS Postgres DB the user entered. Everything else
 * (Telegram app id/hash, JWT secret, uploads encryption key) is baked in from the NAS config at
 * build time. Differences from the NAS instance, so the two can share one DB safely:
 *  - db.migrate = false: the phone never changes the NAS schema
 *  - cronjobs off: cleanup jobs stay on the NAS
 *  - Telegram bot sessions in a local JSON file, not the shared DB (bot auth keys are stored
 *    per bot id only, so sharing them would put the NAS's keys on the phone too)
 */
object TeldriveServer {
    const val PORT = 18080
    const val BASE_URL = "http://127.0.0.1:$PORT"

    private const val PREFS = "teldrive"
    private const val K_DB_HOST = "db_host"
    private const val K_DB_PORT = "db_port"
    private const val K_DB_NAME = "db_name"
    private const val K_DB_USER = "db_user"
    private const val K_DB_PASS = "db_pass"
    private const val K_TOKEN = "access_token"
    private const val K_DONE = "setup_done"

    @Volatile private var process: Process? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Db(val host: String, val port: String, val name: String, val user: String, val pass: String)

    fun db(ctx: Context): Db {
        val p = prefs(ctx)
        return Db(
            p.getString(K_DB_HOST, BuildConfig.TD_DB_HOST)!!,
            p.getString(K_DB_PORT, BuildConfig.TD_DB_PORT.ifEmpty { "5432" })!!,
            p.getString(K_DB_NAME, BuildConfig.TD_DB_NAME.ifEmpty { "teldrive" })!!,
            p.getString(K_DB_USER, "")!!,
            p.getString(K_DB_PASS, "")!!,
        )
    }

    fun saveDb(ctx: Context, db: Db) {
        prefs(ctx).edit()
            .putString(K_DB_HOST, db.host).putString(K_DB_PORT, db.port).putString(K_DB_NAME, db.name)
            .putString(K_DB_USER, db.user).putString(K_DB_PASS, db.pass)
            .apply()
    }

    fun hasDb(ctx: Context) = db(ctx).let { it.host.isNotEmpty() && it.user.isNotEmpty() }
    fun isSetUp(ctx: Context) = prefs(ctx).getBoolean(K_DONE, false) && hasDb(ctx)
    fun accessToken(ctx: Context): String? = prefs(ctx).getString(K_TOKEN, null)

    fun markSetUp(ctx: Context, token: String) {
        prefs(ctx).edit().putString(K_TOKEN, token).putBoolean(K_DONE, true).apply()
    }

    /** One-off: drop the per-channel remotes earlier builds created (only "teldrive" is wanted). */
    fun pruneOldRemotesOnce(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("pruned_channel_remotes", false)) return
        TeldriveRemotes.pruneExtra(ca.pkay.rcloneexplorer.Rclone(ctx))
        p.edit().putBoolean("pruned_channel_remotes", true).apply()
    }

    fun resetSetup(ctx: Context) {
        prefs(ctx).edit().remove(K_TOKEN).putBoolean(K_DONE, false).apply()
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "teldrive").apply { mkdirs() }
    /** Logs go to the app's external files dir so they can be read over adb (release builds block run-as):
     *  /sdcard/Android/data/<package>/files/logs/. Config and sessions stay in private storage. */
    private fun logDir(ctx: Context) = (ctx.getExternalFilesDir("logs") ?: dir(ctx)).apply { mkdirs() }
    fun logFile(ctx: Context) = File(logDir(ctx), "teldrive.log")
    private fun stdoutFile(ctx: Context) = File(logDir(ctx), "stdout.log")

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun writeConfig(ctx: Context): File {
        val db = db(ctx)
        val params = BuildConfig.TD_DB_PARAMS.ifEmpty { "sslmode=disable" }
        val dsn = "postgres://${enc(db.user)}:${enc(db.pass)}@${db.host}:${db.port}/${enc(db.name)}?$params"
        val allowed = BuildConfig.TD_ALLOWED_USERS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val opt = { key: String, value: String -> if (value.isEmpty()) "" else "$key = ${q(value)}\n" }
        val toml = buildString {
            append("[db]\n")
            append("data-source = ${q(dsn)}\n")
            append("prepare-stmt = true\n")
            append("migrate = false\n\n")
            append("[db.pool]\nenable = true\nmax-open-connections = 6\nmax-idle-connections = 2\nmax-lifetime = \"10m\"\n\n")
            append("[cronjobs]\nenable = false\n\n")
            append("[jwt]\n")
            append("secret = ${q(BuildConfig.TD_JWT_SECRET)}\n")
            append("session-time = \"3650d\"\n")
            append("allowed-users = [${allowed.joinToString(",") { q(it) }}]\n\n")
            append("[log]\nlevel = \"debug\"\nfile = ${q(logFile(ctx).absolutePath)}\n\n")
            append("[server]\nhost = \"127.0.0.1\"\nport = $PORT\nread-timeout = \"2h\"\nwrite-timeout = \"2h\"\n\n")
            append("[tg]\n")
            if (BuildConfig.TD_APP_ID.isNotEmpty()) append("app-id = ${BuildConfig.TD_APP_ID}\n")
            append(opt("app-hash", BuildConfig.TD_APP_HASH))
            append(opt("app-version", BuildConfig.TD_APP_VERSION))
            append(opt("device-model", BuildConfig.TD_DEVICE_MODEL))
            append(opt("system-version", BuildConfig.TD_SYSTEM_VERSION))
            append(opt("lang-pack", BuildConfig.TD_LANG_PACK))
            append("session-instance = \"teldrive-apk\"\n")
            append("pool-size = 4\nrate-limit = true\nrate = 100\nrate-burst = 5\nauto-channel-create = true\n\n")
            append("[tg.session]\ntype = \"file\"\n\n") // patch 0002: JSON file, not bbolt
            append("[tg.session.bolt]\npath = ${q(File(dir(ctx), "sessions.json").absolutePath)}\n\n")
            append("[tg.uploads]\n")
            append(opt("encryption-key", BuildConfig.TD_UPLOADS_ENCRYPTION_KEY))
            append("threads = 4\nmax-retries = 12\n\n")
            // Phone: stream through ONE bot (the NAS server uses all of them), kept logged in for 3h. A warm client
            // logs in to each DC a file lives on once and keeps 2 connections there (one per parallel
            // chunk read). Rotating through dozens of bots made almost every request wait ~10s for a
            // fresh login.
            append("[tg.stream]\nconcurrency = 2\nbuffers = 8\nchunk-timeout = \"45s\"\n")
            append("bots-limit = 1\nwarm-clients = true\nwarm-idle = \"3h\"\nwarm-connections = 2\n")
        }
        val f = File(dir(ctx), "config.toml")
        f.writeText(toml)
        f.setReadable(false, false); f.setReadable(true, true)
        return f
    }

    @Synchronized
    fun start(ctx: Context) {
        if (process?.isAlive == true) return
        val app = ctx.applicationContext
        // Older builds kept bot sessions in a bbolt file (session.db) that got corrupted; it is
        // no longer used. Bot sessions are recreated on demand; the user's login lives in Postgres.
        File(dir(app), "session.db").delete()
        val config = writeConfig(app)
        val bin = File(app.applicationInfo.nativeLibraryDir, "libteldrive.so").absolutePath
        val pb = ProcessBuilder(bin, "run", "--config", config.absolutePath)
            .directory(dir(app))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutFile(app)))
        pb.environment()["HOME"] = dir(app).absolutePath
        pb.environment()["TMPDIR"] = app.cacheDir.absolutePath
        process = pb.start()
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }

    fun isRunning() = process?.isAlive == true

    /** True once the HTTP server answers. */
    fun isUp(): Boolean = try {
        http.newCall(Request.Builder().url("$BASE_URL/").build()).execute().use { it.code in 200..499 }
    } catch (e: Exception) {
        false
    }

    /** Waits for the server; returns null when up, else the tail of its log as the error. */
    fun awaitUp(ctx: Context, timeoutMs: Long = 45_000): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (isUp()) return null
            if (!isRunning()) break
            Thread.sleep(500)
        }
        return lastLog(ctx).ifEmpty { "teldrive did not start" }
    }

    fun lastLog(ctx: Context, lines: Int = 12): String {
        val out = stdoutFile(ctx).takeIf { it.exists() }?.readLines().orEmpty()
        val log = logFile(ctx).takeIf { it.exists() }?.readLines().orEmpty()
        return (out + log).takeLast(lines).joinToString("\n")
    }

    fun get(path: String, token: String): String {
        val req = Request.Builder().url("$BASE_URL$path").header("Cookie", "access_token=$token").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GET $path -> HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }
}

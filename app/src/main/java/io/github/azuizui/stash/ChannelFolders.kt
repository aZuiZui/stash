package io.github.azuizui.stash

import android.content.Context
import ca.pkay.rcloneexplorer.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Folder -> Telegram channel mapping for uploads.
 *
 * There is a single rclone remote "teldrive". When something on the phone is uploaded into a
 * mapped folder (or anything below it), the destination is rewritten to an rclone connection
 * string with an override, e.g. `teldrive:Shows/HK/ep1.mkv` -> `teldrive,channel_id=123:Shows/HK/ep1.mkv`,
 * so the teldrive server stores the parts in that channel. The deepest matching folder wins.
 *
 * Only uploads are rewritten (source on the phone, Google Drive, TeraBox, ...). Teldrive -> teldrive moves/copies are left
 * alone: an override makes rclone treat the two sides as different remotes and it would
 * download and re-upload instead of moving server-side. Downloads don't need it either, as
 * every file records its own channel.
 */
object ChannelFolders {
    private const val KEY = "channel_folders"

    data class Mapping(val folder: String, val channelId: Long, val channelName: String)

    /** "/Shows/HK/" -> "Shows/HK" (rclone path form, relative to the remote root). */
    fun norm(path: String) = path.trim().trim('/')

    fun all(ctx: Context): List<Mapping> {
        val raw = TeldriveServer.prefs(ctx).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Mapping(o.getString("folder"), o.getLong("channelId"), o.optString("channelName"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Mapping>) {
        val arr = JSONArray()
        list.sortedBy { it.folder.lowercase() }.forEach {
            arr.put(JSONObject().put("folder", it.folder).put("channelId", it.channelId).put("channelName", it.channelName))
        }
        TeldriveServer.prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun put(ctx: Context, m: Mapping) {
        val folder = norm(m.folder)
        save(ctx, all(ctx).filter { it.folder != folder } + m.copy(folder = folder))
    }

    fun remove(ctx: Context, folder: String) {
        save(ctx, all(ctx).filter { it.folder != norm(folder) })
    }

    /** The NAS mappings baked in at build time (scripts/gen-teldrive-secrets.py), names unknown. */
    fun nasDefaults(): List<Mapping> = BuildConfig.TD_CHANNEL_FOLDERS.split(';')
        .mapNotNull {
            val folder = it.substringBeforeLast('|', "")
            val id = it.substringAfterLast('|', "").toLongOrNull()
            if (folder.isBlank() || id == null) null else Mapping(norm(folder), id, "")
        }

    /** Adds the NAS mappings (keeps a folder's existing mapping). Returns how many were added. */
    fun addNasDefaults(ctx: Context): Int {
        val have = all(ctx).map { it.folder }.toSet()
        val add = nasDefaults().filter { it.folder !in have }
        if (add.isNotEmpty()) save(ctx, all(ctx) + add)
        return add.size
    }

    /** First run: start with the NAS mappings. */
    fun seedOnce(ctx: Context) {
        val prefs = TeldriveServer.prefs(ctx)
        if (prefs.getBoolean("channel_folders_seeded", false)) return
        addNasDefaults(ctx)
        prefs.edit().putBoolean("channel_folders_seeded", true).apply()
    }

    /** Fills in channel names (from the server's channel list) for mappings saved without one. */
    fun fillNames(ctx: Context, names: Map<Long, String>) {
        val list = all(ctx)
        if (list.none { it.channelName.isEmpty() && names[it.channelId] != null }) return
        save(ctx, list.map { if (it.channelName.isEmpty()) it.copy(channelName = names[it.channelId] ?: "") else it })
    }

    /** Channel for an upload to [path] (rclone path within the teldrive remote), or null. */
    fun channelFor(ctx: Context, path: String): Long? {
        val p = norm(path)
        return all(ctx)
            .filter { it.folder.isEmpty() || p == it.folder || p.startsWith(it.folder + "/") }
            .maxByOrNull { it.folder.length }
            ?.channelId
    }

    private val PREFIX = TeldriveRemotes.ROOT + ":"

    /** `teldrive:<path>` -> `teldrive,channel_id=N:<path>` when <path> is under a mapped folder. */
    fun rewriteFs(ctx: Context, fs: String): String {
        if (!fs.startsWith(PREFIX)) return fs
        val path = fs.substring(PREFIX.length)
        val ch = channelFor(ctx, path) ?: return fs
        return "${TeldriveRemotes.ROOT},channel_id=$ch:$path"
    }

    /** rc API form: fs "teldrive:" + separate path. Returns the fs to use for an upload. */
    fun fsForUpload(ctx: Context, fs: String, path: String): String {
        if (fs != PREFIX) return fs
        val ch = channelFor(ctx, path) ?: return fs
        return "${TeldriveRemotes.ROOT},channel_id=$ch:"
    }

    private val UPLOAD_COMMANDS = setOf("copy", "copyto", "sync", "move", "moveto", "bisync")

    /** `teldrive:...` or `teldrive,opt=...:...` - the source side is teldrive itself. */
    private fun isTeldrive(arg: String) = arg.startsWith(PREFIX) || arg.startsWith(TeldriveRemotes.ROOT + ",")

    /**
     * Rewrites an rclone command line in place: for `copy/sync/move... <src> <dst>` where <src>
     * is not teldrive (the phone, Google Drive, ...) and <dst> is in teldrive, adds the channel
     * override to <dst>.
     */
    fun rewriteCommand(ctx: Context, args: MutableList<String>) {
        if (all(ctx).isEmpty()) return
        val i = args.indexOfFirst { it in UPLOAD_COMMANDS }
        if (i < 0 || i + 2 >= args.size) return
        val src = args[i + 1]
        val dst = args[i + 2]
        if (src.startsWith("-") || dst.startsWith("-")) return
        if (isTeldrive(src) || !dst.startsWith(PREFIX)) return
        args[i + 2] = rewriteFs(ctx, dst)
    }
}

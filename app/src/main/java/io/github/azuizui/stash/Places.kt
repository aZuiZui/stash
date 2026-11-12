package io.github.azuizui.stash

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Places in any remote: folders/files pinned to the side menu, and open tabs.
 *
 * [path] is the file browser's path form: "//<remote>" for the remote's root, otherwise the
 * item's path inside the remote (e.g. "Shows/Some Show").
 */
object Places {

    data class Place(val remote: String, val path: String, val name: String, val isDir: Boolean = true) {
        /** The folder to open: the place itself, or a file's folder. */
        fun folder(): String {
            if (isDir) return path
            val p = path.trimEnd('/')
            val i = p.lastIndexOf('/')
            return if (i > 0) p.substring(0, i) else "//$remote"
        }

        fun same(o: Place) = remote == o.remote && path == o.path

        fun toJson(): JSONObject = JSONObject().put("remote", remote).put("path", path)
            .put("name", name).put("isDir", isDir)

        companion object {
            fun fromJson(o: JSONObject) = Place(o.getString("remote"), o.getString("path"),
                o.optString("name"), o.optBoolean("isDir", true))

            fun root(remote: String, displayName: String = remote) = Place(remote, "//$remote", displayName, true)

            /** The place for a browser path, named after its last folder. */
            fun of(remote: String, path: String, displayName: String = remote): Place {
                if (path.isEmpty() || path == "//$remote") return root(remote, displayName)
                val name = path.trimEnd('/').substringAfterLast('/')
                return Place(remote, path, name.ifEmpty { displayName }, true)
            }
        }
    }

    /** Implemented by the activity hosting the file browser. */
    interface Host {
        fun refreshPinnedPlaces()
        fun openInNewTab(place: Place)
        fun renderTabs()
    }

    private const val PINS = "pinned_places"
    private const val TABS = "tabs"
    private const val ACTIVE = "tabs_active"

    private fun load(ctx: Context, key: String): MutableList<Place> {
        val raw = TeldriveServer.prefs(ctx).getString(key, null) ?: return mutableListOf()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { Place.fromJson(a.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun store(ctx: Context, key: String, list: List<Place>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        TeldriveServer.prefs(ctx).edit().putString(key, a.toString()).apply()
    }

    // --- pins ---------------------------------------------------------------------------------

    @JvmStatic fun pins(ctx: Context): List<Place> = load(ctx, PINS)

    @JvmStatic fun isPinned(ctx: Context, p: Place) = pins(ctx).any { it.same(p) }

    /** Pins or unpins [p]; returns true when it is now pinned. */
    @JvmStatic fun togglePin(ctx: Context, p: Place): Boolean {
        val list = load(ctx, PINS)
        val had = list.removeAll { it.same(p) }
        if (!had) list.add(p)
        store(ctx, PINS, list)
        return !had
    }

    /** Drops pins and tabs of a remote that no longer exists. */
    @JvmStatic fun forgetRemote(ctx: Context, remote: String) {
        store(ctx, PINS, load(ctx, PINS).filter { it.remote != remote })
        val tabs = load(ctx, TABS).filter { it.remote != remote }
        saveTabs(ctx, tabs, activeTab(ctx).coerceAtMost(tabs.size - 1).coerceAtLeast(0))
    }

    // --- tabs ---------------------------------------------------------------------------------

    @JvmStatic fun tabs(ctx: Context): List<Place> = load(ctx, TABS)

    @JvmStatic fun activeTab(ctx: Context): Int = TeldriveServer.prefs(ctx).getInt(ACTIVE, 0)

    @JvmStatic fun saveTabs(ctx: Context, tabs: List<Place>, active: Int) {
        store(ctx, TABS, tabs)
        TeldriveServer.prefs(ctx).edit().putInt(ACTIVE, active).apply()
    }
}

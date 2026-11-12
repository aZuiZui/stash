package io.github.azuizui.stash

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Settings screen: which teldrive folder uploads to which Telegram channel. */
class ChannelFoldersActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }
    private lateinit var list: LinearLayout

    data class Channel(val id: Long, val name: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        col.addView(TextView(this).apply {
            text = "Files you upload into a mapped folder (or any folder below it) are stored in that " +
                "channel. Other uploads use your default channel. Moving files inside teldrive keeps " +
                "their channel."
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, pad, 0, pad) }
        col.addView(list)
        col.addView(Button(this).apply {
            text = "Add folder"
            setOnClickListener { browse("") }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val nas = ChannelFolders.nasDefaults().size
        if (nas > 0) col.addView(Button(this).apply {
            text = "Use NAS mappings ($nas)"
            setOnClickListener {
                val added = ChannelFolders.addNasDefaults(this@ChannelFoldersActivity)
                Toast.makeText(this@ChannelFoldersActivity,
                    if (added == 0) "All NAS mappings are already here" else "Added $added mapping(s)",
                    Toast.LENGTH_SHORT).show()
                refresh(); fillNames()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // Edge-to-edge (Android 15+): the theme has no action bar, so bring our own toolbar and pad
        // the whole screen by the status/navigation bars and display cutout.
        val toolbar = Toolbar(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(toolbar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "Channel folders"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ChannelFolders.seedOnce(this)
        refresh()
        fillNames()
    }

    /** Mappings from the NAS are saved without channel names; look them up on the server. */
    private fun fillNames() {
        if (ChannelFolders.all(this).none { it.channelName.isEmpty() } || token() == null) return
        Thread {
            try {
                ChannelFolders.fillNames(this, channels().associate { it.id to it.name })
                ui.post { if (!isFinishing) refresh() }
            } catch (_: Exception) {
                // keep showing channel ids
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    private fun refresh() {
        list.removeAllViews()
        val maps = ChannelFolders.all(this)
        if (maps.isEmpty()) {
            list.addView(TextView(this).apply { text = "No folders mapped yet."; alpha = 0.7f })
        }
        for (m in maps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, pad / 2, 0, pad / 2)
            }
            row.addView(TextView(this).apply {
                text = "/${m.folder}\n→ ${m.channelName.ifEmpty { m.channelId.toString() }}"
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Remove"
                setOnClickListener {
                    ChannelFolders.remove(this@ChannelFoldersActivity, m.folder); refresh()
                }
            })
            list.addView(row)
        }
    }

    // --- teldrive API (local server) ---------------------------------------------------------

    private fun token(): String? = TeldriveServer.accessToken(this)

    private fun background(work: () -> Unit) = Thread {
        try {
            work()
        } catch (e: Exception) {
            ui.post { Toast.makeText(this, "Teldrive: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }.start()

    private fun subfolders(path: String): List<String> {
        val q = URLEncoder.encode("/$path", "UTF-8")
        val json = JSONObject(TeldriveServer.get("/api/files?path=$q&operation=list&type=folder&limit=1000", token()!!))
        val items = json.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).map { items.getJSONObject(it) }
            .filter { it.optString("type") == "folder" }
            .map { it.getString("name") }
            .sortedBy { it.lowercase() }
    }

    private fun channels(): List<Channel> {
        val arr = JSONArray(TeldriveServer.get("/api/users/channels", token()!!))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { Channel(it.getLong("channelId"), it.optString("channelName")) }
    }

    // --- add flow: pick folder, then channel -------------------------------------------------

    private fun browse(path: String) {
        if (token() == null) {
            Toast.makeText(this, "Log in first", Toast.LENGTH_LONG).show(); return
        }
        background {
            val subs = subfolders(path)
            ui.post {
                val entries = (if (path.isEmpty()) emptyList() else listOf("⬑ ..")) + subs.map { "📁 $it" }
                AlertDialog.Builder(this)
                    .setTitle("/$path")
                    .setItems(entries.toTypedArray()) { _, which ->
                        val up = path.isNotEmpty() && which == 0
                        if (up) browse(path.substringBeforeLast('/', ""))
                        else {
                            val name = subs[which - if (path.isEmpty()) 0 else 1]
                            browse(if (path.isEmpty()) name else "$path/$name")
                        }
                    }
                    .setPositiveButton("Use this folder") { _, _ -> pickChannel(path) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun pickChannel(folder: String) = mapFolder(this, folder) { refresh() }

    companion object {
        /**
         * Asks which channel uploads into teldrive [folder] go to (also offers to remove an
         * existing mapping). Used by this screen and by a teldrive folder's menu in the browser.
         */
        @JvmStatic
        @JvmOverloads
        fun mapFolder(activity: android.app.Activity, folder: String, onChanged: () -> Unit = {}) {
            val ui = Handler(Looper.getMainLooper())
            val path = ChannelFolders.norm(folder)
            val token = TeldriveServer.accessToken(activity)
            if (token == null) {
                Toast.makeText(activity, "Log in first", Toast.LENGTH_LONG).show(); return
            }
            Thread {
                val chans = try {
                    val arr = JSONArray(TeldriveServer.get("/api/users/channels", token))
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .map { Channel(it.getLong("channelId"), it.optString("channelName")) }
                } catch (e: Exception) {
                    ui.post { Toast.makeText(activity, "Teldrive: ${e.message}", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                ui.post {
                    if (activity.isFinishing) return@post
                    if (chans.isEmpty()) {
                        Toast.makeText(activity, "No channels found for your account", Toast.LENGTH_LONG).show()
                        return@post
                    }
                    val current = ChannelFolders.all(activity).firstOrNull { it.folder == path }
                    val names = chans.map { c ->
                        val n = c.name.ifEmpty { c.id.toString() }
                        if (current?.channelId == c.id) "✓ $n" else n
                    }
                    val b = AlertDialog.Builder(activity)
                        .setTitle("Uploads to /$path go to")
                        .setItems(names.toTypedArray()) { _, which ->
                            val c = chans[which]
                            ChannelFolders.put(activity, ChannelFolders.Mapping(path, c.id, c.name))
                            Toast.makeText(activity, "/$path → ${c.name.ifEmpty { c.id.toString() }}", Toast.LENGTH_SHORT).show()
                            onChanged()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                    if (current != null) {
                        b.setNeutralButton("Remove mapping") { _, _ ->
                            ChannelFolders.remove(activity, path); onChanged()
                        }
                    }
                    b.show()
                }
            }.start()
        }
    }
}

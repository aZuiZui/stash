package io.github.azuizui.stash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Services.StreamingService
import ca.pkay.rcloneexplorer.workmanager.EphemeralTaskManager
import com.bumptech.glide.Glide
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Google Drive-style viewer: full screen, swipe between the files of a folder, top bar with
 * back / name / "n of m" / Open with / Download. Videos and audio play in Media3 (double-tap
 * left/right to skip 10 s, speed and audio/subtitle tracks in the controls); images zoom
 * (double-tap, pinch) and close with a swipe down; PDFs render page by page; text files can be
 * edited and saved back to the remote; Word and spreadsheet files render read-only in a WebView. Everything is read through one `rclone serve http` of
 * the folder.
 */
@OptIn(UnstableApi::class)
class ViewerActivity : AppCompatActivity() {

    companion object {
        /** Uploaded edits shown instead of the remote copy (see [EditUploads.recent]). */
        private val recentEdits get() = EditUploads.recent
        private const val REQ_OFFICE_EDIT = 4201

        const val EXTRA_REMOTE = "viewer_remote"
        const val EXTRA_FOLDER = "viewer_folder"   // browser path of the folder ("//remote" = root)
        const val EXTRA_ITEMS = "viewer_items"
        const val EXTRA_INDEX = "viewer_index"

        private val TEXT_EXT = setOf("txt", "log", "md", "json", "xml", "csv", "ini", "conf", "cfg", "yml",
            "yaml", "toml", "srt", "ass", "ssa", "vtt", "nfo", "sh", "py", "js", "ts", "kt", "java", "c",
            "h", "cpp", "go", "rs", "html", "css", "sql", "properties", "env", "gitignore", "m3u", "m3u8")
        private const val MAX_TEXT = 1_000_000L

        private val SHEET_EXT = setOf("xlsx", "xlsm", "xls", "ods")

        private val SLIDES_EXT = setOf("pptx", "ppsx", "odp", "ppt")

        enum class Kind { VIDEO, AUDIO, IMAGE, PDF, DOC, SHEET, SLIDES, TEXT, OTHER }

        fun kindOf(item: FileItem): Kind {
            val mime = item.mimeType ?: ""
            val ext = item.name.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("video/") -> Kind.VIDEO
                mime.startsWith("audio/") -> Kind.AUDIO
                mime.startsWith("image/") -> Kind.IMAGE
                mime == "application/pdf" || ext == "pdf" -> Kind.PDF
                ext == "docx" || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> Kind.DOC
                ext in SHEET_EXT || mime == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Kind.SHEET
                ext in SLIDES_EXT || mime == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> Kind.SLIDES
                mime.startsWith("text/") || ext in TEXT_EXT -> Kind.TEXT
                else -> Kind.OTHER
            }
        }

        /** Opens [items] (the folder's files) at [index]. */
        @JvmStatic
        fun open(ctx: Context, remote: RemoteItem, folder: String, items: List<FileItem>, index: Int) {
            // keep the parcel small: at most 300 files around the tapped one
            val from = (index - 150).coerceAtLeast(0)
            val to = (from + 300).coerceAtMost(items.size)
            ctx.startActivity(Intent(ctx, ViewerActivity::class.java)
                .putExtra(EXTRA_REMOTE, remote)
                .putExtra(EXTRA_FOLDER, folder)
                .putParcelableArrayListExtra(EXTRA_ITEMS, ArrayList(items.subList(from, to)))
                .putExtra(EXTRA_INDEX, index - from))
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private lateinit var remote: RemoteItem
    private lateinit var folder: String
    private lateinit var items: List<FileItem>
    private var port = 0
    private var base = ""

    private lateinit var pager: ViewPager2
    private lateinit var topBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var editButton: TextView
    /** Word and Excel previews are white pages: the bar goes solid light with dark text, like Drive. */
    private var lightBar = false
    private val DOC_BG = 0xFFF0F4F9.toInt()     // Drive's grey behind pages
    private val pdfLists = ArrayList<RecyclerView>()
    private lateinit var editFab: ImageView          // Google Docs style pencil: opens OfficeEditorActivity
    private var officeEditPos = -1
    private lateinit var loading: View
    private var barsVisible = true

    private var player: ExoPlayer? = null
    private var playerPosition = -1
    private val playerViews = HashMap<Int, PlayerView>()
    private val textEditors = HashMap<Int, EditText>()
    private val pdfRenderers = HashMap<Int, PdfRenderer>()
    private val officeViews = HashMap<Int, WebView>()
    private var editing = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = intent.getParcelableExtra(EXTRA_REMOTE) ?: return finish()
        folder = intent.getStringExtra(EXTRA_FOLDER) ?: "//${remote.name}"
        items = intent.getParcelableArrayListExtra<FileItem>(EXTRA_ITEMS) ?: return finish()
        val start = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        val day = System.currentTimeMillis() - 24 * 3600_000L
        for (sub in listOf("open", "edit"))
            File(cacheDir, sub).listFiles()?.filter { it.lastModified() < day }?.forEach { it.deleteRecursively() }

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pager = ViewPager2(this).apply { offscreenPageLimit = 1 }
        root.addView(pager, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        loading = ProgressBar(this)
        root.addView(loading, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        topBar = buildTopBar()
        topBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                officeViews.values.forEach { placeBelowBar(it) }
                pdfLists.forEach { it.setPadding(0, topBar.height + dp(4), 0, dp(24)) }
            }
        }
        root.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        editFab = ImageView(this).apply {
            setImageResource(R.drawable.ic_edit_black); setColorFilter(0xFF001D35.toInt())
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFFC2E7FF.toInt()) }
            elevation = dp(6).toFloat()
            contentDescription = "Edit"
            visibility = View.GONE
            setOnClickListener { openOfficeEditor(current()) }
        }
        val fabLp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM or Gravity.END)
        root.addView(editFab, fabLp)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.setPadding(b.left, b.top, b.right, 0)
            pager.setPadding(b.left, 0, b.right, 0)
            fabLp.setMargins(0, 0, b.right + dp(16), b.bottom + dp(24)); editFab.layoutParams = fabLp
            insets
        }
        setContentView(root)
        updateTitle(start)
        startServer(start)
    }

    // --- top bar --------------------------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    private val barIcons = ArrayList<ImageView>()

    /** 48dp round icon button, like the Google apps' top bars. */
    private fun barIcon(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        contentDescription = desc
        tooltipText = desc
        scaleType = ImageView.ScaleType.CENTER
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33888888), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        setOnClickListener { onClick() }
        barIcons.add(this)
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000))
        }
        // one 64dp row; the status bar inset is added above it as padding
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val icon = { LinearLayout.LayoutParams(dp(48), dp(48)) }
        row.addView(barIcon(R.drawable.ic_arrow_back, "Back") { finish() }, icon())
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; setSingleLine(true)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            includeFontPadding = false
        }
        subtitle = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt()); textSize = 12f; setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(3), 0, 0)
        }
        titles.addView(title); titles.addView(subtitle)
        row.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        editButton = barButton("Edit") { toggleEdit() }.apply { visibility = View.GONE }
        row.addView(editButton, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))
        row.addView(barIcon(R.drawable.ic_open_in_new, "Open with") { openWith(current()) }, icon())
        row.addView(barIcon(R.drawable.ic_file_download, "Download") { download(current()) }, icon())
        bar.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return bar
    }

    private fun current() = pager.currentItem.coerceIn(0, items.size - 1)

    // upload state of an edited copy of the current file, shown in the subtitle
    private var syncLive: LiveData<WorkInfo?>? = null
    private val syncObserver = Observer<WorkInfo?> { showSync(current(), it) }

    private fun showSync(pos: Int, info: WorkInfo?) {
        val count = "${pos + 1} of ${items.size}"
        val edit = recentEdits[remote.name + items[pos].path]
        subtitle.setOnClickListener(null); subtitle.isClickable = false
        if (edit == null) {
            subtitle.text = count; subtitle.setTextColor(if (lightBar) 0xFF444746.toInt() else 0xB3FFFFFF.toInt()); return
        }
        when (info?.state) {
            WorkInfo.State.SUCCEEDED -> {
                subtitle.text = "$count · Edited · saved to cloud"; subtitle.setTextColor(if (lightBar) 0xFF146C2E.toInt() else 0xFF81C784.toInt())
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                subtitle.text = "$count · Upload failed, edits only on this phone · tap to retry"
                subtitle.setTextColor(if (lightBar) 0xFFB3261E.toInt() else 0xFFE57373.toInt())
                subtitle.setOnClickListener { uploadBack(items[pos], edit.file) }
            }
            else -> {
                subtitle.text = "$count · Edited · uploading…"; subtitle.setTextColor(if (lightBar) 0xFF8C5000.toInt() else 0xFFFFB74D.toInt())
            }
        }
    }

    private fun updateTitle(pos: Int) {
        title.text = items[pos].name
        syncLive?.removeObserver(syncObserver); syncLive = null
        val edit = recentEdits[remote.name + items[pos].path]
        if (edit != null) {
            syncLive = WorkManager.getInstance(this).getWorkInfoByIdLiveData(edit.upload)
                .also { it.observe(this, syncObserver) }
        }
        showSync(pos, syncLive?.value)
        val textual = kindOf(items[pos]) == Kind.TEXT || pos in sniffedText
        editButton.visibility = if (textual && items[pos].size <= MAX_TEXT) View.VISIBLE else View.GONE
        editButton.text = if (editing) "Save" else "Edit"
        editFab.visibility = if (OfficeEditorActivity.canEdit(items[pos].name) && barsVisible) View.VISIBLE else View.GONE
        styleBar(kindOf(items[pos]).let { it == Kind.DOC || it == Kind.SHEET || it == Kind.PDF || it == Kind.SLIDES })
        if (lightBar && !barsVisible) { barsVisible = true; topBar.alpha = 1f; topBar.visibility = View.VISIBLE }
        showSync(pos, syncLive?.value)
    }

    private fun placeBelowBar(web: View) {
        val lp = web.layoutParams as? FrameLayout.LayoutParams ?: return
        val h = topBar.height.takeIf { it > 0 } ?: dp(100)
        if (lp.topMargin != h) { lp.topMargin = h; web.layoutParams = lp }
    }

    private fun styleBar(light: Boolean) {
        if (light == lightBar && topBar.background != null) return
        lightBar = light
        topBar.background = if (light) android.graphics.drawable.ColorDrawable(DOC_BG)
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
        title.setTextColor(if (light) 0xFF1F1F1F.toInt() else Color.WHITE)
        barIcons.forEach { it.setColorFilter(if (light) 0xFF444746.toInt() else Color.WHITE) }
        editButton.setTextColor(if (light) 0xFF0B57D0.toInt() else Color.WHITE)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = light
    }

    private fun setBars(show: Boolean) {
        // documents keep their bar, like Drive: the page starts below it
        if (!show && lightBar) return
        barsVisible = show
        topBar.animate().alpha(if (show) 1f else 0f).setDuration(150).withEndAction {
            topBar.visibility = if (show) View.VISIBLE else View.INVISIBLE
        }.start()
        if (show) topBar.visibility = View.VISIBLE
        if (OfficeEditorActivity.canEdit(items[current()].name)) {
            editFab.visibility = View.VISIBLE
            editFab.animate().alpha(if (show) 1f else 0f).setDuration(150).withEndAction {
                if (!show) editFab.visibility = View.GONE
            }.start()
        }
    }

    private fun toggleBars() = setBars(!barsVisible)

    // --- server ---------------------------------------------------------------------------------

    private fun startServer(start: Int) {
        port = try { ServerSocket(0).use { it.localPort } } catch (e: Exception) { 18181 }
        base = "http://127.0.0.1:$port/"
        val serve = Intent(this, StreamingService::class.java)
            .putExtra(StreamingService.SERVE_PATH_ARG, folder)
            .putExtra(StreamingService.REMOTE_ARG, remote)
            .putExtra(StreamingService.SERVE_PROTOCOL, StreamingService.SERVE_HTTP)
            .putExtra(StreamingService.SHOW_NOTIFICATION_TEXT, false)
            .putExtra(StreamingService.SERVE_PORT, port)
        stopService(Intent(this, StreamingService::class.java))
        ContextCompat.startForegroundService(this, serve)
        io.execute {
            var ok = false
            val deadline = System.currentTimeMillis() + 45_000
            while (!ok && System.currentTimeMillis() < deadline && !isFinishing) {
                ok = try {
                    http.newCall(Request.Builder().url(base).head().build()).execute().use { it.code == 200 }
                } catch (e: Exception) { false }
                if (!ok) Thread.sleep(300)
            }
            ui.post {
                if (isFinishing) return@post
                loading.visibility = View.GONE
                if (!ok) {
                    Toast.makeText(this, "Couldn't open this folder for viewing", Toast.LENGTH_LONG).show()
                    finish(); return@post
                }
                pager.adapter = PageAdapter()
                pager.setCurrentItem(start, false)
                pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) = onPage(position)
                })
                onPage(start)
            }
        }
    }

    private fun url(item: FileItem) = base + Uri.encode(item.name)

    // --- pages ----------------------------------------------------------------------------------

    private fun onPage(position: Int) {
        if (editing) toggleEdit(saveFirst = false)
        updateTitle(position)
        attachPlayer(position)
    }

    private inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

    private inner class PageAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(FrameLayout(parent.context).apply { layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT) })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.frame.removeAllViews()
            holder.frame.tag = position
            val item = items[position]
            when (kindOf(item)) {
                Kind.VIDEO, Kind.AUDIO -> bindMedia(holder.frame, position)
                Kind.IMAGE -> bindImage(holder.frame, item)
                Kind.PDF -> bindPdf(holder.frame, position, item)
                Kind.DOC -> bindOffice(holder.frame, position, item, "docx.html")
                Kind.SHEET -> bindOffice(holder.frame, position, item, "sheet.html")
                Kind.SLIDES -> bindSlides(holder.frame, position, item)
                Kind.TEXT -> bindText(holder.frame, position, item)
                Kind.OTHER -> if (item.size in 1..MAX_TEXT) bindMaybeText(holder.frame, position, item) else bindOther(holder.frame, item)
            }
        }

        override fun onViewRecycled(holder: Holder) {
            val pos = holder.frame.tag as? Int ?: return
            playerViews.remove(pos)?.player = null
            textEditors.remove(pos)
            officeViews.remove(pos)?.destroy()
            pdfRenderers.remove(pos)?.let { r -> synchronized(r) { try { r.close() } catch (_: Exception) {} } }
        }
    }

    private fun spinner(frame: FrameLayout): View {
        val p = ProgressBar(this)
        frame.addView(p, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        return p
    }

    private fun message(frame: FrameLayout, text: String) {
        frame.addView(TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0)
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))
    }

    // video / audio: one player, attached to the page on screen
    @SuppressLint("ClickableViewAccessibility")
    private fun bindMedia(frame: FrameLayout, position: Int) {
        val pv = PlayerView(this).apply {
            setShowSubtitleButton(true)
            setShowNextButton(false); setShowPreviousButton(false)
            controllerAutoShow = true
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                if (pager.currentItem == position) setBars(v == View.VISIBLE)
            })
        }
        if (kindOf(items[position]) == Kind.AUDIO) pv.controllerShowTimeoutMs = 0
        // double-tap left / right third: skip back / forward 10 s
        val hint = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x88000000.toInt()) }
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val p = player ?: return false
                val w = pv.width.coerceAtLeast(1)
                val delta = when {
                    e.x < w / 3f -> -10_000L
                    e.x > w * 2 / 3f -> 10_000L
                    else -> { if (p.isPlaying) p.pause() else p.play(); return true }
                }
                p.seekTo((p.currentPosition + delta).coerceAtLeast(0))
                hint.text = if (delta < 0) "« 10 s" else "10 s »"
                hint.visibility = View.VISIBLE
                ui.removeCallbacksAndMessages(hint)
                ui.postAtTime({ hint.visibility = View.GONE }, hint, android.os.SystemClock.uptimeMillis() + 700)
                return true
            }
        })
        pv.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); false }
        frame.addView(pv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        frame.addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        playerViews[position] = pv
        if (position == pager.currentItem) attachPlayer(position)
    }

    private fun resumeKey(item: FileItem) = "viewer_pos:${remote.name}:${item.path}"

    private fun saveResume() {
        val p = player ?: return
        if (playerPosition !in items.indices) return
        val d = p.duration
        val pos = p.currentPosition
        val prefs = TeldriveServer.prefs(this).edit()
        val key = resumeKey(items[playerPosition])
        if (d > 120_000 && pos > 10_000 && pos < d - 15_000) prefs.putLong(key, pos) else prefs.remove(key)
        prefs.apply()
    }

    private fun attachPlayer(position: Int) {
        val isMedia = kindOf(items[position]).let { it == Kind.VIDEO || it == Kind.AUDIO }
        if (playerPosition != position) {
            saveResume()
            player?.let { it.stop(); it.clearMediaItems() }
            playerViews.values.forEach { it.player = null }
            playerPosition = -1
        }
        if (!isMedia) return
        val pv = playerViews[position] ?: return // bound later; bindMedia attaches then
        val p = player ?: ExoPlayer.Builder(this).build().also { player = it }
        if (playerPosition != position) {
            p.setMediaItem(MediaItem.fromUri(url(items[position])))
            val resume = TeldriveServer.prefs(this).getLong(resumeKey(items[position]), 0L)
            p.prepare()
            if (resume > 0) p.seekTo(resume)
            p.playWhenReady = true
            playerPosition = position
        }
        pv.player = p
    }

    // images: zoom (pinch / double-tap); swipe down closes when not zoomed
    @SuppressLint("ClickableViewAccessibility")
    private fun bindImage(frame: FrameLayout, item: FileItem) {
        val view = SubsamplingScaleImageView(this).apply {
            setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE)
            orientation = SubsamplingScaleImageView.ORIENTATION_USE_EXIF
            setDoubleTapZoomScale(2.5f)
            setOnClickListener { toggleBars() }
        }
        val closer = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dy = e2.y - start.y
                val notZoomed = !view.isReady || view.scale <= view.minScale * 1.05f
                if (notZoomed && dy > dp(120) && abs(dy) > abs(e2.x - start.x) * 1.5f && vy > 1500) {
                    finish(); return true
                }
                return false
            }
        })
        view.setOnTouchListener { _, ev -> closer.onTouchEvent(ev); false }
        frame.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val spin = spinner(frame)
        io.execute {
            val file = try { Glide.with(this).downloadOnly().load(url(item)).submit().get() } catch (e: Exception) { null }
            ui.post {
                spin.visibility = View.GONE
                if (file == null) message(frame, "Couldn't load ${item.name}")
                else view.setImage(ImageSource.uri(Uri.fromFile(file)))
            }
        }
    }

    // files are fetched to the cache for PDF / text
    private fun fetch(item: FileItem): File? {
        val key = remote.name + item.path
        recentEdits[key]?.let { e ->
            // the edited copy stands in only while its upload is pending or failed; once uploaded the
            // remote copy is current again (and for Google files the only one the viewer can read:
            // the listing never shows their size, and the other app's file may not open here)
            val info = try { WorkManager.getInstance(this).getWorkInfoById(e.upload).get() } catch (x: Exception) { null }
            val uploaded = info == null || info.state == WorkInfo.State.SUCCEEDED
            if (e.file.exists() && !uploaded) return e.file
            recentEdits.remove(key)
        }
        val dir = File(cacheDir, "viewer").apply { mkdirs() }
        val f = File(dir, "${(remote.name + item.path).hashCode()}_${item.name}")
        if (f.exists() && f.length() == item.size) return f
        return try {
            http.newCall(Request.Builder().url(url(item)).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                f.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            f
        } catch (e: Exception) { null }
    }

    private fun bindPdf(frame: FrameLayout, position: Int, item: FileItem) {
        val spin = spinner(frame)
        io.execute {
            val file = fetch(item)
            val renderer = try {
                file?.let { PdfRenderer(ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)) }
            } catch (e: Exception) { null }
            ui.post {
                spin.visibility = View.GONE
                if (renderer == null) { message(frame, "Couldn't open ${item.name}"); return@post }
                if (frame.tag != position) { renderer.close(); return@post }
                pdfRenderers[position] = renderer
                val list = RecyclerView(this).apply {
                    layoutManager = LinearLayoutManager(this@ViewerActivity)
                    setBackgroundColor(DOC_BG)
                    setPadding(0, (topBar.height.takeIf { it > 0 } ?: dp(100)) + dp(4), 0, dp(24)); clipToPadding = false
                    adapter = PdfPages(renderer)
                }
                frame.addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                pdfLists.add(list)
            }
        }
    }

    /**
     * Word (.docx) and spreadsheets (.xlsx/.xls/.ods), read-only: the file is fetched to the cache
     * like a PDF, then [page] from assets/office (docx-preview / SheetJS, bundled, no network)
     * renders it in a WebView. Everything is served from the fake origin https://stash.local/ so
     * the page can fetch() the file; no other requests are allowed.
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun bindOffice(frame: FrameLayout, position: Int, item: FileItem, page: String) {
        val spin = spinner(frame)
        io.execute {
            val file = fetch(item)
            ui.post {
                spin.visibility = View.GONE
                if (file == null) { message(frame, "Couldn't load ${item.name}"); return@post }
                if (frame.tag != position) return@post
                val web = WebView(this)
                web.setBackgroundColor(Color.WHITE)
                web.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url
                        if (u.host != "stash.local") return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, null)
                        val name = u.lastPathSegment ?: return null
                        return try {
                            if (name == "file") {
                                WebResourceResponse("application/octet-stream", null, file.inputStream())
                            } else {
                                val mime = if (name.endsWith(".html")) "text/html" else "application/javascript"
                                WebResourceResponse(mime, "utf-8", assets.open("office/$name"))
                            }
                        } catch (e: Exception) {
                            WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
                        }
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                }
                // let the page scroll sideways (wide sheets, zoomed documents); once it can't scroll any
                // further, a horizontal swipe goes back to the pager to change file
                var startX = 0f
                web.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { startX = ev.x; v.parent.requestDisallowInterceptTouchEvent(true) }
                        MotionEvent.ACTION_MOVE -> if (ev.pointerCount == 1) {
                            val dx = ev.x - startX
                            if (abs(dx) > dp(12) && !v.canScrollHorizontally(if (dx < 0) 1 else -1)) {
                                v.parent.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
                    false
                }
                officeViews[position] = web
                frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                placeBelowBar(web)
                web.loadUrl("https://stash.local/$page")
            }
        }
    }

    private inner class PdfPages(val renderer: PdfRenderer) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = synchronized(renderer) { renderer.pageCount }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
            ImageView(parent.context).apply {
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(10), dp(6), dp(10), dp(6)) }
                elevation = dp(2).toFloat()   // pages sit on the grey like paper, as in Drive
                setOnClickListener { toggleBars() }
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val iv = holder.itemView as ImageView
            iv.setImageBitmap(null)
            iv.minimumHeight = dp(300)
            iv.tag = position
            val width = resources.displayMetrics.widthPixels
            io.execute {
                val bmp = try {
                    synchronized(renderer) {
                        renderer.openPage(position).use { page ->
                            val h = (width.toFloat() * page.height / page.width).toInt()
                            Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(Color.WHITE)
                                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                } catch (e: Exception) { null }
                ui.post { if (iv.tag == position && bmp != null) { iv.minimumHeight = 0; iv.setImageBitmap(bmp) } }
            }
        }
    }

    // text: shown read-only; "Edit" makes it editable, "Save" uploads it back
    private fun bindText(frame: FrameLayout, position: Int, item: FileItem) {
        if (item.size > MAX_TEXT) {
            bindOther(frame, item, "This file is too large to show here.")
            return
        }
        val spin = spinner(frame)
        io.execute {
            val text = fetch(item)?.readText()
            ui.post {
                spin.visibility = View.GONE
                if (text == null) { message(frame, "Couldn't load ${item.name}"); return@post }
                val edit = EditText(this).apply {
                    setText(text)
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                    setTextColor(0xFFE6E6E6.toInt())
                    background = null
                    gravity = Gravity.TOP or Gravity.START
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    isFocusable = false; isFocusableInTouchMode = false
                    setTextIsSelectable(true)
                    setPadding(dp(14), dp(80), dp(14), dp(40))
                }
                textEditors[position] = edit
                frame.addView(ScrollView(this).apply { isFillViewport = true; addView(edit) },
                    FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
    }

    /** Unknown type but small: show it as text if it looks like text (e.g. "rclone.conf.bak-2026…"). */
    private val sniffedText = HashSet<Int>()

    private fun bindMaybeText(frame: FrameLayout, position: Int, item: FileItem) {
        val spin = spinner(frame)
        io.execute {
            val f = fetch(item)
            val isText = f != null && looksLikeText(f)
            ui.post {
                if (frame.tag != position) return@post
                frame.removeView(spin)
                if (isText) {
                    sniffedText.add(position)
                    bindText(frame, position, item)
                    if (position == current()) updateTitle(position)
                } else bindOther(frame, item)
            }
        }
    }

    private fun looksLikeText(f: File): Boolean {
        val head = ByteArray(4096)
        val n = f.inputStream().use { it.read(head) }
        if (n <= 0) return false
        var odd = 0
        for (i in 0 until n) {
            val b = head[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x09 || (b in 0x0E..0x1F)) odd++
        }
        return odd * 100 / n < 2
    }

    private fun toggleEdit(saveFirst: Boolean = true) {
        val pos = current()
        val edit = textEditors[pos]
        if (!editing) {
            if (edit == null) return
            editing = true
            edit.isFocusable = true; edit.isFocusableInTouchMode = true
            edit.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(edit, 0)
            pager.isUserInputEnabled = false
            setBars(true)
        } else {
            editing = false
            pager.isUserInputEnabled = true
            edit?.isFocusable = false; edit?.isFocusableInTouchMode = false
            if (saveFirst && edit != null) save(items[pos], edit.text.toString())
        }
        updateTitle(pos)
    }

    private fun save(item: FileItem, text: String) {
        val dir = File(cacheDir, "edit/${System.currentTimeMillis()}").apply { mkdirs() }
        val f = File(dir, item.name)
        f.writeText(text)
        uploadBack(item, f)
    }

    /** Files opened in another app with write access, uploaded back when they change. */
    private class ExternalEdit(val item: FileItem, val file: File, var stamp: Pair<Long, Long>)
    private val externalEdits = ArrayList<ExternalEdit>()

    private fun stamp(f: File) = f.lastModified() to f.length()

    private fun checkExternalEdits() {
        for (e in externalEdits) {
            if (!e.file.exists() || e.file.length() == 0L) continue
            val now = stamp(e.file)
            if (now == e.stamp) continue
            e.stamp = now
            // upload a snapshot: the other app may keep writing to its file
            val snap = File(cacheDir, "edit/${System.currentTimeMillis()}/${e.item.name}")
            try {
                snap.parentFile!!.mkdirs(); e.file.copyTo(snap, overwrite = true)
            } catch (x: Exception) {
                Toast.makeText(this, "Could not save ${e.item.name}", Toast.LENGTH_SHORT).show()
                continue
            }
            uploadBack(e.item, snap)
        }
    }

    /** Edit a Word / Excel / PowerPoint file in the bundled ONLYOFFICE editor; the result is uploaded back. */
    @Suppress("DEPRECATION")
    private fun openOfficeEditor(pos: Int) {
        if (pos !in items.indices) return
        val item = items[pos]
        player?.pause()
        // edits auto-saved in an editor that was closed before Done: offer to carry on with them
        val key = OfficeEditorActivity.draftKey(remote.name, item.path)
        val draft = OfficeEditorActivity.draft(this, key, item.name)
        if (draft != null) {
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(draft.lastModified()))
            val day = android.text.format.DateUtils.isToday(draft.lastModified())
            val whenText = if (day) time else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(draft.lastModified()))
            AlertDialog.Builder(this)
                .setTitle("Unsaved edits")
                .setMessage("You have edits to ${item.name} from $whenText that were not uploaded. Continue with them?")
                .setPositiveButton("Continue") { _, _ ->
                    val copy = File(cacheDir, "open/${System.currentTimeMillis()}/${item.name}")
                    try {
                        copy.parentFile!!.mkdirs(); draft.copyTo(copy, true)
                        officeEditPos = pos
                        startActivityForResult(OfficeEditorActivity.intent(this, copy, remote, item, key, fromDraft = true), REQ_OFFICE_EDIT)
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not open the saved edits", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Discard") { _, _ ->
                    OfficeEditorActivity.deleteDraft(this, key)
                    openOfficeEditor(pos)
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
            return
        }
        loading.visibility = View.VISIBLE
        io.execute {
            val f = fetch(item)
            val copy = f?.let {
                try {
                    File(cacheDir, "open/${System.currentTimeMillis()}/${item.name}").also { c -> c.parentFile!!.mkdirs(); it.copyTo(c, true) }
                } catch (e: Exception) { null }
            }
            ui.post {
                loading.visibility = View.GONE
                if (isFinishing || isDestroyed) return@post
                if (copy == null) { Toast.makeText(this, "Could not load ${item.name}", Toast.LENGTH_SHORT).show(); return@post }
                officeEditPos = pos
                startActivityForResult(OfficeEditorActivity.intent(this, copy, remote, item, key), REQ_OFFICE_EDIT)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OFFICE_EDIT) return
        val pos = officeEditPos.also { officeEditPos = -1 }
        val path = data?.getStringExtra(OfficeEditorActivity.EXTRA_RESULT_PATH)
        Log.i("OfficeEditor", "result=$resultCode path=$path pos=$pos")
        if (resultCode == OfficeEditorActivity.RESULT_UPLOADED && pos in items.indices) {
            // the editor already uploaded the last version (when the app was left); just refresh
            updateTitle(current()); pager.adapter?.notifyItemChanged(pos); return
        }
        if (resultCode != RESULT_OK || path == null || pos !in items.indices) return
        uploadBack(items[pos], File(path))
        pager.adapter?.notifyItemChanged(pos)   // re-render the preview from the edited copy
    }

    private fun uploadBack(item: FileItem, f: File) {
        EditUploads.upload(this, remote, item, f)
        updateTitle(current())
        Toast.makeText(this, "Saving ${item.name}…", Toast.LENGTH_SHORT).show()
    }

    /**
     * Presentations, Drive style: the first slide (the thumbnail PowerPoint / Impress store inside the
     * file), the number of slides and an Open button for the editor, where they can be viewed and edited.
     */
    private fun bindSlides(frame: FrameLayout, position: Int, item: FileItem) {
        val spin = spinner(frame)
        io.execute {
            val file = fetch(item)
            var thumb: Bitmap? = null
            var slides = 0
            if (file != null) try {
                java.util.zip.ZipFile(file).use { z ->
                    val slide = Regex("^ppt/slides/slide\\d+\\.xml$")
                    for (e in z.entries()) if (slide.matches(e.name)) slides++
                    if (slides == 0) {   // OpenDocument: one <draw:page> per slide
                        z.getEntry("content.xml")?.let { c ->
                            slides = Regex("<draw:page[ >]").findAll(z.getInputStream(c).bufferedReader().readText()).count()
                        }
                    }
                    val t = z.getEntry("docProps/thumbnail.jpeg") ?: z.getEntry("Thumbnails/thumbnail.png")
                    if (t != null) thumb = z.getInputStream(t).use { android.graphics.BitmapFactory.decodeStream(it) }
                }
            } catch (e: Exception) { /* old binary .ppt: no zip, no thumbnail */ }
            ui.post {
                spin.visibility = View.GONE
                if (file == null) { message(frame, "Couldn't load ${item.name}"); return@post }
                if (frame.tag != position) return@post
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setBackgroundColor(DOC_BG)
                    setPadding(dp(24), topBar.height.takeIf { it > 0 } ?: dp(100), dp(24), dp(24))
                }
                val w = resources.displayMetrics.widthPixels - dp(48)
                val card = ImageView(this).apply {
                    setBackgroundColor(Color.WHITE)
                    elevation = dp(2).toFloat()
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    if (thumb != null) setImageBitmap(thumb)
                    else { setImageResource(R.drawable.ic_file); setColorFilter(0xFF444746.toInt()); scaleType = ImageView.ScaleType.CENTER }
                    setOnClickListener { if (OfficeEditorActivity.canEdit(item.name)) openOfficeEditor(position) }
                }
                col.addView(card, LinearLayout.LayoutParams(w, w * 9 / 16))
                col.addView(TextView(this).apply {
                    text = when { slides == 1 -> "1 slide"; slides > 1 -> "$slides slides"; else -> "Presentation" }
                    textSize = 14f; setTextColor(0xFF444746.toInt()); gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, dp(16))
                })
                if (OfficeEditorActivity.canEdit(item.name)) {
                    col.addView(TextView(this).apply {
                        text = "Open"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xFF0B57D0.toInt()) }
                        setPadding(dp(24), 0, dp(24), 0)
                        setOnClickListener { openOfficeEditor(position) }
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40)))
                } else {
                    col.addView(TextView(this).apply {
                        text = "Open it in another app with ↗"
                        textSize = 14f; setTextColor(0xFF444746.toInt()); gravity = Gravity.CENTER
                    })
                }
                frame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        }
    }

    private fun bindOther(frame: FrameLayout, item: FileItem, note: String = "No preview available") {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setOnClickListener { toggleBars() }
        }
        col.addView(ImageView(this).apply { setImageResource(R.drawable.ic_file); setColorFilter(Color.WHITE) },
            LinearLayout.LayoutParams(dp(72), dp(72)))
        col.addView(TextView(this).apply {
            text = "${item.name}\n$note"; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(24), dp(16), dp(24), dp(16))
        })
        col.addView(Button(this).apply { text = "Open with"; setOnClickListener { openWith(items.indexOf(item)) } })
        frame.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    // --- actions --------------------------------------------------------------------------------

    private fun openWith(pos: Int) {
        if (pos !in items.indices) return
        val item = items[pos]
        player?.pause()
        val ext = item.name.substringAfterLast('.', "").lowercase()
        val mime = item.mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        // players can stream the local http url; other apps (office, pdf readers, ...) only accept
        // content:// uris, so fetch the file to the cache and share it through the FileProvider
        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
            launchOpenWith(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url(item)), mime))
            return
        }
        loading.visibility = View.VISIBLE
        io.execute {
            val f = fetch(item)
            ui.post {
                loading.visibility = View.GONE
                if (isFinishing || isDestroyed) return@post
                if (f == null) {
                    Toast.makeText(this, "Could not load ${item.name}", Toast.LENGTH_SHORT).show()
                    return@post
                }
                // the other app edits its own copy (the viewer cache may be refetched under it);
                // changes are uploaded back when the user returns here (checkExternalEdits)
                val copy = File(cacheDir, "open/${System.currentTimeMillis()}/${item.name}")
                try {
                    copy.parentFile!!.mkdirs(); f.copyTo(copy, overwrite = true)
                } catch (e: Exception) {
                    Toast.makeText(this, "Could not load ${item.name}", Toast.LENGTH_SHORT).show()
                    return@post
                }
                externalEdits.add(ExternalEdit(item, copy, stamp(copy)))
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", copy, item.name)
                launchOpenWith(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
        }
    }

    private fun launchOpenWith(view: Intent) {
        try {
            startActivity(Intent.createChooser(view, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun download(pos: Int) {
        if (pos !in items.indices) return
        val item = items[pos]
        val target = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
        AlertDialog.Builder(this)
            .setMessage("Download ${item.name} to Download?")
            .setPositiveButton("Download") { _, _ ->
                EphemeralTaskManager.queueDownload(this, remote, item, target)
                Toast.makeText(this, "Downloading ${item.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onBackPressed() {
        if (editing) {
            AlertDialog.Builder(this).setMessage("Save changes?")
                .setPositiveButton("Save") { _, _ -> toggleEdit(saveFirst = true) }
                .setNegativeButton("Discard") { _, _ -> toggleEdit(saveFirst = false) }
                .show()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        checkExternalEdits()
    }

    override fun onPause() {
        super.onPause()
        saveResume()
        player?.pause()
    }

    override fun onDestroy() {
        checkExternalEdits()
        super.onDestroy()
        saveResume()
        player?.release(); player = null
        pdfRenderers.values.forEach { r -> synchronized(r) { try { r.close() } catch (_: Exception) {} } }
        officeViews.values.forEach { it.destroy() }
        io.shutdownNow()
        stopService(Intent(this, StreamingService::class.java))
    }
}

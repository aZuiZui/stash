package io.github.azuizui.stash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import ca.pkay.rcloneexplorer.R
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * In-app Word / Excel / PowerPoint editor: the ONLYOFFICE mobile editors, fully offline (bundled by
 * the :onlyoffice Gradle project into assets/onlyoffice, see onlyoffice/bundle.py).
 * PDFs open in PDF.js instead (the :pdfjs project, assets/pdfjs): fill in forms, sign, highlight,
 * draw, add text and images. Both pages talk to this activity through the same StashBridge calls.
 *
 * Input: a local copy of the file ([EXTRA_PATH]). On Done or back, an edited document is converted
 * back to its format and written to a new file returned as [EXTRA_RESULT_PATH] (RESULT_OK); the
 * caller uploads it. Unchanged documents return RESULT_CANCELED.
 *
 * Everything is served from the fake origin https://stash.local/ (assets + the file); nothing else
 * is reachable from the page.
 */
class OfficeEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "office_path"
        const val EXTRA_NAME = "office_name"
        const val EXTRA_RESULT_PATH = "office_result_path"
        private const val TAG = "OfficeEditor"
        private const val ORIGIN = "https://stash.local/"

        // Appropriate Legal Notices (AGPL-3.0 section 5) for the modified ONLYOFFICE editors.
        private const val OFFICE_NOTICE =
            "Word, Excel and PowerPoint files are edited with the ONLYOFFICE mobile editors and the x2t " +
            "converter, copyright (C) Ascensio System SIA 2009-2026, as packaged by wasmtools/office-web.\n\n" +
            "Stash runs a modified version, based on the original ONLYOFFICE software developed by " +
            "Ascensio System SIA, modified for Stash on 2026-11-12: its own toolbar replaces the editor's, " +
            "and offline start-up fixes are added (onlyoffice/stash in the Stash source).\n\n" +
            "This program is free software under the GNU Affero General Public License version 3, " +
            "with the additional terms in ONLYOFFICE's LICENSE file. It comes WITHOUT ANY WARRANTY.\n\n" +
            "Licence: https://www.gnu.org/licenses/agpl-3.0.html\n" +
            "Source: https://github.com/aZuiZui/stash and https://github.com/wasmtools/office-web\n\n" +
            "ONLYOFFICE is a trademark of Ascensio System SIA. Stash is not affiliated with or endorsed by it."
        private const val PDF_NOTICE =
            "PDF files are edited with PDF.js, copyright Mozilla Foundation and contributors, licensed " +
            "under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0). " +
            "Stash adds its own toolbar (pdfjs/stash in the Stash source). It runs offline on this phone."

        /** Formats the bundled editors can open and write back unchanged. */
        val EDITABLE = setOf("docx", "odt", "xlsx", "xls", "ods", "pptx", "odp", "pdf")

        fun canEdit(name: String) = name.substringAfterLast('.', "").lowercase() in EDITABLE

        const val EXTRA_DRAFT_KEY = "office_draft_key"
        const val EXTRA_REMOTE = "office_remote"
        const val EXTRA_ITEM = "office_item"
        /** Result: the last version was already uploaded by the editor itself (when the app was left). */
        const val RESULT_UPLOADED = RESULT_FIRST_USER + 1
        const val EXTRA_FROM_DRAFT = "office_from_draft"
        private const val AUTOSAVE_MS = 30_000L

        /**
         * @param draftKey identifies the cloud file (remote + path); auto-saves go to its draft folder
         * @param fromDraft [file] is a recovered draft: it counts as edited from the start
         */
        fun intent(ctx: Context, file: File, remote: RemoteItem, item: FileItem, draftKey: String, fromDraft: Boolean = false): Intent =
            Intent(ctx, OfficeEditorActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath).putExtra(EXTRA_NAME, item.name)
                .putExtra(EXTRA_REMOTE, remote).putExtra(EXTRA_ITEM, item)
                .putExtra(EXTRA_DRAFT_KEY, draftKey).putExtra(EXTRA_FROM_DRAFT, fromDraft)

        /** Auto-saved, not yet uploaded edits live in filesDir/drafts/<key>/<file name>. */
        fun draftKey(remote: String, path: String): String =
            java.security.MessageDigest.getInstance("SHA-1").digest("$remote\u0000$path".toByteArray())
                .joinToString("") { "%02x".format(it) }

        fun draftDir(ctx: Context, key: String) = File(ctx.filesDir, "drafts/$key")

        /** The draft for [key], if there is one. */
        fun draft(ctx: Context, key: String, name: String): File? =
            File(draftDir(ctx, key), name).takeIf { it.isFile && it.length() > 0 }

        fun deleteDraft(ctx: Context, key: String) { draftDir(ctx, key).deleteRecursively() }
    }

    private lateinit var source: File
    private lateinit var name: String
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var bottom: LinearLayout
    private lateinit var progress: ProgressBar
    private var ready = false
    private var dirty = false
    private var saving = false          // a final save (Done) is running
    private var autoSaving = false      // an auto-save is running
    private var finalAfterAuto = false  // Done was tapped during an auto-save
    private var saveKind = "final"
    private var changedSinceAutosave = false
    private var changedSinceUpload = false   // edits not yet uploaded by a background save
    private var uploadedOnce = false         // the editor itself uploaded a version (app was left)
    private var uploadAfterAuto = false
    private var remote: RemoteItem? = null
    private var item: FileItem? = null
    private var draftKey = ""
    private val autosave = object : Runnable {
        override fun run() {
            autoSave()
            web.postDelayed(this, AUTOSAVE_MS)
        }
    }
    private var saveOut: FileOutputStream? = null
    private var saveFile: File? = null
    private val droppedFonts = HashSet<String>()
    private var fallbackFont = ""
    private var isPdf = false
    private val toolBtns = LinkedHashMap<String, ImageView>()

    // PDF.js "Add image" / signature from a picture: the page's <input type=file>
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = File(intent.getStringExtra(EXTRA_PATH) ?: return finish())
        name = intent.getStringExtra(EXTRA_NAME) ?: source.name
        draftKey = intent.getStringExtra(EXTRA_DRAFT_KEY) ?: ""
        @Suppress("DEPRECATION")
        remote = intent.getParcelableExtra(EXTRA_REMOTE)
        @Suppress("DEPRECATION")
        item = intent.getParcelableExtra(EXTRA_ITEM)
        if (intent.getBooleanExtra(EXTRA_FROM_DRAFT, false)) { dirty = true; changedSinceUpload = true }
        isPdf = name.substringAfterLast('.', "").equals("pdf", true)
        if (!isPdf) loadFontFallbacks()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        window.statusBarColor = BAR

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BAR) }
        root.addView(topBar(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFFF1F3F4.toInt()) }
        web = WebView(this)
        frame.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        progress = ProgressBar(this)
        frame.addView(progress, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        bottom = if (isPdf) pdfBar() else bottomBar()
        root.addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        web.addJavascriptInterface(Bridge(), "StashBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = serve(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { pickImage.launch("image/*"); true } catch (e: Exception) { fileCallback = null; false }
            }
        }
        web.loadUrl(if (isPdf) ORIGIN + "pdf/web/viewer.html?file=" + Uri.encode("/pdf/file") else ORIGIN + "oo/editor.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishEditing()
        })
    }

    // --- bars: Google Docs / Sheets style ---------------------------------------------------------
    // Top: done (✓ pill once edited, ← before), undo, redo, ⋮. Bottom: B I U, text colour, highlight,
    // alignment. The buttons call window.stashCmd() in editor.js, which drives the ONLYOFFICE engine.

    private val BAR = 0xFFF0F4F9.toInt()        // Google Drive surface, same as the viewer bar
    private val ICON = 0xFF444746.toInt()
    private val ACTIVE_BG = 0xFFD3E3FD.toInt()
    private val BLUE = 0xFF0B57D0.toInt()

    private lateinit var doneBtn: ImageView
    private lateinit var undoBtn: ImageView
    private lateinit var redoBtn: ImageView
    private lateinit var boldBtn: ImageView
    private lateinit var italicBtn: ImageView
    private lateinit var underlineBtn: ImageView
    private lateinit var alignBtn: ImageView
    private lateinit var colorBar: View
    private var align = "left"
    private var cellEditing = false     // Sheets: typing into a cell, not yet committed
    private var textColor = Color.BLACK

    private fun icon(res: Int, desc: String, onClick: (View) -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(ICON)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER
        background = rippleOval()
        setOnClickListener { onClick(it) }
    }

    private fun rippleOval() = android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x1F444746), null,
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })

    private fun cmd(name: String, arg: String? = null) {
        val a = if (arg == null) "" else ",'" + arg + "'"
        web.evaluateJavascript("window.stashCmd && window.stashCmd('$name'$a)", null)
    }

    private fun topBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(8), 0, dp(4), 0)
            minimumHeight = dp(64)
        }
        doneBtn = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Done"
            // Sheets: ✓ first confirms the cell being typed (like Google Sheets), then saves
            setOnClickListener { if (cellEditing) cmd("commitCell") else finishEditing() }
        }
        bar.addView(doneBtn, LinearLayout.LayoutParams(dp(56), dp(40)).apply { marginEnd = dp(8) })
        showDone(false)
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = name.substringBeforeLast('.'); textSize = 16f; setTextColor(0xFF1F1F1F.toInt()); setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        status = TextView(this).apply { text = "Opening…"; textSize = 12f; setTextColor(0xFF5F6368.toInt()) }
        titles.addView(status)
        bar.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        undoBtn = icon(R.drawable.oo_undo, "Undo") { cmd("undo") }
        redoBtn = icon(R.drawable.oo_redo, "Redo") { cmd("redo") }
        bar.addView(undoBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(redoBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        bar.addView(icon(R.drawable.ic_more_vert, "More") { showMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        setEnabledLook(undoBtn, false); setEnabledLook(redoBtn, false)
        return bar
    }

    /** Google Docs: a blue ✓ pill once there is something to save, a plain ← before. */
    private fun showDone(edited: Boolean) {
        if (edited) {
            doneBtn.setImageResource(R.drawable.ic_check)
            doneBtn.setColorFilter(Color.WHITE)
            doneBtn.background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(BLUE) }
        } else {
            doneBtn.setImageResource(R.drawable.ic_arrow_back)
            doneBtn.setColorFilter(ICON)
            doneBtn.background = rippleOval()
        }
    }

    private fun bottomBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        val lp = { LinearLayout.LayoutParams(0, dp(48), 1f) }
        boldBtn = icon(R.drawable.oo_bold, "Bold") { cmd("bold") }
        italicBtn = icon(R.drawable.oo_italic, "Italic") { cmd("italic") }
        underlineBtn = icon(R.drawable.oo_underline, "Underline") { cmd("underline") }
        bar.addView(boldBtn, lp()); bar.addView(italicBtn, lp()); bar.addView(underlineBtn, lp())
        // text colour: the letter with a colour stripe under it
        val color = FrameLayout(this).apply {
            background = rippleOval()
            setOnClickListener { pickColor(it, false) }
            contentDescription = "Text colour"
        }
        color.addView(ImageView(this).apply { setImageResource(R.drawable.oo_text_color); setColorFilter(ICON); scaleType = ImageView.ScaleType.CENTER },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        colorBar = View(this).apply { setBackgroundColor(textColor) }
        color.addView(colorBar, FrameLayout.LayoutParams(dp(18), dp(3), Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = dp(9) })
        bar.addView(color, lp())
        bar.addView(icon(R.drawable.oo_highlight, "Highlight") { pickColor(it, true) }, lp())
        bar.addView(View(this).apply { setBackgroundColor(0x33444746) }, LinearLayout.LayoutParams(dp(1), dp(28)))
        alignBtn = icon(R.drawable.oo_align_left, "Alignment") { pickAlign(it) }
        bar.addView(alignBtn, lp())
        return bar
    }

    /** PDFs: the PDF.js tools. Form fields need no tool, they are filled in by tapping them. */
    private fun pdfBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        listOf(
            Triple("highlight", R.drawable.pdf_highlight, "Highlight"),
            Triple("draw", R.drawable.pdf_draw, "Draw"),
            Triple("text", R.drawable.pdf_text, "Add text"),
            Triple("sign", R.drawable.pdf_sign, "Sign"),
            Triple("image", R.drawable.pdf_image, "Add image"),
        ).forEach { (tool, res, desc) ->
            // Google bottom-navigation style: icon in a pill (filled when active), label underneath
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                isClickable = true
                setOnClickListener { cmd("tool", tool) }
                contentDescription = desc
            }
            val b = ImageView(this).apply {
                setImageResource(res); setColorFilter(ICON); scaleType = ImageView.ScaleType.CENTER
                background = rippleOval()
                isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            if (tool == "highlight") b.clearColorFilter()   // keeps its yellow bar
            item.addView(b, LinearLayout.LayoutParams(dp(56), dp(32)))
            item.addView(TextView(this).apply {
                text = desc; textSize = 12f; setTextColor(0xFF444746.toInt()); gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            toolBtns[tool] = b
            bar.addView(item, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return bar
    }

    private fun setEnabledLook(v: ImageView, on: Boolean) {
        v.isEnabled = on
        // dim with alpha: a translucent colour filter would let the icon's black show through
        v.setColorFilter(ICON)
        v.imageAlpha = if (on) 255 else 97
    }

    private fun setActive(v: ImageView, on: Boolean) {
        v.background = if (on) GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(ACTIVE_BG) } else rippleOval()
        v.setColorFilter(if (on) 0xFF041E49.toInt() else ICON)
    }

    private val ALIGN_ICONS = linkedMapOf(
        "left" to R.drawable.oo_align_left, "center" to R.drawable.oo_align_center,
        "right" to R.drawable.oo_align_right, "justify" to R.drawable.oo_align_justify)

    private fun pickAlign(anchor: View) {
        val row = popupRow()
        val popup = popup(row)
        ALIGN_ICONS.forEach { (a, res) ->
            row.addView(icon(res, a) { cmd("align", a); popup.dismiss() }.also { setActive(it, a == align) },
                LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        popup.showAsDropDown(anchor, 0, -dp(112))
    }

    private val TEXT_COLORS = listOf("#000000", "#5F6368", "#D93025", "#E37400", "#188038", "#1A73E8", "#9334E6")
    private val HIGHLIGHTS = listOf("#FFFF00", "#B7E1CD", "#C6DAFC", "#FAD2CF", "#FCE8B2")

    private fun pickColor(anchor: View, highlight: Boolean) {
        val row = popupRow()
        val popup = popup(row)
        val colors = if (highlight) listOf("none") + HIGHLIGHTS else TEXT_COLORS
        for (c in colors) {
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (c == "none") { setColor(Color.WHITE); setStroke(dp(2), 0xFFD93025.toInt()) } else setColor(Color.parseColor(c))
                }
                contentDescription = if (c == "none") "No highlight" else c
                setOnClickListener {
                    cmd(if (highlight) "highlight" else "color", c)
                    if (!highlight) { textColor = Color.parseColor(c); colorBar.setBackgroundColor(textColor) }
                    popup.dismiss()
                }
            }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) })
        }
        popup.showAsDropDown(anchor, 0, -dp(112))
    }

    private fun popupRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.WHITE) }
        elevation = dp(8).toFloat()
    }

    private fun popup(content: View) = android.widget.PopupWindow(content, WRAP_CONTENT, WRAP_CONTENT, true).apply {
        elevation = dp(8).toFloat()
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    private fun showMenu(anchor: View) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add("Undo all changes")
            menu.add("Close without saving")
            menu.add("About the editor")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Undo all changes" -> undoAll()
                    "Close without saving" -> {
                        if (draftKey.isNotEmpty()) deleteDraft(this@OfficeEditorActivity, draftKey)
                        setResult(RESULT_CANCELED); finish()
                    }
                    else -> androidx.appcompat.app.AlertDialog.Builder(this@OfficeEditorActivity)
                        .setTitle("Editor")
                        .setMessage(if (isPdf) PDF_NOTICE else OFFICE_NOTICE)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
                true
            }
        }.show()
    }

    private fun applyState(json: String) {
        val j = JSONObject(json)
        // The mobile editors don't send onDocumentStateChange; anything to undo means the document changed.
        if ((j.optBoolean("undo") || j.optBoolean("modified")) && !dirty) {
            dirty = true
            changedSinceAutosave = true; changedSinceUpload = true
            if (ready && !saving && !autoSaving) status.text = "Edited"
            showDone(true)
        }
        setEnabledLook(undoBtn, j.optBoolean("undo")); setEnabledLook(redoBtn, j.optBoolean("redo"))
        if (isPdf) {
            val tool = j.optString("tool", "none")
            toolBtns.forEach { (t, b) ->
                b.background = if (t == tool) GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(ACTIVE_BG) } else rippleOval()
                if (t != "highlight") b.setColorFilter(if (t == tool) 0xFF041E49.toInt() else ICON)
            }
            return
        }
        setActive(boldBtn, j.optBoolean("bold"))
        setActive(italicBtn, j.optBoolean("italic"))
        setActive(underlineBtn, j.optBoolean("underline"))
        align = j.optString("align", "left")
        cellEditing = j.optBoolean("cellEdit")
        showDone(dirty || cellEditing)
        alignBtn.setImageResource(ALIGN_ICONS[align] ?: R.drawable.oo_align_left)
    }

    // --- assets -----------------------------------------------------------------------------------

    private fun loadFontFallbacks() {
        try {
            val j = JSONObject(assets.open("onlyoffice/fonts-fallback.json").bufferedReader().readText())
            fallbackFont = j.getString("fallback")
            val d = j.getJSONArray("dropped")
            for (i in 0 until d.length()) droppedFonts.add(d.getString(i))
        } catch (e: Exception) {
            Log.w(TAG, "no font fallbacks", e)
        }
    }

    private fun serve(url: Uri): WebResourceResponse {
        if (url.host != "stash.local") return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, null)
        val path = url.path ?: ""
        return try {
            when {
                path == "/oo/file" || path == "/pdf/file" ->
                    WebResourceResponse(if (isPdf) "application/pdf" else "application/octet-stream", null, source.inputStream())
                path.startsWith("/pdf/") -> {
                    val rel = path.removePrefix("/pdf/")
                    if (rel.split('/').any { it == ".." }) throw FileNotFoundException(rel)
                    WebResourceResponse(mime(rel), if (isText(rel)) "utf-8" else null, assets.open("pdfjs/$rel"))
                        .apply { responseHeaders = mapOf("Cache-Control" to "no-cache") }
                }
                path.startsWith("/oo/") -> {
                    var rel = path.removePrefix("/oo/")
                    // fonts left out of the bundle (CJK…) are answered with a Latin one instead of a 404
                    if (rel.startsWith("vendor/fonts/") && rel.substringAfterLast('/') in droppedFonts)
                        rel = "vendor/fonts/$fallbackFont"
                    WebResourceResponse(mime(rel), if (isText(rel)) "utf-8" else null, assets.open("onlyoffice/$rel"))
                        .apply { responseHeaders = mapOf("Cache-Control" to "no-cache") }
                }
                else -> WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
            }
        } catch (e: FileNotFoundException) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null)
        } catch (e: Exception) {
            Log.w(TAG, "serve $path", e)
            WebResourceResponse("text/plain", "utf-8", 500, "Error", null, null)
        }
    }

    private fun isText(p: String) = p.substringAfterLast('.').lowercase() in setOf("html", "js", "mjs", "css", "json", "svg", "txt", "ftl")

    private fun mime(p: String) = when (p.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "ftl" -> "text/plain"
        "css" -> "text/css"
        "json" -> "application/json"
        "wasm" -> "application/wasm"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }

    // --- done / save ------------------------------------------------------------------------------

    private fun finishEditing() {
        Log.i(TAG, "done: ready=$ready dirty=$dirty cell=$cellEditing saving=$saving autoSaving=$autoSaving")
        if (saving) return
        if (cellEditing) dirty = true   // the open cell is committed by stashSave()
        if (ready && !dirty) {
            // don't trust events alone: ask the editor whether anything changed before closing
            web.evaluateJavascript("window.stashCheck ? window.stashCheck() : null") { res ->
                val j = try { JSONObject(JSONObject("{\"v\":$res}").optString("v", "{}")) } catch (e: Exception) { JSONObject() }
                Log.i(TAG, "check: $j")
                if (j.optBoolean("modified") || j.optBoolean("cellEdit") || j.optBoolean("undo")) {
                    dirty = true; finishEditing()
                } else {
                    setResult(RESULT_CANCELED); finish()
                }
            }
            return
        }
        if (!ready || !dirty) {
            setResult(RESULT_CANCELED); finish(); return
        }
        if (uploadedOnce && !changedSinceUpload && !cellEditing) {
            // the last version already went up when the app was left
            if (draftKey.isNotEmpty()) deleteDraft(this, draftKey)
            setResult(RESULT_UPLOADED); finish(); return
        }
        if (autoSaving) { finalAfterAuto = true; status.text = "Saving…"; return }
        saving = true
        status.text = "Saving…"
        progress.visibility = View.VISIBLE
        web.evaluateJavascript("window.stashSave('final')", null)
    }

    /** Keeps a copy on the phone while editing (timer and leaving the app), like Google Docs. */
    private fun autoSave() {
        if (!ready || !dirty || !changedSinceAutosave || saving || autoSaving || draftKey.isEmpty()) return
        autoSaving = true
        changedSinceAutosave = false
        web.evaluateJavascript("window.stashSave('auto')", null)
    }

    override fun onStart() {
        super.onStart()
        web.removeCallbacks(autosave)
        web.postDelayed(autosave, AUTOSAVE_MS)
    }

    override fun onStop() {
        web.removeCallbacks(autosave)
        if (!isFinishing) uploadInBackground()
        super.onStop()
    }

    /** Leaving the app with unsent edits: save and upload them now, keep the editor open. */
    private fun uploadInBackground() {
        if (!ready || !dirty || saving || remote == null || item == null) return
        if (!changedSinceUpload && !cellEditing) return
        if (autoSaving) { uploadAfterAuto = true; return }
        autoSaving = true
        changedSinceAutosave = false
        changedSinceUpload = false
        Log.i(TAG, "leaving the app: uploading")
        web.evaluateJavascript("window.stashSave('upload')", null)
    }

    /** ⋮ Undo all changes: reload the file as it was opened. */
    private fun undoAll() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage("Undo all changes since you opened $name?")
            .setPositiveButton("Undo all") { _, _ ->
                if (draftKey.isNotEmpty()) deleteDraft(this, draftKey)
                ready = false
                // if a version was already uploaded, Done must put the original back
                dirty = uploadedOnce
                changedSinceAutosave = false
                changedSinceUpload = uploadedOnce
                cellEditing = false
                status.text = "Opening…"
                bottom.visibility = View.GONE
                progress.visibility = View.VISIBLE
                showDone(dirty)
                web.reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveFailed(msg: String) {
        saving = false
        progress.visibility = View.GONE
        status.text = "Couldn't save"
        Toast.makeText(this, "Couldn't save $name: $msg", Toast.LENGTH_LONG).show()
    }

    /** Called from JavaScript on a binder thread; UI work is posted to the main thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun info(): String = JSONObject().put("name", name).put("ext", name.substringAfterLast('.', "docx")).toString()

        @JavascriptInterface
        fun log(msg: String) { Log.i(TAG, msg) }

        @JavascriptInterface
        fun onReady() = runOnUiThread {
            ready = true
            progress.visibility = View.GONE
            status.text = if (dirty) "Unsaved edits restored" else ""
            showDone(dirty)
            bottom.visibility = View.VISIBLE
        }

        @JavascriptInterface
        fun onDirty(d: Boolean) = runOnUiThread {
            if (d) { dirty = true; changedSinceAutosave = true; changedSinceUpload = true }
            if (ready && !saving && !autoSaving) { if (d) status.text = "Edited"; showDone(dirty) }
        }

        @JavascriptInterface
        fun onError(msg: String) = runOnUiThread {
            Log.w(TAG, "open failed: $msg")
            progress.visibility = View.GONE
            status.text = "Couldn't open"
            Toast.makeText(this@OfficeEditorActivity, "Couldn't open $name: $msg", Toast.LENGTH_LONG).show()
        }

        @JavascriptInterface
        fun onState(json: String) = runOnUiThread {
            try { applyState(json) } catch (e: Exception) { Log.w(TAG, "state", e) }
            // every edit moves the undo state; good enough to know an auto-save is due
            if (dirty) changedSinceAutosave = true; changedSinceUpload = true
        }

        @JavascriptInterface
        fun onSaveRequested() = runOnUiThread { if (dirty) finishEditing() }

        @JavascriptInterface
        fun saveStart(kind: String) {
            saveKind = kind
            val f = if (kind == "auto") File(draftDir(this@OfficeEditorActivity, draftKey), "$name.tmp")
                    else File(cacheDir, "edit/${System.currentTimeMillis()}/$name")
            f.parentFile?.mkdirs()
            saveFile = f
            saveOut = FileOutputStream(f)
        }

        @JavascriptInterface
        fun saveChunk(b64: String) { saveOut?.write(Base64.decode(b64, Base64.DEFAULT)) }

        @JavascriptInterface
        fun saveEnd(kind: String) {
            saveOut?.close(); saveOut = null
            val f = saveFile
            Log.i(TAG, "saved ($kind): ${f?.length()} bytes")
            if (kind == "upload") {
                val ok = f != null && f.length() > 0
                runOnUiThread {
                    autoSaving = false
                    val r = remote; val it = item
                    if (ok && r != null && it != null) {
                        // keep a draft too, in case the upload fails and Stash is closed
                        if (draftKey.isNotEmpty()) try { f!!.copyTo(File(draftDir(this@OfficeEditorActivity, draftKey), name), true) } catch (_: Exception) {}
                        EditUploads.upload(this@OfficeEditorActivity, r, it, f!!)
                        uploadedOnce = true
                        status.text = "Saved to cloud"
                    } else {
                        changedSinceUpload = true
                    }
                    if (finalAfterAuto) { finalAfterAuto = false; finishEditing() }
                }
                return
            }
            if (kind == "auto") {
                // write-then-rename: a draft is never half written
                val ok = f != null && f.length() > 0 && f.renameTo(File(f.parentFile, name))
                runOnUiThread {
                    autoSaving = false
                    if (ok && !saving) status.text = "Saved on phone"
                    if (finalAfterAuto) { finalAfterAuto = false; finishEditing() }
                    else if (uploadAfterAuto) { uploadAfterAuto = false; uploadInBackground() }
                }
                return
            }
            runOnUiThread {
                if (f == null || f.length() == 0L) { saveFailed("empty file"); return@runOnUiThread }
                // the final copy is handed to the viewer for upload; the draft is no longer needed
                if (draftKey.isNotEmpty()) deleteDraft(this@OfficeEditorActivity, draftKey)
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, f.absolutePath))
                finish()
            }
        }

        @JavascriptInterface
        fun onSaveFailed(kind: String, msg: String) {
            Log.w(TAG, "save ($kind) failed: $msg")
            try { saveOut?.close() } catch (_: Exception) {}
            saveOut = null
            runOnUiThread {
                if (kind != "final") {
                    autoSaving = false; changedSinceAutosave = true; changedSinceUpload = true
                    Log.w(TAG, "auto-save failed: $msg")
                    if (finalAfterAuto) { finalAfterAuto = false; finishEditing() }
                } else saveFailed(msg)
            }
        }
    }

    override fun onDestroy() {
        web.removeCallbacks(autosave)
        web.destroy()
        super.onDestroy()
    }
}

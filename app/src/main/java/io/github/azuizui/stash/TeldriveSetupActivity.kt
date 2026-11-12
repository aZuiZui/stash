package io.github.azuizui.stash

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ca.pkay.rcloneexplorer.Activities.MainActivity

/**
 * First-run setup: the user gives the NAS DB host/user/password, the bundled teldrive server
 * starts against it, the user logs in with Telegram in the teldrive web UI, and the rclone
 * remotes are created from the resulting session token.
 */
class TeldriveSetupActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private var cookiePoll: Runnable? = null
    private val pad by lazy { (16 * resources.displayMetrics.density).toInt() }

    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Teldrive setup"
        // Android 15+ draws apps edge to edge (targetSdk 35): keep every step - including the
        // teldrive login page in the WebView - clear of the status bar, nav bar and keyboard.
        root = FrameLayout(this)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        showDbForm(null)
    }

    private fun show(v: View) {
        root.removeAllViews()
        root.addView(v, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    override fun onDestroy() {
        cookiePoll?.let { ui.removeCallbacks(it) }
        super.onDestroy()
    }

    // --- step 1: database -------------------------------------------------------------------

    private fun field(parent: LinearLayout, label: String, value: String, type: Int): EditText {
        parent.addView(TextView(this).apply { text = label; setPadding(0, pad / 2, 0, 0) })
        return EditText(this).apply {
            setText(value); inputType = type; setSingleLine()
            parent.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun showDbForm(error: String?) {
        val db = TeldriveServer.db(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        col.addView(TextView(this).apply {
            text = "Teldrive database"
            textSize = 22f
        })
        col.addView(TextView(this).apply {
            text = "The app runs its own teldrive server on this phone, using your NAS database. " +
                "Off your home network you need a VPN to reach it."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val host = field(col, "Database IP / host", db.host, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val user = field(col, "Database user", db.user, InputType.TYPE_CLASS_TEXT)
        val pass = field(col, "Database password", db.pass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val port = field(col, "Port", db.port, InputType.TYPE_CLASS_NUMBER)
        val name = field(col, "Database name", db.name, InputType.TYPE_CLASS_TEXT)
        if (error != null) {
            col.addView(TextView(this).apply {
                text = error
                setTextColor(0xFFD32F2F.toInt())
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setPadding(0, pad, 0, 0)
            })
        }
        col.addView(Button(this).apply {
            text = "Connect"
            setOnClickListener {
                TeldriveServer.saveDb(
                    this@TeldriveSetupActivity,
                    TeldriveServer.Db(
                        host.text.toString().trim(), port.text.toString().trim().ifEmpty { "5432" },
                        name.text.toString().trim(), user.text.toString().trim(), pass.text.toString()
                    )
                )
                startServer()
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad })
        show(ScrollView(this).apply { addView(col) })
    }

    private fun showBusy(msg: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad)
        }
        col.addView(ProgressBar(this))
        col.addView(TextView(this).apply { text = msg; gravity = Gravity.CENTER; setPadding(0, pad, 0, 0) })
        show(col)
    }

    private fun startServer() {
        showBusy("Starting teldrive…")
        Thread {
            TeldriveServer.stop()                 // restart to pick up the new DB settings
            ui.post { TeldriveService.start(this) } // keeps it alive (its watchdog only restarts a dead process)
            val err = try {
                TeldriveServer.start(this); TeldriveServer.awaitUp(this)
            } catch (e: Exception) {
                e.toString()
            }
            ui.post { if (err == null) showLogin() else showDbForm("teldrive could not start:\n$err") }
        }.start()
    }

    // --- step 2: Telegram login in the teldrive web UI --------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun showLogin() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.removeAllCookies(null)

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl("${TeldriveServer.BASE_URL}/login")
        }
        show(web)

        // The UI is an SPA: watch the cookie jar instead of page loads.
        cookiePoll = object : Runnable {
            override fun run() {
                val token = cookies.getCookie(TeldriveServer.BASE_URL)
                    ?.split(';')?.map { it.trim() }
                    ?.firstOrNull { it.startsWith("access_token=") }
                    ?.substringAfter('=')
                    ?.takeIf { it.isNotEmpty() }
                if (token != null) {
                    cookies.flush()
                    web.stopLoading()
                    createRemotes(token)
                } else {
                    ui.postDelayed(this, 1000)
                }
            }
        }.also { ui.postDelayed(it, 1000) }
    }

    // --- step 3: rclone remotes -------------------------------------------------------------

    private fun createRemotes(token: String) {
        showBusy("Logged in. Creating the teldrive remote…")
        Thread {
            val result = try {
                TeldriveRemotes.create(this, token)
                TeldriveServer.markSetUp(this, token)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            ui.post {
                result.onSuccess {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }.onFailure { showDbForm("Login worked but creating the teldrive remote failed:\n${it.message}") }
            }
        }.start()
    }

    override fun onBackPressed() {
        // Back from the login page returns to the DB form instead of leaving setup half-done.
        if (cookiePoll != null) {
            cookiePoll?.let { ui.removeCallbacks(it) }
            cookiePoll = null
            showDbForm(null)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

package com.sudosilo.cliprig

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.DateFormat
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var titleText: TextView
    private val screens = mutableMapOf<String, View>()
    private var current = "launch"

    private lateinit var progressCard: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var overlayHost: FrameLayout

    private lateinit var gameSpinner: Spinner
    private lateinit var addressField: android.widget.EditText
    private var launchGames = listOf<GameProfile>()
    private var launchLen = 15
    private lateinit var launchStyle: Spinner
    private lateinit var startButton: TextView
    private var pendingCamera = false
    private lateinit var preview: ThemePreviewView
    private lateinit var themeList: LinearLayout
    private lateinit var musicModePick: Spinner
    private lateinit var musicList: LinearLayout
    private lateinit var musicInfo: TextView
    private var player: android.media.MediaPlayer? = null
    private var playingUri = ""

    private lateinit var inboxList: RecyclerView
    private lateinit var inboxGate: View
    private lateinit var inboxGameSpinner: Spinner
    private var inboxGameId = ""

    private lateinit var libraryList: RecyclerView
    private lateinit var libraryFilter: Spinner
    private var libraryFilterName = "All games"
    private lateinit var libraryEmpty: TextView

    private lateinit var gamesList: RecyclerView
    private lateinit var updateItem: TextView
    private var pendingRelease: Updater.Release? = null

    private val thumbs = LruCache<String, Bitmap>(80)
    private val lenOptions = listOf(10, 15, 20, 30)

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingCamera) {
            if (it[Manifest.permission.CAMERA] == true || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            else toast("cliprig needs the camera permission for live camera")
        } else if (it[Manifest.permission.RECORD_AUDIO] == true) askProjection()
        else toast("cliprig needs the audio permission to record game sound")
    }
    private val videoPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshInbox()
    }
    private var pendingLaunch: Triple<String, String, String>? = null
    private val pickVideo = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { Pipeline.fromUri(this, uri, inboxGameId, true); show("library") }
    }
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = pendingLaunch ?: return@registerForActivityResult
        if (r.resultCode != Activity.RESULT_OK || r.data == null) { toast("Recording was not approved"); return@registerForActivityResult }
        SessionLog.reset(p.first, p.second, p.third, launchLen)
        RecordService.start(this, r.resultCode, r.data!!)
        startActivity(Intent(this, PlayActivity::class.java).putExtra(PlayActivity.EXTRA_URL, p.third))
    }
    private val folderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Store.musicFolder = uri.toString()
            musicFolderText?.text = folderName()
            if (current == "music") refreshMusic()
        }
    }
    private var musicFolderText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        Pipeline.listener = { showStatus(it) }
        Pipeline.onClipAdded = { refreshLibrary() }
        showStatus(Pipeline.status)
        handleIntent(intent)
        checkUpdate(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshLaunchGames()
        if (current == "inbox") refreshInbox()
        if (current == "library") refreshLibrary()
    }

    override fun onDestroy() {
        Pipeline.listener = null
        Pipeline.onClipAdded = null
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.getStringExtra("screen") == "library") show("library")
        if (i.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri: Uri? = i.getParcelableExtra(Intent.EXTRA_STREAM)
            i.action = null
            if (uri != null) chooseGame("Which game is this recording?") { gid ->
                Pipeline.fromUri(this, uri, gid, true)
                show("library")
            }
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // UI construction

    private fun buildUi() {
        drawer = DrawerLayout(this)
        drawer.setScrimColor(0x55000000)
        val content = FrameLayout(this).apply { setBackgroundColor(PAPER) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(3).toFloat()
        }
        val burger = TextView(this).apply {
            text = "☰"; textSize = 24f; setTextColor(BLUE); gravity = Gravity.CENTER
            setOnClickListener { drawer.openDrawer(GravityCompat.START) }
        }
        bar.addView(burger, LinearLayout.LayoutParams(dp(56), dp(56)))
        titleText = label("Launch", 20f, INK, true)
        bar.addView(titleText, LinearLayout.LayoutParams(0, -2, 1f))
        val dot = View(this).apply { background = rounded(BLUE, dp(6).toFloat()) }
        bar.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(18) })

        val screenHost = FrameLayout(this)
        screens["launch"] = buildLaunch()
        screens["inbox"] = buildInbox()
        screens["library"] = buildLibrary()
        screens["games"] = buildGames()
        screens["themes"] = buildThemes()
        screens["music"] = buildMusic()
        screens.values.forEach { screenHost.addView(it, FrameLayout.LayoutParams(-1, -1)); it.visibility = View.GONE }

        val stack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stack.addView(bar, LinearLayout.LayoutParams(-1, dp(56)))
        stack.addView(screenHost, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(stack, FrameLayout.LayoutParams(-1, -1))

        progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, dp(16).toFloat(), dp(2), BLUE)
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(14))
            visibility = View.GONE
        }
        progressText = label("", 15f, INK, true)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(BLUE)
        }
        progressCard.addView(progressText)
        progressCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
        content.addView(progressCard, FrameLayout.LayoutParams(-1, -2).apply {
            gravity = Gravity.BOTTOM; leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(24)
        })

        overlayHost = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
        content.addView(overlayHost, FrameLayout.LayoutParams(-1, -1))

        drawer.addView(content, DrawerLayout.LayoutParams(-1, -1))
        drawer.addView(buildDrawer(), DrawerLayout.LayoutParams(dp(300), -1).apply { gravity = GravityCompat.START })
        setContentView(drawer)

        ViewCompat.setOnApplyWindowInsetsListener(drawer) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, s.top, 0, s.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    overlayHost.visibility == View.VISIBLE -> closeOverlay()
                    current != "launch" -> show("launch")
                    else -> finish()
                }
            }
        })
        show("launch")
    }

    private fun show(name: String) {
        current = name
        screens.forEach { (k, v) -> v.visibility = if (k == name) View.VISIBLE else View.GONE }
        titleText.text = when (name) {
            "inbox" -> "Inbox"; "library" -> "Library"; "games" -> "Games"; "themes" -> "Themes"; "music" -> "Music"; else -> "Launch"
        }
        when (name) {
            "inbox" -> refreshInbox()
            "library" -> refreshLibrary()
            "games" -> refreshGames()
            "launch" -> refreshLaunchGames()
            "themes" -> refreshThemes()
            "music" -> refreshMusic()
        }
        if (name != "music") stopPreview()
        drawer.closeDrawer(GravityCompat.START)
    }

    private fun buildDrawer(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BLUE)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        head.addView(label("cliprig", 26f, Color.WHITE, true))
        head.addView(label("play it, clip it", 14f, 0xFFD6E4FF.toInt()))
        col.addView(head)

        fun nav(t: String, key: String) = label(t, 18f, INK, true).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setOnClickListener { show(key) }
        }
        col.addView(nav("Launch", "launch"))
        col.addView(nav("Inbox", "inbox"))
        col.addView(nav("Library", "library"))
        col.addView(nav("Games", "games"))
        col.addView(nav("Themes", "themes"))
        col.addView(nav("Music", "music"))

        updateItem = label("Check for update (${BuildConfig.VERSION_NAME})", 16f, BLUE, true).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setOnClickListener { checkUpdate(true) }
        }
        col.addView(updateItem)

        val settingsHead = label("Settings ▾", 18f, INK, true).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(BLUE_SOFT)
        }
        val settings = column(20).apply { visibility = View.GONE }
        settingsHead.setOnClickListener {
            val open = settings.visibility != View.VISIBLE
            settings.visibility = if (open) View.VISIBLE else View.GONE
            settingsHead.text = if (open) "Settings ▴" else "Settings ▾"
        }
        col.addView(settingsHead)
        col.addView(settings)
        buildSettings(settings)

        scroll.addView(col)
        return scroll
    }

    private fun buildSettings(s: LinearLayout) {
        fun row(name: String, options: List<String>, sel: Int, onPick: (Int) -> Unit) {
            s.add(label(name, 13f, MUTED, true), 10)
            s.add(picker(options, sel, onPick), 4)
        }
        row("Default clip length", lenOptions.map { "$it seconds" }, lenOptions.indexOf(Store.clipLenSec).coerceAtLeast(0)) {
            Store.clipLenSec = lenOptions[it]
        }
        val counts = (1..6).toList()
        val styles0 = listOf("Highlights", "Rapid fire", "Fast forward", "Highlights and rapid fire", "Everything")
        row("Clip style", listOf("Highlight clips", "Rapid fire montage", "Fast forward recap", "Highlights and rapid fire", "Everything"), styles0.indexOf(Store.clipStyle).coerceAtLeast(0)) {
            Store.clipStyle = styles0[it]; if (::launchStyle.isInitialized) launchStyle.setSelection(it, false)
        }
        val speeds = listOf(1f, 1.5f, 2f, 3f, 4f)
        row("Highlight speed", listOf("Normal", "1.5x fast forward", "2x fast forward", "3x fast forward", "4x fast forward"), speeds.indexOf(Store.speed).coerceAtLeast(0)) {
            Store.speed = speeds[it]
        }
        row("Clips per session", counts.map { "$it" }, Store.clipCount - 1) { Store.clipCount = counts[it] }
        val modes = listOf("Queue", "Random", "Off")
        row("Music", listOf("Play my queue in order", "Random from folder", "No music"), modes.indexOf(Store.musicMode).coerceAtLeast(0)) {
            Store.musicMode = modes[it]; if (::musicModePick.isInitialized) musicModePick.setSelection(it, false)
        }
        s.add(label("Music folder", 13f, MUTED, true), 10)
        musicFolderText = label(folderName(), 14f, INK)
        s.add(musicFolderText!!, 4)
        s.add(blueButton("Pick music folder", false) { folderLauncher.launch(null) }, 6)
        val vols = (1..10).map { it * 10 }
        row("Music volume", vols.map { "$it percent" }, vols.indexOf(Store.musicVolume).coerceAtLeast(0)) { Store.musicVolume = vols[it] }
        row("Game sound under music", vols.map { "$it percent" }, vols.indexOf(Store.gameVolume).coerceAtLeast(0)) { Store.gameVolume = vols[it] }
        val styles = listOf("Top", "Bottom", "Off")
        row("Caption spot", styles, styles.indexOf(Store.captionStyle).coerceAtLeast(0)) { Store.captionStyle = styles[it] }
        val frames = listOf("Fill", "Fit")
        row("Framing", listOf("Fill the phone screen", "Fit with bars"), frames.indexOf(Store.framing).coerceAtLeast(0)) { Store.framing = frames[it] }
        row("Title and end cards", listOf("On", "Off"), if (Store.titleCards) 0 else 1) { Store.titleCards = it == 0 }
        val keeps = listOf(1, 3, 7, 30)
        row("Keep raw recordings", keeps.map { if (it == 1) "1 day" else "$it days" }, keeps.indexOf(Store.rawKeepDays).coerceAtLeast(0)) { Store.rawKeepDays = keeps[it] }
        s.add(label("Default text", 13f, MUTED, true), 10)
        s.add(blueButton("Edit default text", false) { editDefaultCaptions() }, 4)
    }

    private fun folderName(): String {
        val f = Store.musicFolder
        if (f.isEmpty()) return "No folder picked"
        return runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(f)).substringAfter(':').ifEmpty { "Phone storage" } }
            .getOrDefault("Picked")
    }

    // Launch

    private fun buildLaunch(): View {
        val scroll = ScrollView(this)
        val c = column(20)
        c.add(label("Record from", 13f, MUTED, true))
        val sources = listOf("Browser", "Camera")
        c.add(picker(listOf("Game in the browser", "Live camera"), sources.indexOf(Store.source).coerceAtLeast(0)) {
            Store.source = sources[it]
            startButton.text = if (it == 1) "Start camera" else "Start playing"
        }, 6)
        c.add(label("Game", 13f, MUTED, true), 16)
        gameSpinner = picker(listOf("Typed address"), 0) {}
        c.add(gameSpinner, 6)
        c.add(label("Game address", 13f, MUTED, true), 16)
        addressField = field("game address", Store.lastUrl)
        c.add(addressField, 6)
        c.add(label("Clip length", 13f, MUTED, true), 16)
        launchLen = Store.clipLenSec
        c.add(picker(lenOptions.map { "$it seconds" }, lenOptions.indexOf(launchLen).coerceAtLeast(0)) { launchLen = lenOptions[it] }, 6)
        c.add(label("Clip style", 13f, MUTED, true), 16)
        val styles = listOf("Highlights", "Rapid fire", "Fast forward", "Highlights and rapid fire", "Everything")
        launchStyle = picker(listOf("Highlight clips", "Rapid fire montage", "Fast forward recap", "Highlights and rapid fire", "Everything"), styles.indexOf(Store.clipStyle).coerceAtLeast(0)) {
            Store.clipStyle = styles[it]
        }
        c.add(launchStyle, 6)
        startButton = blueButton(if (Store.source == "Camera") "Start camera" else "Start playing") { startRun() }
        c.add(startButton, 24, dp(60))
        val tip = label(
            "While you record, press volume down or tap the blue star to mark a moment you want in a clip. " +
                "Tap the red square or press back when you are done and cliprig starts cutting clips. " +
                "Live camera records with the phone camera and mic, and the round arrow flips cameras.",
            14f, MUTED
        ).apply {
            background = rounded(BLUE_SOFT, dp(14).toFloat())
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        c.add(tip, 24)
        scroll.addView(c)
        return scroll
    }

    private fun refreshLaunchGames() {
        if (!::gameSpinner.isInitialized) return
        launchGames = Store.games()
        val names = listOf("Typed address") + launchGames.map { it.name.ifBlank { it.url } }
        val selIndex = launchGames.indexOfFirst { it.id == Store.lastGameId }.let { if (it >= 0) it + 1 else 0 }
        gameSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        gameSpinner.setSelection(selIndex, false)
        gameSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos > 0) addressField.setText(launchGames[pos - 1].url)
                Store.lastGameId = if (pos > 0) launchGames[pos - 1].id else ""
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file:")) t else "https://$t"
    }

    private fun startRun(gameOverride: GameProfile? = null) {
        if (RecordService.instance != null) { toast("A recording is already running"); return }
        val g = gameOverride ?: launchGames.getOrNull(gameSpinner.selectedItemPosition - 1)
        if (gameOverride == null && Store.source == "Camera") {
            pendingCamera = true
            pendingLaunch = Triple(g?.id ?: "", g?.name ?: "Camera", "camera")
            val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) openCamera() else permLauncher.launch(missing.toTypedArray())
            return
        }
        pendingCamera = false
        val url = normalizeUrl(gameOverride?.url ?: addressField.text.toString())
        if (url.isEmpty()) { toast("Type a game address first"); return }
        Store.lastUrl = url
        val name = g?.name ?: url.removePrefix("https://").removePrefix("http://").substringBefore('/')
        pendingLaunch = Triple(g?.id ?: "", name, url)
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) askProjection() else permLauncher.launch(missing.toTypedArray())
    }

    private fun openCamera() {
        val p = pendingLaunch ?: return
        SessionLog.reset(p.first, p.second, "camera", launchLen)
        startActivity(Intent(this, CameraActivity::class.java))
    }

    private fun askProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mpm.createScreenCaptureIntent()
        projectionLauncher.launch(intent)
    }

    // Inbox

    data class Recording(val uri: Uri, val name: String, val durationMs: Long, val added: Long)

    private fun buildInbox(): View {
        val c = column(16)
        c.add(label("Clips from these recordings go to", 13f, MUTED, true))
        inboxGameSpinner = picker(listOf("No game"), 0) {}
        c.add(inboxGameSpinner, 6)
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val filters = listOf("Screen", "Camera", "All")
        filterRow.addView(picker(listOf("Screen recordings", "Camera videos", "All videos"), filters.indexOf(Store.inboxFilter).coerceAtLeast(0)) {
            if (Store.inboxFilter != filters[it]) { Store.inboxFilter = filters[it]; refreshInbox() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        filterRow.addView(blueButton("Pick a video", false) {
            pickVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }.apply { textSize = 14f; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        c.add(filterRow, 10)
        val frame = FrameLayout(this)
        inboxList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); clipToPadding = false; setPadding(0, 0, 0, dp(130)) }
        frame.addView(inboxList, FrameLayout.LayoutParams(-1, -1))
        val gate = column(8).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(PAPER)
        }
        gate.add(label("cliprig needs to see your screen recordings to list them here.", 15f, INK))
        gate.add(blueButton("Allow access to recordings") {
            val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            videoPermLauncher.launch(p)
        }, 12)
        inboxGate = gate
        frame.addView(gate, FrameLayout.LayoutParams(-1, -2))
        c.add(frame, 12, 0)
        (frame.layoutParams as LinearLayout.LayoutParams).weight = 1f
        frame.layoutParams.height = 0
        return c
    }

    private fun hasVideoPerm(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshInbox() {
        val games = Store.games()
        val names = listOf("No game") + games.map { it.name.ifBlank { it.url } }
        inboxGameSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val sel = games.indexOfFirst { it.id == inboxGameId }.let { if (it >= 0) it + 1 else 0 }
        inboxGameSpinner.setSelection(sel, false)
        inboxGameSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                inboxGameId = if (pos > 0) games[pos - 1].id else ""
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        if (!hasVideoPerm()) { inboxGate.visibility = View.VISIBLE; inboxList.adapter = null; return }
        inboxGate.visibility = View.GONE
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { queryRecordings() }
            val done = Store.doneInbox()
            inboxList.adapter = InboxAdapter(list, done)
        }
    }

    private fun queryRecordings(): List<Recording> {
        val out = mutableListOf<Recording>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED
        )
        runCatching {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                when (Store.inboxFilter) {
                    "Screen" -> "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                    "Camera" -> "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                    else -> "${MediaStore.Video.Media.RELATIVE_PATH} NOT LIKE ?"
                },
                when (Store.inboxFilter) {
                    "Screen" -> arrayOf("%Screen recordings%")
                    "Camera" -> arrayOf("DCIM/Camera%")
                    else -> arrayOf("%Movies/cliprig%")
                },
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 40) {
                    val id = c.getLong(0)
                    out.add(
                        Recording(
                            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                            c.getString(1) ?: "", c.getLong(2), c.getLong(3) * 1000
                        )
                    )
                }
            }
        }
        return out
    }

    private fun loadThumb(uri: Uri, into: ImageView) {
        val key = uri.toString()
        into.tag = key
        thumbs.get(key)?.let { into.setImageBitmap(it); return }
        into.setImageDrawable(null)
        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) { runCatching { contentResolver.loadThumbnail(uri, Size(270, 480), null) }.getOrNull() }
            if (b != null) { thumbs.put(key, b); if (into.tag == key) into.setImageBitmap(b) }
        }
    }

    private fun fmtDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    inner class InboxAdapter(private val items: List<Recording>, private val done: Set<String>) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = if (items.isEmpty()) 1 else items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.WHITE, dp(14).toFloat())
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = RecyclerView.LayoutParams(-1, dp(112)).apply { bottomMargin = dp(10) }
            }
            return object : RecyclerView.ViewHolder(row) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            val row = h.itemView as LinearLayout
            row.removeAllViews()
            if (items.isEmpty()) {
                row.addView(label("No videos found here yet. Record with the Samsung screen recorder or the camera, or tap Pick a video.", 15f, MUTED))
                return
            }
            val r = items[pos]
            val img = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(BLUE_SOFT, dp(8).toFloat())
                clipToOutline = true
            }
            row.addView(img, LinearLayout.LayoutParams(dp(52), dp(92)))
            loadThumb(r.uri, img)
            val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            info.addView(label(DateFormat.format("EEE MMM d, h:mm a", r.added).toString(), 15f, INK, true))
            info.addView(label(fmtDuration(r.durationMs) + if (done.contains(r.uri.toString())) "   clipped" else "", 13f, MUTED))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(blueButton("Clip it") {
                Pipeline.fromUri(this@MainActivity, r.uri, inboxGameId, false)
                show("library")
            }.apply { textSize = 14f }, LinearLayout.LayoutParams(dp(96), dp(48)))
        }
    }

    // Library

    sealed class LibItem {
        data class Header(val name: String) : LibItem()
        data class Clip(val c: ClipRecord) : LibItem()
    }

    private fun buildLibrary(): View {
        val c = column(16)
        libraryFilter = picker(listOf("All games"), 0) {}
        c.add(libraryFilter)
        val frame = FrameLayout(this)
        libraryList = RecyclerView(this).apply { clipToPadding = false; setPadding(0, 0, 0, dp(130)) }
        frame.addView(libraryList, FrameLayout.LayoutParams(-1, -1))
        libraryEmpty = label("No clips yet. Play a game from Launch or clip a recording from the Inbox.", 15f, MUTED).apply {
            visibility = View.GONE
        }
        frame.addView(libraryEmpty, FrameLayout.LayoutParams(-1, -2))
        c.add(frame, 12, 0)
        (frame.layoutParams as LinearLayout.LayoutParams).weight = 1f
        return c
    }

    private fun refreshLibrary() {
        if (!::libraryList.isInitialized) return
        val all = Store.clips()
        val names = listOf("All games") + all.map { it.gameName }.distinct().sorted()
        libraryFilter.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        libraryFilter.setSelection(names.indexOf(libraryFilterName).coerceAtLeast(0), false)
        libraryFilter.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (names[pos] != libraryFilterName) { libraryFilterName = names[pos]; refreshLibrary() }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        val shown = all.filter { libraryFilterName == "All games" || it.gameName == libraryFilterName }
        val items = mutableListOf<LibItem>()
        shown.groupBy { it.gameName }.toSortedMap().forEach { (g, list) ->
            items.add(LibItem.Header(g)); list.sortedByDescending { it.createdAt }.forEach { items.add(LibItem.Clip(it)) }
        }
        libraryEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val glm = GridLayoutManager(this, 3)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (items[position] is LibItem.Header) 3 else 1
        }
        libraryList.layoutManager = glm
        libraryList.adapter = LibraryAdapter(items)
    }

    inner class LibraryAdapter(private val items: List<LibItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position] is LibItem.Header) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v: View = if (viewType == 0) label("", 16f, BLUE_DARK, true).apply {
                setPadding(dp(4), dp(14), dp(4), dp(6))
                layoutParams = RecyclerView.LayoutParams(-1, -2)
            } else ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(BLUE_SOFT, dp(10).toFloat())
                clipToOutline = true
                val w = (parent.width - dp(24)) / 3
                layoutParams = RecyclerView.LayoutParams(-1, (w * 16 / 9).coerceAtLeast(dp(150))).apply {
                    setMargins(dp(4), dp(4), dp(4), dp(4))
                }
            }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
            when (val it = items[pos]) {
                is LibItem.Header -> (h.itemView as TextView).text = it.name
                is LibItem.Clip -> {
                    val img = h.itemView as ImageView
                    loadThumb(Uri.parse(it.c.uri), img)
                    img.setOnClickListener { _ -> openPlayer(it.c) }
                }
            }
        }
    }

    private fun openOverlay(v: View) {
        overlayHost.removeAllViews()
        overlayHost.addView(v, FrameLayout.LayoutParams(-1, -1))
        overlayHost.visibility = View.VISIBLE
    }

    private fun closeOverlay() {
        overlayHost.removeAllViews()
        overlayHost.visibility = View.GONE
    }

    private fun openPlayer(clip: ClipRecord) {
        val root = FrameLayout(this).apply { setBackgroundColor(0xF20A1020.toInt()) }
        val video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1).apply {
            gravity = Gravity.CENTER; bottomMargin = dp(150); topMargin = dp(16)
        })
        video.setVideoURI(Uri.parse(clip.uri))
        video.setOnPreparedListener { it.isLooping = true; video.start() }
        video.setOnErrorListener { _, _, _ -> toast("That clip file is gone"); true }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        bar.addView(label(clip.caption, 14f, 0xFFD6E4FF.toInt()).apply { maxLines = 1 })
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun put(row: LinearLayout, v: View) = row.addView(v, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        put(r1, blueButton("Share") { share(Uri.parse(clip.uri)) })
        put(r1, blueButton("New caption") { Pipeline.regenerate(this, clip); closeOverlay() })
        put(r1, blueButton("Restyle") { Pipeline.restyle(this, clip); closeOverlay() })
        put(r2, blueButton("New music", false) { Pipeline.newMusic(this, clip); closeOverlay() })
        put(r2, blueButton("Delete", false) {
            AlertDialog.Builder(this).setMessage("Delete this clip?")
                .setPositiveButton("Delete") { _, _ ->
                    runCatching { contentResolver.delete(Uri.parse(clip.uri), null, null) }
                    Store.removeClip(clip.id); closeOverlay(); refreshLibrary()
                }.setNegativeButton("Keep", null).show()
        })
        put(r2, blueButton("Close", false) { closeOverlay() })
        bar.addView(r1); bar.addView(r2)
        root.addView(bar, FrameLayout.LayoutParams(-1, dp(150)).apply { gravity = Gravity.BOTTOM })
        openOverlay(root)
    }

    private fun share(uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share clip"))
    }

    // Themes

    private fun buildThemes(): View {
        val c = column(12)
        preview = ThemePreviewView(this).apply { background = rounded(0xFF0A1020.toInt(), dp(16).toFloat()) }
        c.add(preview, 0, dp(330))
        val opts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val introPick = picker(listOf("Intro on", "Intro off"), if (Store.introOn) 0 else 1) {
            Store.introOn = it == 0; preview.intro = Store.introOn; preview.theme = preview.theme
        }
        val modePick = picker(listOf("Preview rapid fire", "Preview highlight"), 0) {
            preview.rapid = it == 0; preview.theme = preview.theme
        }
        opts.addView(introPick, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
        opts.addView(modePick, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        c.add(opts, 10)
        val scroll = ScrollView(this)
        themeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(130)) }
        scroll.addView(themeList)
        scroll.clipToPadding = false
        c.add(scroll, 10, 0)
        (scroll.layoutParams as LinearLayout.LayoutParams).weight = 1f
        return c
    }

    private fun refreshThemes() {
        preview.intro = Store.introOn
        val g = Store.game(Store.lastGameId)
        preview.title = g?.name?.ifBlank { null } ?: "Your Game"
        val link = g?.promoUrl?.ifBlank { null } ?: Store.defaultPromo.ifBlank { null } ?: g?.url ?: ""
        preview.endText = g?.endText?.ifBlank { null } ?: ("play it at\n" + (link.removePrefix("https://").removePrefix("http://").trimEnd('/').ifBlank { "yourgame.site" }))
        preview.tagline = g?.tagline?.ifBlank { null } ?: Store.defaultTagline
        preview.popups = g?.popups?.takeIf { it.isNotEmpty() } ?: Store.defaultPopups
        preview.caption = (g?.captions?.firstOrNull() ?: Store.defaultCaptions.firstOrNull() ?: "this run was unreal")
        preview.theme = if (Store.theme == "random") ComicTheme else ThemeBook.byId(Store.theme)
        themeList.removeAllViews()
        val entries = ThemeBook.all.map { Triple(it.id, it.label, it.blurb) } +
            Triple("random", "Surprise Me", "A different theme on every clip")
        for ((id, name, blurb) in entries) {
            val selected = Store.theme == id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(if (selected) BLUE_SOFT else Color.WHITE, dp(14).toFloat(), dp(2), if (selected) BLUE else 0xFFDCE4F2.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener {
                    Store.theme = id
                    refreshThemes()
                }
            }
            row.addView(label((if (selected) "\u25C9  " else "\u25CB  ") + name, 17f, if (selected) BLUE_DARK else INK, true))
            row.addView(label(blurb, 13f, MUTED))
            themeList.addView(row, LinearLayout.LayoutParams(-1, dp(78)).apply { bottomMargin = dp(8) })
        }
    }

    // Music

    private fun buildMusic(): View {
        val c = column(16)
        c.add(label("Music on clips", 13f, MUTED, true))
        val modes = listOf("Queue", "Random", "Off")
        musicModePick = picker(listOf("Play my queue in order", "Random from folder", "No music"), modes.indexOf(Store.musicMode).coerceAtLeast(0)) {
            Store.musicMode = modes[it]
        }
        c.add(musicModePick, 6)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val starts = listOf(0, 15, 30, 45, 60)
        row.addView(picker(listOf("Songs start at the top", "Start 15 seconds in", "Start 30 seconds in", "Start 45 seconds in", "Start a minute in"),
            starts.indexOf(Store.musicStartSec).coerceAtLeast(0)) { Store.musicStartSec = starts[it] },
            LinearLayout.LayoutParams(0, dp(48), 1.4f).apply { rightMargin = dp(6) })
        row.addView(blueButton("Pick folder", false) { folderLauncher.launch(null) }.apply { textSize = 14f; setPadding(dp(6), 0, dp(6), 0) },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        c.add(row, 10)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        musicInfo = label("", 13f, MUTED).apply { maxLines = 2 }
        row2.addView(musicInfo, LinearLayout.LayoutParams(0, dp(48), 1.4f).apply { rightMargin = dp(6) })
        row2.addView(blueButton("Clear queue", false) {
            Store.musicQueue = emptyList(); Store.queueIndex = 0; refreshMusic()
        }.apply { textSize = 14f; setPadding(dp(6), 0, dp(6), 0) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
        c.add(row2, 10)
        val scroll = ScrollView(this).apply { clipToPadding = false }
        musicList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(130)) }
        scroll.addView(musicList)
        c.add(scroll, 10, 0)
        (scroll.layoutParams as LinearLayout.LayoutParams).weight = 1f
        return c
    }

    private fun refreshMusic() {
        musicModePick.setSelection(listOf("Queue", "Random", "Off").indexOf(Store.musicMode).coerceAtLeast(0), false)
        musicList.removeAllViews()
        lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) { ClipEditor.musicList(this@MainActivity) }
            val queue = Store.musicQueue
            musicInfo.text = if (Store.musicFolder.isEmpty()) "Pick a folder with your songs first."
            else "Tap a song to add it to the queue, tap again to take it out. Next up is spot ${if (queue.isEmpty()) "one" else (Store.queueIndex % queue.size + 1).toString()}."
            musicList.removeAllViews()
            if (tracks.isEmpty()) {
                musicList.addView(label(if (Store.musicFolder.isEmpty()) "No music folder picked yet." else "No songs found in that folder.", 15f, MUTED))
                return@launch
            }
            for ((uri, name) in tracks) {
                val pos = queue.indexOf(uri.toString())
                val r = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(if (pos >= 0) BLUE_SOFT else Color.WHITE, dp(14).toFloat(), dp(2), if (pos >= 0) BLUE else 0xFFDCE4F2.toInt())
                    setPadding(dp(10), dp(6), dp(8), dp(6))
                }
                val badge = label(if (pos >= 0) (pos + 1).toString() else "+", 16f, if (pos >= 0) Color.WHITE else BLUE, true).apply {
                    gravity = Gravity.CENTER
                    background = rounded(if (pos >= 0) BLUE else BLUE_SOFT, dp(18).toFloat())
                }
                r.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)))
                r.addView(label(name, 15f, INK, true).apply { maxLines = 2; setPadding(dp(12), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                val playing = playingUri == uri.toString()
                r.addView(blueButton(if (playing) "\u25A0" else "\u25B6", !playing) { togglePreview(uri) }.apply {
                    textSize = 16f; setPadding(0, 0, 0, 0)
                }, LinearLayout.LayoutParams(dp(56), dp(44)))
                r.setOnClickListener {
                    val q = Store.musicQueue.toMutableList()
                    if (!q.remove(uri.toString())) q.add(uri.toString())
                    Store.musicQueue = q
                    if (Store.musicMode != "Off" && q.isNotEmpty()) Store.musicMode = "Queue"
                    refreshMusic()
                }
                musicList.addView(r, LinearLayout.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(8) })
            }
        }
    }

    private fun togglePreview(uri: Uri) {
        val was = playingUri
        stopPreview()
        if (was != uri.toString()) {
            runCatching {
                player = android.media.MediaPlayer().apply {
                    setDataSource(this@MainActivity, uri)
                    setOnPreparedListener { it.seekTo(Store.musicStartSec * 1000); it.start() }
                    setOnCompletionListener { stopPreview(); if (current == "music") refreshMusic() }
                    prepareAsync()
                }
                playingUri = uri.toString()
            }
        }
        if (current == "music") refreshMusic()
    }

    private fun stopPreview() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingUri = ""
    }

    override fun onPause() {
        super.onPause()
        if (playingUri.isNotEmpty()) { stopPreview(); if (current == "music") refreshMusic() }
    }

    // Games

    private fun buildGames(): View {
        val c = column(16)
        c.add(blueButton("Add a game") { editGame(GameProfile()) }, 0, dp(56))
        gamesList = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity); clipToPadding = false; setPadding(0, 0, 0, dp(130)) }
        c.add(gamesList, 12, 0)
        (gamesList.layoutParams as LinearLayout.LayoutParams).weight = 1f
        return c
    }

    private fun refreshGames() {
        val list = Store.games()
        gamesList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = if (list.isEmpty()) 1 else list.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(Color.WHITE, dp(14).toFloat())
                    setPadding(dp(14), dp(10), dp(10), dp(10))
                    layoutParams = RecyclerView.LayoutParams(-1, dp(84)).apply { bottomMargin = dp(10) }
                }
                return object : RecyclerView.ViewHolder(row) {}
            }
            override fun onBindViewHolder(h: RecyclerView.ViewHolder, pos: Int) {
                val row = h.itemView as LinearLayout
                row.removeAllViews()
                if (list.isEmpty()) {
                    row.addView(label("No games saved yet. Add your games so clips get the right title, captions and end card.", 15f, MUTED))
                    return
                }
                val g = list[pos]
                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(label(g.name.ifBlank { "Untitled" }, 16f, INK, true).apply { maxLines = 1 })
                info.addView(label(g.url.removePrefix("https://"), 13f, MUTED).apply { maxLines = 1 })
                info.setOnClickListener { editGame(g) }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(blueButton("Play") { startRun(g) }.apply { textSize = 14f }, LinearLayout.LayoutParams(dp(84), dp(48)))
            }
        }
    }

    private fun editGame(g: GameProfile) {
        val scroll = ScrollView(this).apply { setBackgroundColor(PAPER) }
        val c = column(20)
        c.add(label(if (g.name.isBlank()) "New game" else "Edit game", 22f, INK, true))
        c.add(label("Name", 13f, MUTED, true), 16)
        val name = field("game name", g.name); c.add(name, 6)
        c.add(label("Address", 13f, MUTED, true), 14)
        val url = field("game address", g.url); c.add(url, 6)
        c.add(label("Captions, one per line", 13f, MUTED, true), 14)
        val caps = field("caption lines", g.captions.joinToString("\n"), true); c.add(caps, 6)
        c.add(label("Link to advertise, like your itch page. Blank uses the default link from Settings, then the play address", 13f, MUTED, true), 14)
        val promo = field("link to advertise", g.promoUrl); c.add(promo, 6)
        c.add(label("Intro line, shown while the intro plays", 13f, MUTED, true), 14)
        val tag = field("intro line", g.tagline).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        c.add(tag, 6)
        c.add(label("Pop up lines, one per line, dropped into the video", 13f, MUTED, true), 14)
        val pops = field("pop up lines", g.popups.joinToString("\n"), true); c.add(pops, 6)
        c.add(label("End card text, blank uses the advertised link", 13f, MUTED, true), 14)
        val end = field("end card text", g.endText); c.add(end, 6)
        val hook = CheckBox(this).apply { text = "Event hook installed in this game"; isChecked = g.hook; setTextColor(INK) }
        c.add(hook, 14)
        c.add(label("Games can call cliprig.mark('highscore') from their code when something big happens.", 13f, MUTED), 4)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun put(v: View) = row.addView(v, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        put(blueButton("Save") {
            g.name = name.text.toString().trim()
            g.url = normalizeUrl(url.text.toString())
            g.captions = caps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            g.endText = end.text.toString().trim()
            g.promoUrl = promo.text.toString().trim()
            g.tagline = tag.text.toString().trim()
            g.popups = pops.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            g.hook = hook.isChecked
            Store.upsertGame(g); closeOverlay(); refreshGames(); refreshLaunchGames()
        })
        put(blueButton("Delete", false) {
            Store.deleteGame(g.id); closeOverlay(); refreshGames(); refreshLaunchGames()
        })
        put(blueButton("Close", false) { closeOverlay() })
        c.add(row, 20)
        scroll.addView(c)
        openOverlay(scroll)
    }

    private fun editDefaultCaptions() {
        drawer.closeDrawer(GravityCompat.START)
        val scroll = ScrollView(this).apply { setBackgroundColor(PAPER) }
        val c = column(20)
        c.add(label("Default text", 22f, INK, true))
        c.add(label("Used for any game that leaves these blank.", 13f, MUTED), 6)
        c.add(label("Link to advertise", 13f, MUTED, true), 14)
        val promo = field("link to advertise", Store.defaultPromo); c.add(promo, 6)
        c.add(label("Intro line", 13f, MUTED, true), 14)
        val tag = field("intro line", Store.defaultTagline).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        c.add(tag, 6)
        c.add(label("Captions, one per line", 13f, MUTED, true), 14)
        val f = field("caption lines", Store.defaultCaptions.joinToString("\n"), true); c.add(f, 6)
        c.add(label("Pop up lines, one per line", 13f, MUTED, true), 14)
        val pops = field("pop up lines", Store.defaultPopups.joinToString("\n"), true); c.add(pops, 6)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(blueButton("Save") {
            Store.defaultPromo = promo.text.toString().trim()
            Store.defaultTagline = tag.text.toString().trim()
            Store.defaultCaptions = f.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            Store.defaultPopups = pops.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
            closeOverlay()
            if (current == "themes") refreshThemes()
        }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        row.addView(blueButton("Close", false) { closeOverlay() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
        c.add(row, 20)
        scroll.addView(c)
        openOverlay(scroll)
    }

    private fun chooseGame(title: String, onPick: (String) -> Unit) {
        val games = Store.games()
        val names = (listOf("No game") + games.map { it.name.ifBlank { it.url } }).toTypedArray()
        AlertDialog.Builder(this).setTitle(title)
            .setItems(names) { _, which -> onPick(if (which == 0) "" else games[which - 1].id) }
            .show()
    }

    // Status and updates

    private fun showStatus(s: Pipeline.Status) {
        if (s.running) {
            progressCard.removeCallbacks(hideProgress)
            progressCard.visibility = View.VISIBLE
            progressText.text = s.text
            progressBar.progress = (s.progress * 1000).toInt()
        } else if (progressCard.visibility == View.VISIBLE) {
            progressBar.progress = 1000
            progressCard.postDelayed(hideProgress, 3500)
        }
    }

    private val hideProgress = Runnable { progressCard.visibility = View.GONE }

    private fun checkUpdate(userAsked: Boolean) {
        lifecycleScope.launch {
            val r = Updater.latest()
            if (r == null) { if (userAsked) toast("Could not reach GitHub for updates"); return@launch }
            if (r.version <= BuildConfig.VERSION_CODE) {
                updateItem.text = "Up to date (${BuildConfig.VERSION_NAME})"
                if (userAsked) toast("cliprig is up to date")
                return@launch
            }
            pendingRelease = r
            updateItem.text = "Install update v${r.version}"
            updateItem.setOnClickListener { installUpdate() }
            if (userAsked) installUpdate() else toast("A cliprig update is ready in the menu")
        }
    }

    private fun installUpdate() {
        val r = pendingRelease ?: return checkUpdate(true)
        if (!Updater.canInstall(this)) { Updater.openInstallPermission(this); return }
        drawer.closeDrawer(GravityCompat.START)
        showStatus(Pipeline.Status(true, "Downloading update v${r.version}", 0f))
        lifecycleScope.launch {
            val ok = Updater.downloadAndInstall(this@MainActivity, r) { p ->
                runOnUiThread { showStatus(Pipeline.Status(true, "Downloading update v${r.version}", p)) }
            }
            showStatus(Pipeline.Status(false, "", 1f))
            if (!ok) toast("Update download failed, try again")
        }
    }
}

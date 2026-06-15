package com.indotv.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    data class Channel(val name: String, val group: String, val url: String)

    private lateinit var prefs: SharedPreferences
    private var mediaPlayer: MediaPlayer? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var surfaceView: SurfaceView? = null

    private var allChannels = mutableListOf<Channel>()
    private var filteredChannels = mutableListOf<Channel>()
    private var currentChannel: Channel? = null
    private var playerPrepared = false

    private lateinit var sidebar: LinearLayout
    private lateinit var channelList: ListView
    private lateinit var statusView: TextView
    private lateinit var channelNameOverlay: TextView
    private lateinit var searchInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var loadUrlBtn: Button
    private lateinit var progressBar: ProgressBar

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var sidebarVisible = true

    private val hideOverlayRunnable = Runnable {
        channelNameOverlay.visibility = View.GONE
    }
    private val hideSidebarRunnable = Runnable {
        hideSidebar()
    }

    private val DEFAULT_URL = "https://iptv-org.github.io/iptv/countries/id.m3u"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("indotv_prefs", Context.MODE_PRIVATE)
        buildUi()

        val savedUrl = prefs.getString("m3u_url", DEFAULT_URL) ?: DEFAULT_URL
        urlInput.setText(savedUrl)
        loadPlaylist(savedUrl)
    }

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // SurfaceView for video
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        surfaceView!!.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceHolder = holder
                currentChannel?.let { playChannel(it) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceHolder = null
            }
        })
        root.addView(surfaceView)

        // Channel name overlay (auto-hide after 3s)
        channelNameOverlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(Color.parseColor("#88000000"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(16)
            }
        }
        root.addView(channelNameOverlay)

        // Progress bar center
        progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.CENTER
            )
            visibility = View.GONE
        }
        root.addView(progressBar)

        // Status text center
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = dp(60)
            }
        }
        root.addView(statusView)

        // Sidebar
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD1A1A2E"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                dp(300),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.START
            )
        }

        // App title
        val titleView = TextView(this).apply {
            text = "📺 INDOTV"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        }
        sidebar.addView(titleView)

        // URL input field
        urlInput = EditText(this).apply {
            hint = "URL Playlist M3U"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(4)
            }
        }
        sidebar.addView(urlInput)

        // Load URL button
        loadUrlBtn = Button(this).apply {
            text = "⟳ Muat Playlist"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC3333"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36)
            ).apply {
                bottomMargin = dp(8)
            }
        }
        loadUrlBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                prefs.edit().putString("m3u_url", url).apply()
                loadPlaylist(url)
            }
        }
        sidebar.addView(loadUrlBtn)

        // Search input
        searchInput = EditText(this).apply {
            hint = "🔍 Cari channel..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        sidebar.addView(searchInput)

        // Channel list
        channelList = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            divider = null
            dividerHeight = 0
        }
        channelList.setOnItemClickListener { _, _, position, _ ->
            if (position < filteredChannels.size) {
                playChannel(filteredChannels[position])
                scheduleSidebarHide()
            }
        }
        sidebar.addView(channelList)

        root.addView(sidebar)

        // Touch on video area toggles sidebar
        root.setOnClickListener {
            toggleSidebar()
        }

        setContentView(root)
    }

    private fun applyFilter(query: String) {
        filteredChannels.clear()
        if (query.isEmpty()) {
            filteredChannels.addAll(allChannels)
        } else {
            val q = query.lowercase()
            filteredChannels.addAll(allChannels.filter {
                it.name.lowercase().contains(q) || it.group.lowercase().contains(q)
            })
        }
        updateChannelAdapter()
    }

    private fun updateChannelAdapter() {
        val adapter = object : BaseAdapter() {
            override fun getCount() = filteredChannels.size
            override fun getItem(pos: Int) = filteredChannels[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                val ch = filteredChannels[pos]
                val isPlaying = currentChannel?.url == ch.url
                val tv = (convertView as? TextView) ?: TextView(this@MainActivity).apply {
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    textSize = 13f
                }
                tv.text = "${ch.name}  •  ${ch.group}"
                tv.setTextColor(if (isPlaying) Color.parseColor("#FF5555") else Color.WHITE)
                tv.setBackgroundColor(
                    if (isPlaying) Color.parseColor("#33FF5555") else Color.TRANSPARENT
                )
                return tv
            }
        }
        channelList.adapter = adapter
    }

    private fun showChannelOverlay(name: String) {
        mainHandler.removeCallbacks(hideOverlayRunnable)
        channelNameOverlay.text = name
        channelNameOverlay.visibility = View.VISIBLE
        mainHandler.postDelayed(hideOverlayRunnable, 3000)
    }

    private fun toggleSidebar() {
        if (sidebarVisible) hideSidebar() else showSidebar()
    }

    private fun showSidebar() {
        sidebar.visibility = View.VISIBLE
        sidebarVisible = true
        mainHandler.removeCallbacks(hideSidebarRunnable)
    }

    private fun hideSidebar() {
        sidebar.visibility = View.GONE
        sidebarVisible = false
    }

    private fun scheduleSidebarHide() {
        mainHandler.removeCallbacks(hideSidebarRunnable)
        mainHandler.postDelayed(hideSidebarRunnable, 5000)
    }

    private fun setLoading(loading: Boolean, text: String = "") {
        mainHandler.post {
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            statusView.text = text
            statusView.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun loadPlaylist(urlStr: String) {
        setLoading(true, "Memuat playlist...")
        loadUrlBtn.isEnabled = false
        executor.execute {
            try {
                val content = downloadPlaylist(urlStr)
                val channels = parseM3u(content)
                mainHandler.post {
                    allChannels.clear()
                    allChannels.addAll(channels)
                    filteredChannels.clear()
                    filteredChannels.addAll(channels)
                    updateChannelAdapter()
                    loadUrlBtn.isEnabled = true
                    if (channels.isNotEmpty()) {
                        setLoading(false, "${channels.size} channel dimuat")
                        mainHandler.postDelayed({ statusView.visibility = View.GONE }, 2000)
                    } else {
                        setLoading(false, "Tidak ada channel ditemukan")
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    loadUrlBtn.isEnabled = true
                    setLoading(false, "Gagal: ${e.message}")
                }
            }
        }
    }

    private fun downloadPlaylist(urlStr: String): String {
        var lastUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = URL(lastUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "INDOTV/1.0")
            val code = conn.responseCode
            if (code in 300..399) {
                lastUrl = conn.getHeaderField("Location") ?: break
                redirects++
                conn.disconnect()
                continue
            }
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            reader.forEachLine { sb.appendLine(it) }
            reader.close()
            conn.disconnect()
            return sb.toString()
        }
        throw Exception("Terlalu banyak redirect")
    }

    private fun parseM3u(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:")) {
                val name = line.substringAfterLast(",").trim()
                val groupMatch = Regex("""group-title="([^"]*?)"""").find(line)
                val group = groupMatch?.groupValues?.get(1) ?: ""
                // next non-comment line is URL
                var j = i + 1
                while (j < lines.size && lines[j].trim().startsWith("#")) j++
                if (j < lines.size) {
                    val url = lines[j].trim()
                    if (url.startsWith("http")) {
                        channels.add(Channel(name, group, url))
                    }
                }
            }
            i++
        }
        return channels
    }

    private fun playChannel(channel: Channel) {
        currentChannel = channel
        updateChannelAdapter()
        showChannelOverlay(channel.name)
        setLoading(true, "Memuat: ${channel.name}")

        releasePlayer()

        val holder = surfaceHolder ?: return

        executor.execute {
            try {
                val mp = MediaPlayer()
                mp.setDisplay(holder)
                mp.setDataSource(this, Uri.parse(channel.url))
                mp.setOnPreparedListener {
                    playerPrepared = true
                    it.start()
                    setLoading(false)
                }
                mp.setOnErrorListener { _, what, extra ->
                    setLoading(false, "Error ($what/$extra)")
                    true
                }
                mp.setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> setLoading(true, "Buffering...")
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> setLoading(false)
                    }
                    false
                }
                mp.prepareAsync()
                mediaPlayer = mp
            } catch (e: Exception) {
                setLoading(false, "Gagal: ${e.message}")
            }
        }
    }

    private fun releasePlayer() {
        playerPrepared = false
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> {
                showSidebar()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (sidebarVisible) {
                    hideSidebar()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                playOffset(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                playOffset(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (!sidebarVisible) {
                    toggleSidebar()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun playOffset(offset: Int) {
        if (filteredChannels.isEmpty()) return
        val curIdx = filteredChannels.indexOfFirst { it.url == currentChannel?.url }
        val newIdx = if (curIdx < 0) 0
        else (curIdx + offset + filteredChannels.size) % filteredChannels.size
        playChannel(filteredChannels[newIdx])
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onResume() {
        super.onResume()
        currentChannel?.let {
            if (surfaceHolder != null) playChannel(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        executor.shutdownNow()
    }
}

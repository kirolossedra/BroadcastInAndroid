package com.example.myapplication

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.text.InputType
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.view.setPadding
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : Activity() {
    private var receiverJob: Job? = null
    private val TAG = "UDPReceiver"
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI elements
    private lateinit var statusBar: TextView
    private lateinit var messagesView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var portInput: EditText
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var modeLayout: LinearLayout
    private lateinit var unicastBtn: Button
    private lateinit var broadcastBtn: Button
    private lateinit var layout: LinearLayout

    // Color palette (Material-like, eye-friendly)
    private val bgColor = Color.parseColor("#F6FAFF")
    private val accentColor = Color.parseColor("#2196F3")      // blue
    private val accentDark = Color.parseColor("#1565C0")
    private val errorColor = Color.parseColor("#E53935")        // red
    private val textColor = Color.parseColor("#232D36")         // main text
    private val msgLogBg = Color.parseColor("#E3F2FD")
    private val btnTextColor = Color.WHITE
    private val btnRippleColor = Color.parseColor("#E3F2FD")
    private val titleColor = Color.parseColor("#1565C0")
    private val dividerColor = Color.parseColor("#BDBDBD")
    private val modeUnicast = Color.parseColor("#42A5F5")
    private val modeBroadcast = Color.parseColor("#FFB300")

    enum class ListenMode { UNICAST, BROADCAST }
    private var listenMode: ListenMode = ListenMode.UNICAST

    // throughput state
    private var thrustBytes = 0L
    private var thrustPackets = 0
    private var thrustStart: Long = 0L
    private var throughputPending = false
    private var thrustTimer: Runnable? = null

    @Volatile private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = accentColor
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(0, dp(34), 0, 0)
        }

        // Title
        val title = TextView(this).apply {
            text = "UDP Message Receiver"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(titleColor)
            setShadowLayer(2f, 0f, 2f, accentDark)
            setPadding(0, dp(0), 0, dp(10))
        }
        layout.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Mode selector (row)
        modeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(0), dp(18), dp(4))
        }
        unicastBtn = modeButton("Unicast", modeUnicast, true)
        broadcastBtn = modeButton("Broadcast", modeBroadcast, false)
        modeLayout.addView(unicastBtn)
        modeLayout.addView(broadcastBtn)
        layout.addView(modeLayout)

        // Port input (single row, centered)
        val portRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(4), dp(18), dp(10))
        }
        val portLabel = TextView(this).apply {
            text = "Port:"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accentDark)
            setPadding(0, 0, dp(8), 0)
        }
        portInput = EditText(this).apply {
            hint = "5005"
            setText("5005")
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(textColor)
            setHintTextColor(dividerColor)
            setBackgroundColor(Color.WHITE)
            background = null
            textSize = 16f
            isSingleLine = true
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        portRow.addView(portLabel)
        portRow.addView(portInput)
        layout.addView(portRow)

        // Start/Stop/Clear row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(2), dp(18), dp(8))
        }
        startBtn = fancyButton("Start", accentColor)
        stopBtn = fancyButton("Stop", errorColor)
        clearBtn = fancyButton("Clear", dividerColor)
        stopBtn.isEnabled = false
        clearBtn.isEnabled = true
        btnRow.addView(startBtn)
        btnRow.addView(stopBtn)
        btnRow.addView(clearBtn)
        layout.addView(btnRow)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(dividerColor)
        }
        layout.addView(divider, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ))

        // Status bar
        statusBar = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(18), dp(16), dp(18), dp(6))
            setTextColor(accentColor)
        }
        layout.addView(statusBar)

        // Message log inside ScrollView
        scrollView = ScrollView(this).apply {
            setBackgroundColor(msgLogBg)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        messagesView = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
            setTypeface(Typeface.MONOSPACE)
            setTextColor(textColor)
            setPadding(0, dp(8), 0, dp(24))
        }
        scrollView.addView(messagesView)
        layout.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(layout)

        showDeviceIps()
        setStatus("Ready. Select mode, enter a port, and press Start.", accentColor)

        // Mode button listeners
        unicastBtn.setOnClickListener { selectMode(ListenMode.UNICAST) }
        broadcastBtn.setOnClickListener { selectMode(ListenMode.BROADCAST) }

        startBtn.setOnClickListener {
            if (listening) {
                toast("Already listening.")
                return@setOnClickListener
            }
            val port = portInput.text.toString().toIntOrNull()
            if (port == null || port !in 1..65535) {
                setStatus("Invalid port.", errorColor)
                shakeView(portInput)
                return@setOnClickListener
            }
            startUdpReceiver(port, listenMode)
            startBtn.isEnabled = false
            portInput.isEnabled = false
            stopBtn.isEnabled = true
            clearBtn.isEnabled = true
            unicastBtn.isEnabled = false
            broadcastBtn.isEnabled = false
            animateButtonActive(startBtn, false)
            animateButtonActive(stopBtn, true)
        }
        stopBtn.setOnClickListener {
            stopUdpReceiver()
            setStatus("Stopped.", accentDark)
            startBtn.isEnabled = true
            portInput.isEnabled = true
            stopBtn.isEnabled = false
            clearBtn.isEnabled = true
            unicastBtn.isEnabled = true
            broadcastBtn.isEnabled = true
            animateButtonActive(startBtn, true)
            animateButtonActive(stopBtn, false)
        }
        clearBtn.setOnClickListener {
            if (listening) {
                setStatus("Cannot clear while listening! Stop first.", errorColor)
                shakeView(clearBtn)
            } else {
                messagesView.text = ""
                setStatus("Cleared.", accentDark)
            }
        }
    }

    private fun selectMode(mode: ListenMode) {
        listenMode = mode
        if (mode == ListenMode.UNICAST) {
            setModeButtonActive(unicastBtn, modeUnicast, true)
            setModeButtonActive(broadcastBtn, modeBroadcast, false)
        } else {
            setModeButtonActive(unicastBtn, modeUnicast, false)
            setModeButtonActive(broadcastBtn, modeBroadcast, true)
        }
        setStatus("Mode: ${if (mode == ListenMode.UNICAST) "Unicast" else "Broadcast"}", accentColor)
    }

    private fun setModeButtonActive(btn: Button, color: Int, active: Boolean) {
        val bg = btn.background as GradientDrawable
        if (active) {
            bg.setColor(color)
            btn.setTextColor(Color.WHITE)
            btn.elevation = dp(8).toFloat()
        } else {
            bg.setColor(dividerColor)
            btn.setTextColor(textColor)
            btn.elevation = dp(2).toFloat()
        }
    }

    private fun showDeviceIps() {
        appendMessage("Device IPs:", accentColor)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                        appendMessage(" - ${addr.hostAddress} (iface: ${intf.displayName})", accentDark)
                    }
                }
            }
        } catch (e: Exception) {
            appendMessage("IP fetch error: ${e.message}", errorColor)
        }
    }

    private fun appendMessage(msg: String, color: Int = textColor, animate: Boolean = true) {
        mainHandler.post {
            val oldLen = messagesView.text.length
            messagesView.append("\n$msg")
            messagesView.setTextColor(textColor)
            if (animate && oldLen < messagesView.text.length) {
                val startY = dp(30).toFloat()
                messagesView.translationY = startY
                messagesView.alpha = 0.3f
                messagesView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(320)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun appendThroughputMsg(mbps: Double, pkts: Int, bytes: Long, ms: Long) {
        val color = accentColor
        val tputMsg = "THROUGHPUT: %.2f Mbps (%d pkts, %.1f KB, burst %.1f s)".format(
            mbps, pkts, bytes / 1024.0, ms / 1000.0
        )
        mainHandler.post {
            val span = android.text.SpannableString("\n$tputMsg")
            span.setSpan(
                android.text.style.ForegroundColorSpan(color),
                0, span.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            messagesView.append(span)
            messagesView.append("\n")
            messagesView.setTextColor(textColor)
            messagesView.animate().alpha(0.5f).setDuration(60).withEndAction {
                messagesView.animate().alpha(1f).setDuration(260).start()
            }.start()
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setStatus(msg: String, color: Int = accentColor) {
        mainHandler.post {
            val animator = ValueAnimator.ofObject(
                ArgbEvaluator(),
                statusBar.currentTextColor, color
            )
            animator.duration = 300
            animator.addUpdateListener { valueAnimator ->
                statusBar.setTextColor(valueAnimator.animatedValue as Int)
            }
            animator.start()
            statusBar.text = msg
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun modeButton(text: String, color: Int, active: Boolean): Button {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (active) color else dividerColor)
        }
        return Button(this).apply {
            this.text = text
            setTextColor(if (active) Color.WHITE else textColor)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = shape
            setAllCaps(false)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            elevation = if (active) dp(8).toFloat() else dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
        }
    }

    private fun fancyButton(text: String, color: Int): Button {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(color)
        }
        return Button(this).apply {
            this.text = text
            setTextColor(btnTextColor)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = shape
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setAllCaps(false)
            elevation = dp(6).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(7), 0, dp(7), 0) }
        }
    }

    private fun animateButtonActive(btn: Button, active: Boolean) {
        mainHandler.post {
            val shape = btn.background as GradientDrawable
            val colorFrom = (shape.color?.defaultColor ?: accentColor)
            val colorTo = if (active) accentColor else dividerColor
            val animator = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
            animator.duration = 320
            animator.addUpdateListener { v ->
                shape.setColor(v.animatedValue as Int)
            }
            animator.start()
        }
    }

    private fun shakeView(view: View) {
        view.animate()
            .translationX(dp(10).toFloat())
            .setDuration(40)
            .withEndAction {
                view.animate().translationX((-dp(10)).toFloat()).setDuration(40).withEndAction {
                    view.animate().translationX(0f).setDuration(40).start()
                }.start()
            }.start()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun startUdpReceiver(port: Int, mode: ListenMode) {
        listening = true
        resetThroughput()
        val modeMsg = when(mode) {
            ListenMode.UNICAST -> "Unicast (all device IPs)"
            ListenMode.BROADCAST -> "Broadcast (192.168.0.255)"
        }
        appendMessage("\n--- Listening: $modeMsg, port $port ---", accentColor)
        setStatus("Listening ($modeMsg) on port $port…", accentColor)
        receiverJob = CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                val listenIp = when(mode) {
                    ListenMode.UNICAST -> InetAddress.getByName("0.0.0.0")
                    ListenMode.BROADCAST -> InetAddress.getByName("192.168.0.255")
                }
                socket = DatagramSocket(port, listenIp)
                socket.broadcast = (mode == ListenMode.BROADCAST)
                appendMessage("Bound socket to ${listenIp.hostAddress}:$port", accentDark)
                Log.i(TAG, "Listening on ${listenIp.hostAddress}:$port")
            } catch (e: Exception) {
                appendMessage("FAILED TO BIND SOCKET: ${e.message}", errorColor)
                setStatus("Failed to bind socket: ${e.message}", errorColor)
                socket?.close()
                listening = false
                mainHandler.post {
                    startBtn.isEnabled = true
                    portInput.isEnabled = true
                    stopBtn.isEnabled = false
                    clearBtn.isEnabled = true
                    unicastBtn.isEnabled = true
                    broadcastBtn.isEnabled = true
                }
                return@launch
            }
            val buffer = ByteArray(8192)
            appendMessage("Entering receive loop…", accentColor)
            setStatus("Receiving messages…", accentColor)
            while (isActive && listening) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    val from = "${packet.address}:${packet.port}"
                    Log.i(TAG, "Received: $msg from $from")
                    appendMessage("[${from}]: $msg", titleColor)
                    updateThroughput(packet.length)
                } catch (e: Exception) {
                    appendMessage("RECEIVE ERROR: ${e.message}", errorColor)
                }
            }
            appendMessage("Receiver job ended", dividerColor)
            socket.close()
        }
    }

    private fun stopUdpReceiver() {
        listening = false
        receiverJob?.cancel()
        setStatus("Stopped.", accentDark)
        appendMessage("\n--- Stopped listening ---\n", dividerColor)
        finalizeThroughput()
    }

    // --- THROUGHPUT LOGIC ---
    private fun resetThroughput() {
        thrustBytes = 0L
        thrustPackets = 0
        thrustStart = 0L
        throughputPending = false
        thrustTimer?.let { mainHandler.removeCallbacks(it) }
        thrustTimer = null
    }

    private fun updateThroughput(len: Int) {
        mainHandler.post {
            val now = System.currentTimeMillis()
            if (!throughputPending) {
                thrustStart = now
                thrustBytes = 0L
                thrustPackets = 0
                throughputPending = true
            }
            thrustBytes += len
            thrustPackets += 1

            // If timer exists, remove
            thrustTimer?.let { mainHandler.removeCallbacks(it) }
            // Post delayed to finalize after 2s of no packets
            thrustTimer = Runnable { finalizeThroughput() }
            mainHandler.postDelayed(thrustTimer!!, 2000)
        }
    }

    private fun finalizeThroughput() {
        if (!throughputPending || thrustPackets <= 0) return
        val now = System.currentTimeMillis()
        val ms = now - thrustStart
        if (ms < 10) return // ignore glitch
        val mbps = (thrustBytes * 8.0) / (ms / 1000.0) / 1_000_000.0
        appendThroughputMsg(mbps, thrustPackets, thrustBytes, ms)
        throughputPending = false
        thrustPackets = 0
        thrustBytes = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUdpReceiver()
    }
}

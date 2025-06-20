package com.example.myapplication

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
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
    private lateinit var layout: LinearLayout

    // colors
    private val bgColor = Color.parseColor("#14151A")
    private val accentColor = Color.parseColor("#00C2B1")
    private val accentDark = Color.parseColor("#0A817A")
    private val errorColor = Color.parseColor("#F35C5C")
    private val textColor = Color.parseColor("#E4E8EF")
    private val btnTextColor = Color.WHITE
    private val btnBgColor = Color.parseColor("#23242C")
    private val btnRippleColor = Color.parseColor("#212124")
    private val titleColor = Color.parseColor("#29FFC6")
    private val dividerColor = Color.parseColor("#2D2F36")

    @Volatile private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bgColor
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(0, dp(38), 0, 0)
        }

        // Title
        val title = TextView(this).apply {
            text = "UDP Message Receiver"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(titleColor)
            setShadowLayer(2f, 0f, 2f, accentDark)
            setPadding(0, dp(0), 0, dp(24))
        }
        layout.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Port selector row
        val portRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(0), dp(18), dp(6))
        }
        portInput = EditText(this).apply {
            hint = "Port"
            setText("5005")
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(textColor)
            setHintTextColor(accentColor)
            setBackgroundColor(btnBgColor)
            background = null
            textSize = 16f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(12), 0)
            }
        }
        startBtn = fancyButton("Start", accentColor)
        stopBtn = fancyButton("Stop", errorColor)
        stopBtn.isEnabled = false

        portRow.addView(portInput)
        portRow.addView(startBtn)
        portRow.addView(stopBtn)
        layout.addView(portRow)

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
            setPadding(dp(12), 0, dp(12), 0)
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
        setStatus("Ready. Enter a port and press Start.", accentColor)

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
            startUdpReceiver(port)
            startBtn.isEnabled = false
            portInput.isEnabled = false
            stopBtn.isEnabled = true
            animateButtonActive(startBtn, false)
            animateButtonActive(stopBtn, true)
        }
        stopBtn.setOnClickListener {
            stopUdpReceiver()
            setStatus("Stopped.", accentDark)
            startBtn.isEnabled = true
            portInput.isEnabled = true
            stopBtn.isEnabled = false
            animateButtonActive(startBtn, true)
            animateButtonActive(stopBtn, false)
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

    private fun appendMessage(msg: String, color: Int = textColor) {
        mainHandler.post {
            val oldLen = messagesView.text.length
            messagesView.append("\n$msg")
            messagesView.setTextColor(textColor)
            // Animate "slide up" new message
            if (oldLen < messagesView.text.length) {
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

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun fancyButton(text: String, color: Int): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(btnTextColor)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            background = resources.getDrawable(android.R.drawable.btn_default, theme)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            stateListAnimator = null
            backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), 0, dp(6), 0) }
            elevation = dp(6).toFloat()
        }
    }

    private fun animateButtonActive(btn: Button, active: Boolean) {
        mainHandler.post {
            val colorFrom = (btn.backgroundTintList?.defaultColor ?: accentColor)
            val colorTo = if (active) accentColor else dividerColor
            val animator = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
            animator.duration = 320
            animator.addUpdateListener { v ->
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(v.animatedValue as Int)
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

    private fun startUdpReceiver(port: Int) {
        listening = true
        appendMessage("\n--- Listening on all IPs, port $port ---", accentColor)
        setStatus("Listening on port $port…", accentColor)
        receiverJob = CoroutineScope(Dispatchers.IO).launch {
            var socket: DatagramSocket? = null
            try {
                val listenIp = InetAddress.getByName("0.0.0.0")
                socket = DatagramSocket(port, listenIp)
                socket.broadcast = false
                appendMessage("Bound socket to 0.0.0.0:$port", accentDark)
                Log.i(TAG, "Listening on 0.0.0.0:$port")
            } catch (e: Exception) {
                appendMessage("FAILED TO BIND SOCKET: ${e.message}", errorColor)
                setStatus("Failed to bind socket: ${e.message}", errorColor)
                socket?.close()
                listening = false
                mainHandler.post {
                    startBtn.isEnabled = true
                    portInput.isEnabled = true
                    stopBtn.isEnabled = false
                }
                return@launch
            }
            val buffer = ByteArray(2048)
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
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUdpReceiver()
    }
}

package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import com.example.myapplication.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BroadcastListenerScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

data class BroadcastMessage(
    val timestamp: String,
    val message: String,
    val senderIP: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastListenerScreen(modifier: Modifier = Modifier) {
    var port by remember { mutableStateOf("8080") }
    var isListening by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<BroadcastMessage>()) }
    var socket by remember { mutableStateOf<DatagramSocket?>(null) }
    var listenerJob by remember { mutableStateOf<Job?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Function to start listening
    fun startListening() {
        val portNum = port.toIntOrNull()
        if (portNum != null && portNum in 1..65535) {
            listenerJob = scope.launch(Dispatchers.IO) {
                try {
                    val newSocket = DatagramSocket(portNum)
                    socket = newSocket

                    withContext(Dispatchers.Main) {
                        isListening = true
                        errorMessage = null
                    }

                    val buffer = ByteArray(1024)
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

                    while (isActive && !newSocket.isClosed) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            newSocket.receive(packet)

                            val message = String(packet.data, 0, packet.length)
                            val timestamp = timeFormat.format(Date())
                            val senderIP = packet.address.hostAddress ?: "Unknown"

                            val broadcastMessage = BroadcastMessage(
                                timestamp = timestamp,
                                message = message,
                                senderIP = senderIP
                            )

                            withContext(Dispatchers.Main) {
                                messages = messages + broadcastMessage
                            }
                        } catch (e: Exception) {
                            if (isActive && !newSocket.isClosed) {
                                withContext(Dispatchers.Main) {
                                    errorMessage = "Receive error: ${e.message}"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        errorMessage = "Socket error: ${e.message}"
                        isListening = false
                    }
                }
            }
        } else {
            errorMessage = "Please enter a valid port (1-65535)"
        }
    }

    // Function to stop listening
    fun stopListening() {
        listenerJob?.cancel()
        socket?.close()
        socket = null
        isListening = false
        errorMessage = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "UDP Broadcast Listener",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Port input
        OutlinedTextField(
            value = port,
            onValueChange = {
                if (it.all { char -> char.isDigit() } && it.length <= 5) {
                    port = it
                    errorMessage = null
                }
            },
            label = { Text("Port") },
            placeholder = { Text("8080") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = !isListening,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Broadcast IP display
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = "Listening on: 192.168.0.255:$port",
                modifier = Modifier.padding(12.dp),
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (isListening) {
                        stopListening()
                    } else {
                        startListening()
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isListening) Color(0xFFE53E3E) else Color(0xFF3182CE)
                ),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isListening) "STOP LISTENING" else "START LISTENING",
                    color = Color.White
                )
            }

            OutlinedButton(
                onClick = {
                    messages = emptyList()
                    errorMessage = null
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("CLEAR")
            }
        }

        // Error message
        errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFEBEE)
                )
            ) {
                Text(
                    text = error,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Status
        Text(
            text = if (isListening) "● Listening for broadcasts..." else "○ Not listening",
            color = if (isListening) Color(0xFF4CAF50) else Color.Gray,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Messages list
        Card(
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No messages received yet...",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                } else {
                    items(messages) { message ->
                        MessageItem(message = message)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageItem(message: BroadcastMessage) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = message.timestamp,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = "from: ${message.senderIP}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.message,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

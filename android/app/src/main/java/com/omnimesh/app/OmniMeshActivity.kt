package com.omnimesh.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.*

class OmniMeshActivity : AppCompatActivity() {

    // UI Components
    private lateinit var chatLogView: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var scanButton: Button
    private lateinit var themeButton: Button
    private lateinit var statusLabel: TextView

    // Networking & Bluetooth
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard Serial Port Profile for Nokia & Android
    private val WIFI_TCP_PORT = 8888

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeClientWriters = Collections.synchronizedList(mutableListOf<PrintWriter>())
    private val discoveredPeersList = mutableListOf<String>()

    private var isDarkMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeUserInterface()
        verifyAndRequestPermissions()
        startMeshServerListeners()
    }

    private fun initializeUserInterface() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        statusLabel = TextView(this).apply {
            text = "Status: Initializing OmniMesh Hub..."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }

        chatLogView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setTextIsSelectable(true)
            text = "=== OmniMesh Android Hub v1.0 ===\n[System]: Ready for multi-platform peering.\n"
        }

        messageInput = EditText(this).apply {
            hint = "Type broadcast message..."
            setPadding(16, 16, 16, 16)
        }

        sendButton = Button(this).apply {
            text = "Broadcast to Mesh"
            setOnClickListener {
                val text = messageInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    appendChatLog("[Me]: $text")
                    broadcastMessageToAllPeers(text)
                    messageInput.setText("")
                }
            }
        }

        scanButton = Button(this).apply {
            text = "Scan Bluetooth Peers"
            setOnClickListener {
                initiateBluetoothDeviceDiscovery()
            }
        }

        themeButton = Button(this).apply {
            text = "Toggle Theme"
            setOnClickListener {
                toggleAppTheme()
            }
        }

        rootLayout.addView(statusLabel)
        rootLayout.addView(chatLogView)
        rootLayout.addView(messageInput)
        rootLayout.addView(sendButton)
        rootLayout.addView(scanButton)
        rootLayout.addView(themeButton)

        setContentView(rootLayout)
        applyThemeStyling()
    }

    private fun verifyAndRequestPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 202)
        }
    }

    private fun startMeshServerListeners() {
        // 1. Start Bluetooth RFCOMM Server Thread for Nokia E72 and Nokia 2700 classic
        activityScope.launch(Dispatchers.IO) {
            try {
                val btServerSocket: BluetoothServerSocket? =
                    bluetoothAdapter?.listenUsingRfcommWithServiceRecord("OmniMeshBT", SPP_UUID)

                withContext(Dispatchers.Main) {
                    statusLabel.text = "Status: Listening on Bluetooth & Wi-Fi"
                    appendLog("[BT Server]: Listening on RFCOMM channel...")
                }

                while (isActive) {
                    val socket: BluetoothSocket? = btServerSocket?.accept()
                    if (socket != null) {
                        handleIncomingBluetoothSocket(socket)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[BT Server Error]: ${e.message}")
                }
            }
        }

        // 2. Start TCP Wi-Fi Server Thread for Android-to-Android mesh communication
        activityScope.launch(Dispatchers.IO) {
            try {
                val wifiServerSocket = ServerSocket(WIFI_TCP_PORT)
                withContext(Dispatchers.Main) {
                    appendLog("[Wi-Fi Server]: Listening on TCP port $WIFI_TCP_PORT...")
                }

                while (isActive) {
                    val clientSocket = wifiServerSocket.accept()
                    handleIncomingTcpSocket(clientSocket)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[Wi-Fi Server Error]: ${e.message}")
                }
            }
        }
    }

    private fun handleIncomingBluetoothSocket(socket: BluetoothSocket) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = PrintWriter(socket.outputStream, true)
                synchronized(activeClientWriters) { activeClientWriters.add(writer) }

                withContext(Dispatchers.Main) {
                    appendLog("[Connected]: Legacy peer joined via Bluetooth.")
                }

                while (socket.isConnected) {
                    val line = reader.readLine() ?: break
                    withContext(Dispatchers.Main) {
                        appendLog("[Legacy Peer]: $line")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLog("[BT Connection Dropped]: ${e.message}")
                }
            }
        }
    }

    private fun handleIncomingTcpSocket(socket: Socket) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = PrintWriter(socket.outputStream, true)
                synchronized(activeClientWriters) { activeClientWriters.add(writer) }

                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    withContext(Dispatchers.Main) {
                        appendLog("[Android Peer]: $line")
                    }
                }
            } catch (e: Exception) {
                // Connection closed or dropped
            }
        }
    }

    private fun initiateBluetoothDeviceDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            appendLog("[Permission Error]: Missing BLUETOOTH_SCAN permission.")
            return
        }

        appendLog("[Scan]: Starting Bluetooth discovery inquiry...")
        bluetoothAdapter?.startDiscovery()
    }

    private fun broadcastMessageToAllPeers(message: String) {
        activityScope.launch(Dispatchers.IO) {
            val payload = message + "\n"
            synchronized(activeClientWriters) {
                for (writer in activeClientWriters) {
                    try {
                        writer.println(payload)
                    } catch (e: Exception) {
                        // Dead writer cleanup handled separately
                    }
                }
            }
        }
    }

    private fun toggleAppTheme() {
        isDarkMode = !isDarkMode
        applyThemeStyling()
        appendLog("[Theme]: Switched visual theme layout.")
    }

    private fun applyThemeStyling() {
        if (isDarkMode) {
            chatLogView.setBackgroundColor(0xFF121212.toInt())
            chatLogView.setTextColor(0xFF00E676.toInt()) // Matrix green accent
        } else {
            chatLogView.setBackgroundColor(0xFFFAFAFA.toInt())
            chatLogView.setTextColor(0xFF212121.toInt())
        }
    }

    private fun appendLog(text: String) {
        chatLogView.append("\n$text")
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}

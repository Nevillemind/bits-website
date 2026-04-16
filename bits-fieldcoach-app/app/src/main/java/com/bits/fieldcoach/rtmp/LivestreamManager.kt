package com.bits.fieldcoach.rtmp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bits.fieldcoach.ble.BleConnectionManager
import com.bits.fieldcoach.stream.StreamRelay
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Manages the full GO LIVE lifecycle with comprehensive debug logging.
 * Every step logs detailed state information for diagnosing issues.
 */
class LivestreamManager(
    private val context: Context,
    private val bleManager: BleConnectionManager,
    private val serverUrl: String,
    private val savedSsid: String = "",
    private val savedPassword: String = ""
) {
    companion object {
        private const val TAG = "LivestreamManager"
        private const val RTMP_PORT = 1935
        private const val WIFI_CONNECT_DELAY_MS = 5000L
    }

    enum class State {
        IDLE, ENABLING_HOTSPOT, STARTING_RTMP_SERVER, SENDING_WIFI_CREDS,
        WAITING_WIFI_CONNECT, STARTING_STREAM, LIVE, ERROR
    }

    var state: State = State.IDLE
        private set

    private var rtmpServer: RtmpServer? = null
    private var streamRelay: StreamRelay? = null
    private var hotspotSSID: String = ""
    private var hotspotPassword: String = ""
    private var localIp: String = ""
    private var workerId: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var stateListener: ((State) -> Unit)? = null
    private var debugListener: ((String) -> Unit)? = null

    // Accumulated debug log
    private val debugLog = StringBuilder()

    fun setStateListener(listener: (State) -> Unit) {
        stateListener = listener
    }

    fun setDebugListener(listener: (String) -> Unit) {
        debugListener = listener
    }

    private fun dbg(msg: String) {
        val timestamp = System.currentTimeMillis() % 100000
        val line = "[$timestamp] $msg"
        Log.d(TAG, line)
        debugLog.append(line).append("\n")
        handler.post {
            debugListener?.invoke(debugLog.toString())
        }
    }

    fun getDebugLog(): String = debugLog.toString()

    fun goLive(workerId: String) {
        if (state != State.IDLE) {
            dbg("BLOCKED: Already in state $state")
            return
        }
        this.workerId = workerId
        debugLog.clear()
        dbg("========== GO LIVE START ==========")
        dbg("Worker: $workerId")
        dbg("Server: $serverUrl")
        dbg("BLE connected: ${bleManager.isConnected()}")
        dbg("Android SDK: ${android.os.Build.VERSION.SDK_INT}")
        dbg("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        dbg("")

        // Dump ALL network interfaces first
        dumpAllInterfaces()

        updateState(State.ENABLING_HOTSPOT)
        startHotspot()
    }

    private fun dumpAllInterfaces() {
        dbg("--- ALL NETWORK INTERFACES ---")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                val addrList = mutableListOf<String>()
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress) {
                        addrList.add(a.hostAddress ?: "?")
                    }
                }
                if (addrList.isNotEmpty() || iface.name.startsWith("wlan") || iface.name.startsWith("ap") || iface.name.startsWith("swlan") || iface.name.startsWith("softap") || iface.name.startsWith("rmnet")) {
                    dbg("  ${iface.name}: up=${iface.isUp}, addrs=$addrList")
                }
            }
        } catch (e: Exception) {
            dbg("  ERROR reading interfaces: ${e.message}")
        }
        dbg("")
    }

    private fun startHotspot() {
        dbg("STEP 1: Starting hotspot...")

        // Use saved credentials if available
        if (savedSsid.isNotEmpty()) {
            hotspotSSID = savedSsid
            hotspotPassword = savedPassword
            dbg("  Using saved credentials: SSID='$hotspotSSID'")
        }

        // First check: is hotspot already running?
        val existingIp = findHotspotIp()
        if (existingIp.isNotEmpty()) {
            dbg("HOTSPOT ALREADY RUNNING! IP=$existingIp")
            localIp = existingIp
            startRtmpServer()
            return
        }

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            dbg("WifiManager obtained. Attempting startLocalOnlyHotspot...")

            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    dbg("HOTSPOT onStarted() callback fired!")
                    dbg("  reservation class: ${reservation.javaClass.simpleName}")

                    val config = reservation.wifiConfiguration
                    if (config == null) {
                        dbg("  wifiConfiguration = NULL (deprecated on API 33+)")
                        if (hotspotSSID.isNotEmpty()) {
                            dbg("  Using saved SSID: '$hotspotSSID'")
                        } else {
                            dbg("  No saved SSID either — glasses won't know network name!")
                        }
                    } else {
                        dbg("  wifiConfiguration.SSID = ${config.SSID}")
                        dbg("  wifiConfiguration.preSharedKey = ${config.preSharedKey}")
                        // Prefer saved credentials (user-entered) over API response
                        if (hotspotSSID.isEmpty()) hotspotSSID = config.SSID ?: ""
                        if (hotspotPassword.isEmpty()) hotspotPassword = config.preSharedKey ?: ""
                    }

                    // Dump interfaces again after hotspot started
                    dumpAllInterfaces()

                    // Get IP
                    localIp = findHotspotIp()
                    dbg("  Hotspot IP found: '$localIp'")

                    if (localIp.isEmpty()) {
                        dbg("  IP EMPTY — trying all interfaces with private IPs...")
                        localIp = findAnyPrivateIp()
                        dbg("  Fallback IP: '$localIp'")
                    }

                    if (hotspotSSID.isEmpty()) {
                        dbg("  SSID EMPTY — attempting reflection to read hotspot config")
                        tryReadHotspotSSID(reservation)
                    }

                    dbg("FINAL HOTSPOT STATE:")
                    dbg("  SSID='$hotspotSSID'")
                    dbg("  Password='$hotspotPassword'")
                    dbg("  IP='$localIp'")
                    dbg("")

                    startRtmpServer()
                }

                override fun onFailed(reason: Int) {
                    val reasonStr = when (reason) {
                        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "ERROR_NO_CHANNEL"
                        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "ERROR_INCOMPATIBLE_MODE"
                        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "ERROR_TETHERING_DISALLOWED"
                        else -> "ERROR_UNKNOWN_$reason"
                    }
                    dbg("HOTSPOT onFailed() reason=$reason ($reasonStr)")

                    // Dump interfaces to see if anything changed
                    dumpAllInterfaces()

                    localIp = findHotspotIp()
                    if (localIp.isEmpty()) {
                        localIp = findAnyPrivateIp()
                    }

                    if (localIp.isNotEmpty()) {
                        dbg("  Found fallback IP: $localIp — continuing")
                        startRtmpServer()
                    } else {
                        dbg("  No IP found anywhere. CANNOT PROCEED.")
                        dbg("  User may need to enable hotspot manually in Settings")
                        updateState(State.ERROR)
                    }
                }

                override fun onStopped() {
                    dbg("HOTSPOT onStopped() — hotspot was stopped")
                }
            }, handler)
        } catch (e: SecurityException) {
            dbg("SECURITY EXCEPTION: ${e.message}")
            dbg("NEARBY_WIFI_DEVICES permission may not be granted")
            dumpAllInterfaces()
            localIp = findHotspotIp()
            if (localIp.isEmpty()) localIp = findAnyPrivateIp()
            if (localIp.isNotEmpty()) {
                dbg("  Found IP despite error: $localIp")
                startRtmpServer()
            } else {
                updateState(State.ERROR)
            }
        } catch (e: Exception) {
            dbg("UNEXPECTED EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            updateState(State.ERROR)
        }
    }

    private fun tryReadHotspotInfo() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            // Try to get hotspot config via reflection
            val method = wifiManager?.javaClass?.getDeclaredMethod("getWifiApConfiguration")
            method?.isAccessible = true
            val config = method?.invoke(wifiManager) as? WifiConfiguration
            if (config != null) {
                dbg("  Reflection got config: SSID=${config.SSID}, PSK=${config.preSharedKey}")
                if (hotspotSSID.isEmpty()) hotspotSSID = config.SSID ?: ""
                if (hotspotPassword.isEmpty()) hotspotPassword = config.preSharedKey ?: ""
            }
        } catch (e: Exception) {
            dbg("  Reflection failed: ${e.message}")
        }
    }

    private fun tryReadHotspotSSID(reservation: WifiManager.LocalOnlyHotspotReservation) {
        try {
            // Try getting SoftApConfiguration on API 31+
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val method = reservation.javaClass.getDeclaredMethod("getSoftApConfiguration")
                method.isAccessible = true
                val softApConfig = method.invoke(reservation)
                if (softApConfig != null) {
                    val ssidMethod = softApConfig.javaClass.getDeclaredMethod("getSsid")
                    val passphraseMethod = softApConfig.javaClass.getDeclaredMethod("getPassphrase")
                    val ssid = ssidMethod.invoke(softApConfig) as? String ?: ""
                    val pass = passphraseMethod.invoke(softApConfig) as? String ?: ""
                    dbg("  SoftApConfiguration: SSID='$ssid', pass='$pass'")
                    if (hotspotSSID.isEmpty()) hotspotSSID = ssid
                    if (hotspotPassword.isEmpty()) hotspotPassword = pass
                }
            }
        } catch (e: Exception) {
            dbg("  SoftApConfiguration read failed: ${e.message}")
        }
    }

    private fun findHotspotIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // Expanded list: wlan, ap, softap, swlan (Samsung), wigig, wlan+digit
                val name = iface.name.lowercase()
                if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap")
                    || name.startsWith("swlan") || name.startsWith("wigig") || name.startsWith("ccmni")) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: ""
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                dbg("  Found hotspot IP on ${iface.name}: $ip")
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            dbg("  Error scanning interfaces: ${e.message}")
        }
        return ""
    }

    private fun findAnyPrivateIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: ""
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            dbg("  Found ANY private IP on ${iface.name}: $ip")
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun startRtmpServer() {
        updateState(State.STARTING_RTMP_SERVER)
        dbg("STEP 2: Starting RTMP server...")

        if (localIp.isEmpty()) {
            dbg("  IP is empty — binding to 0.0.0.0")
            localIp = "0.0.0.0"
        }

        // Initialize StreamRelay
        dbg("  Creating StreamRelay: worker=$workerId url=$serverUrl")
        streamRelay = StreamRelay(workerId, serverUrl)
        streamRelay?.start()

        // Initialize RTMP server
        rtmpServer = RtmpServer(
            onVideoData = { videoData ->
                streamRelay?.sendVideoFrame(videoData)
            },
            onAudioData = { audioData ->
                streamRelay?.sendAudioFrame(audioData)
            },
            onClientConnected = {
                dbg("RTMP CLIENT CONNECTED (glasses!)")
            },
            onClientDisconnected = {
                dbg("RTMP CLIENT DISCONNECTED")
                if (state == State.LIVE) {
                    updateState(State.IDLE)
                }
            }
        )

        val bindAddress = localIp
        dbg("  Binding RTMP server to $bindAddress:$RTMP_PORT")
        val started = rtmpServer!!.start(bindAddress, RTMP_PORT)

        if (!started) {
            dbg("  RTMP server FAILED to start!")
            updateState(State.ERROR)
            return
        }

        dbg("  RTMP server started successfully")
        dbg("")

        // Step 3: Send WiFi credentials to glasses
        updateState(State.SENDING_WIFI_CREDS)
        sendWifiCredentials()
    }

    private fun sendWifiCredentials() {
        dbg("STEP 3: Sending WiFi credentials to glasses via BLE...")
        dbg("  SSID='$hotspotSSID'")
        dbg("  Password='$hotspotPassword'")

        if (hotspotSSID.isEmpty()) {
            dbg("  WARNING: SSID is empty! Glasses can't connect to unnamed network")
        }

        val success = bleManager.sendWifiCredentials(hotspotSSID, hotspotPassword)
        dbg("  BLE sendWifiCredentials returned: $success")

        if (!success) {
            dbg("  FAILED to send WiFi credentials")
            updateState(State.ERROR)
            return
        }

        dbg("  WiFi credentials sent OK")
        dbg("")

        // Wait for glasses to connect to WiFi
        updateState(State.WAITING_WIFI_CONNECT)
        dbg("STEP 4: Waiting ${WIFI_CONNECT_DELAY_MS}ms for glasses WiFi connection...")
        handler.postDelayed({
            startRtmpStream()
        }, WIFI_CONNECT_DELAY_MS)
    }

    private fun startRtmpStream() {
        updateState(State.STARTING_STREAM)
        dbg("STEP 5: Sending RTMP start command to glasses...")

        val rtmpUrl = "rtmp://$localIp:$RTMP_PORT/live/$workerId"
        dbg("  RTMP URL: $rtmpUrl")

        if (localIp.isEmpty() || localIp == "0.0.0.0") {
            dbg("  WARNING: IP is $localIp — glasses can't reach this!")
        }

        val success = bleManager.startRtmpStream(rtmpUrl)
        dbg("  BLE startRtmpStream returned: $success")

        if (!success) {
            dbg("  FAILED to send RTMP start command")
            updateState(State.ERROR)
            return
        }

        dbg("  RTMP start command sent OK")
        dbg("  Waiting for glasses response (rtmp_stream_status)...")
        dbg("")
        dbg("========== GO LIVE COMPLETE ==========")
        dbg("Glasses should now be streaming to $rtmpUrl")
        dbg("Stream should appear at bitsfieldcoach.com/livestream/")
        dbg("")
        dbg("If you see rtmp_stream_status:error, check:")
        dbg("  1. Is hotspot actually on? (Settings → Hotspot)")
        dbg("  2. Are glasses connected to hotspot WiFi?")
        dbg("  3. Is the IP address correct?")

        updateState(State.LIVE)
    }

    fun stopLive() {
        dbg("Stopping live stream...")
        bleManager.stopRtmpStream()
        rtmpServer?.stop()
        rtmpServer = null
        streamRelay?.stop()
        streamRelay = null
        updateState(State.IDLE)
    }

    private fun updateState(newState: State) {
        state = newState
        dbg("STATE → $newState")
        handler.post {
            stateListener?.invoke(newState)
        }
    }

    fun isLive(): Boolean = state == State.LIVE
}

package gr.volley.matchup2

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import java.net.ServerSocket
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlin.concurrent.thread
import android.text.Editable
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

data class Player(var number: String = "", var name: String = "", var role: String = "")
data class Team(var name: String, val roster: MutableList<Player> = MutableList(14) { Player() })

class MainActivity : AppCompatActivity() {
    private val teams = arrayOf(Team("ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ"), Team("ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ"))
    private val roles = arrayOf("Π", "ΑΚ1", "ΑΚ2", "Κ1", "Κ2", "Δ", "Λ")
    private val selected = Array(2) { IntArray(6) { -1 } }
    private val p1Formation = Array(2) { IntArray(6) { -1 } }
    private var teamIndex = 0
    private var servingTeam = 0 // 0 = δική μας, 1 = αντίπαλη
    private var receptionRecordedThisRally = false
    private var ourAttackCountThisRally = 0
    // context|rotation|courtZone|role|player|result
    private val ourAttackStats = mutableMapOf<String, Int>()
    private var rallyBallTeam: Int? = null
    private var lastActionWasBlock = false
    private var lastAttackerTeam: Int? = null
    private var lastAttackerName: String? = null
    private var lastAttackerPosition: String? = null
    private var lastTouchedTeam: Int? = null
    private var lastTouchedPos: Int? = null
    private var lastTouchWasBlock = false
    // Context of the next attack for each team: RECEPTION (side-out) or DEFENSE (transition).
    private val nextAttackContext = arrayOf<String?>(null, null)
    private var lastAttackerContext: String? = null
    private val initialRotation = intArrayOf(1, 1)
    // Explicit logical rotation labels. Order is P1 -> P6 -> P5 -> P4 -> P3 -> P2 -> P1.
    private val currentRotation = intArrayOf(1, 1)
    private val receptionCounts = mutableMapOf<String, Int>()
    private val receptionCountsByRotation = mutableMapOf<String, Int>()
    private val receptionByPlayerRotation = mutableMapOf<String, Int>()
    // Active on-court roster index per tactical role; substitutions never rewrite player history.
    private val activeRolePlayers = mutableMapOf<String, Int>()
    private val substitutionHistory = mutableListOf<String>()
    private val opponentActiveRolePlayers = mutableMapOf<String, Int>()
    private fun activePlayerForRole(role: String, ti: Int = 0): Player? {
        val assignments = if (ti == 0) activeRolePlayers else opponentActiveRolePlayers
        val idx = assignments[role]
        return if (idx != null && idx in teams[ti].roster.indices) teams[ti].roster[idx]
        else teams[ti].roster.firstOrNull { it.role == role }
    }

    private val receptionBackCounts = mutableMapOf<String, Int>()
    private val receptionFrontCounts = mutableMapOf<String, Int>()
    private val receptionPositionCounts = mutableMapOf<String, Int>()
    private var currentSet = 1
    private val score = intArrayOf(0, 0)
    private val previousSetCounts = mutableMapOf<String, Int>()
    private val previousSetBackCounts = mutableMapOf<String, Int>()
    private val previousSetFrontCounts = mutableMapOf<String, Int>()
    private val matchReceptionCounts = mutableMapOf<String, Int>()
    private val matchReceptionBackCounts = mutableMapOf<String, Int>()
    private val matchReceptionFrontCounts = mutableMapOf<String, Int>()
    private val completedSetCounts = mutableMapOf<String, Int>()
    private val completedSetBackCounts = mutableMapOf<String, Int>()
    private val completedSetFrontCounts = mutableMapOf<String, Int>()
    private val serverPreviousCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val serverPreviousBackCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val serverPreviousFrontCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val serverSetCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val serverRotationBackCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val serverRotationFrontCounts = mutableMapOf<String, MutableMap<String, Int>>()
    // Opponent attack pattern: rotation|attacker|attackPosition|targetPosition -> count
    private val opponentAttackPattern = mutableMapOf<String, Int>()
    private val opponentAttackPatternByContext = mutableMapOf<String, Int>()
    private val opponentAttackOrigins = mutableMapOf<String, Int>() // context|rotation|zone|player
    private lateinit var root: LinearLayout
    private lateinit var currentScroll: ScrollView
    private var currentScreen = "HOME"
    private val exportTeamsRequest = 9101
    private val importTeamsRequest = 9102
    private var syncMode = "OFF"
    private var hostServer: ServerSocket? = null
    private val hostClients = mutableListOf<Socket>()
    private var viewerSocket: Socket? = null
    private var syncConnected = false
    private var discoverySocket: DatagramSocket? = null
    private val discoveryPort = 50506

    private data class RallySnapshot(
        val selected: Array<IntArray>, val currentRotation: IntArray, val servingTeam: Int, val score: IntArray,
        val receptionRecordedThisRally: Boolean, val rallyBallTeam: Int?, val lastActionWasBlock: Boolean,
        val lastAttackerTeam: Int?, val lastAttackerName: String?, val lastAttackerPosition: String?,
        val lastAttackerContext: String?, val nextAttackContext: Array<String?>,
        val lastTouchedTeam: Int?, val lastTouchedPos: Int?, val lastTouchWasBlock: Boolean,
        val receptionCounts: Map<String,Int>, val receptionCountsByRotation: Map<String,Int>,
        val receptionByPlayerRotation: Map<String,Int>,
        val receptionBackCounts: Map<String,Int>, val receptionFrontCounts: Map<String,Int>,
        val receptionPositionCounts: Map<String,Int>, val matchReceptionCounts: Map<String,Int>,
        val matchReceptionBackCounts: Map<String,Int>, val matchReceptionFrontCounts: Map<String,Int>,
        val serverPreviousCounts: Map<String,Map<String,Int>>,
        val serverPreviousBackCounts: Map<String,Map<String,Int>>,
        val serverPreviousFrontCounts: Map<String,Map<String,Int>>,
        val serverSetCounts: Map<String,Map<String,Int>>,
        val serverRotationBackCounts: Map<String,Map<String,Int>>,
        val serverRotationFrontCounts: Map<String,Map<String,Int>>,
        val opponentAttackPattern: Map<String,Int>, val opponentAttackPatternByContext: Map<String,Int>,
        val opponentAttackOrigins: Map<String,Int>,
        val ourAttackStats: Map<String,Int>, val ourAttackCountThisRally: Int
    )
    private var undoSnapshot: RallySnapshot? = null
    private var lastActionText = "Καμία ενέργεια"

    private fun nestedCopy(src: Map<String, MutableMap<String,Int>>): Map<String,Map<String,Int>> =
        src.mapValues { HashMap(it.value) }

    private fun captureForUndo(action: String) {
        undoSnapshot = RallySnapshot(
            Array(2) { selected[it].clone() }, currentRotation.clone(), servingTeam, score.clone(),
            receptionRecordedThisRally, rallyBallTeam, lastActionWasBlock,
            lastAttackerTeam, lastAttackerName, lastAttackerPosition, lastAttackerContext,
            nextAttackContext.clone(), lastTouchedTeam, lastTouchedPos, lastTouchWasBlock,
            HashMap(receptionCounts), HashMap(receptionCountsByRotation), HashMap(receptionByPlayerRotation),
            HashMap(receptionBackCounts), HashMap(receptionFrontCounts), HashMap(receptionPositionCounts),
            HashMap(matchReceptionCounts), HashMap(matchReceptionBackCounts), HashMap(matchReceptionFrontCounts),
            nestedCopy(serverPreviousCounts), nestedCopy(serverPreviousBackCounts),
            nestedCopy(serverPreviousFrontCounts), nestedCopy(serverSetCounts),
            nestedCopy(serverRotationBackCounts), nestedCopy(serverRotationFrontCounts),
            HashMap(opponentAttackPattern), HashMap(opponentAttackPatternByContext), HashMap(opponentAttackOrigins),
            HashMap(ourAttackStats), ourAttackCountThisRally
        )
        lastActionText = action
    }

    private fun restoreNested(dst: MutableMap<String, MutableMap<String,Int>>, src: Map<String,Map<String,Int>>) {
        dst.clear()
        src.forEach { (k,v) -> dst[k] = v.toMutableMap() }
    }

    private fun undoLastAction() {
        val s = undoSnapshot ?: run {
            Toast.makeText(this, "Δεν υπάρχει ενέργεια για αναίρεση", Toast.LENGTH_SHORT).show()
            return
        }
        for (t in 0..1) for (p in 0..5) selected[t][p] = s.selected[t][p]
        currentRotation[0]=s.currentRotation[0]; currentRotation[1]=s.currentRotation[1]
        servingTeam=s.servingTeam; score[0]=s.score[0]; score[1]=s.score[1]; receptionRecordedThisRally=s.receptionRecordedThisRally
        rallyBallTeam=s.rallyBallTeam; lastActionWasBlock=s.lastActionWasBlock
        lastAttackerTeam=s.lastAttackerTeam; lastAttackerName=s.lastAttackerName
        lastAttackerPosition=s.lastAttackerPosition; lastAttackerContext=s.lastAttackerContext
        nextAttackContext[0]=s.nextAttackContext[0]; nextAttackContext[1]=s.nextAttackContext[1]
        lastTouchedTeam=s.lastTouchedTeam; lastTouchedPos=s.lastTouchedPos; lastTouchWasBlock=s.lastTouchWasBlock
        fun restore(dst:MutableMap<String,Int>, src:Map<String,Int>){ dst.clear(); dst.putAll(src) }
        restore(receptionCounts,s.receptionCounts); restore(receptionCountsByRotation,s.receptionCountsByRotation)
        restore(receptionByPlayerRotation,s.receptionByPlayerRotation)
        restore(receptionBackCounts,s.receptionBackCounts); restore(receptionFrontCounts,s.receptionFrontCounts)
        restore(receptionPositionCounts,s.receptionPositionCounts); restore(matchReceptionCounts,s.matchReceptionCounts)
        restore(matchReceptionBackCounts,s.matchReceptionBackCounts); restore(matchReceptionFrontCounts,s.matchReceptionFrontCounts)
        restoreNested(serverPreviousCounts,s.serverPreviousCounts); restoreNested(serverPreviousBackCounts,s.serverPreviousBackCounts)
        restoreNested(serverPreviousFrontCounts,s.serverPreviousFrontCounts); restoreNested(serverSetCounts,s.serverSetCounts)
        restoreNested(serverRotationBackCounts,s.serverRotationBackCounts); restoreNested(serverRotationFrontCounts,s.serverRotationFrontCounts)
        restore(opponentAttackPattern,s.opponentAttackPattern); restore(opponentAttackPatternByContext,s.opponentAttackPatternByContext)
        restore(opponentAttackOrigins,s.opponentAttackOrigins)
        restore(ourAttackStats,s.ourAttackStats)
        ourAttackCountThisRally = s.ourAttackCountThisRally
        val undone = lastActionText
        undoSnapshot=null
        lastActionText="Αναίρεση: $undone"
        Toast.makeText(this, "Αναιρέθηκε: $undone", Toast.LENGTH_SHORT).show()
        showCourt()
    }

    // Match data are stored privately on this device, separately from VolleyMatchup2.
    private fun saveMatchData() {
        try {
            val data = JSONObject()
            data.put("set", currentSet)
            data.put("score", org.json.JSONArray(score.toList()))
            data.put("rotation", org.json.JSONArray(currentRotation.toList()))
            data.put("serving", servingTeam)
            data.put("receivingDone", receptionRecordedThisRally)
            data.put("attackCount", ourAttackCountThisRally)
            data.put("selected0", org.json.JSONArray(selected[0].toList()))
            data.put("selected1", org.json.JSONArray(selected[1].toList()))
            data.put("p1_0", org.json.JSONArray(p1Formation[0].toList()))
            data.put("p1_1", org.json.JSONArray(p1Formation[1].toList()))
            data.put("teams", org.json.JSONArray(listOf(teams[0].name, teams[1].name)))
            data.put("activeRolePlayers", JSONObject(activeRolePlayers as Map<*, *>))
            data.put("opponentActiveRolePlayers", JSONObject(opponentActiveRolePlayers as Map<*, *>))
            data.put("substitutionHistory", org.json.JSONArray(substitutionHistory))
            val maps = JSONObject()
            val stats = mapOf(
                "ourAttackStats" to ourAttackStats,
                "receptionCounts" to receptionCounts,
                "receptionCountsByRotation" to receptionCountsByRotation,
                "receptionByPlayerRotation" to receptionByPlayerRotation,
                "receptionBackCounts" to receptionBackCounts,
                "receptionFrontCounts" to receptionFrontCounts,
                "receptionPositionCounts" to receptionPositionCounts,
                "matchReceptionCounts" to matchReceptionCounts,
                "matchReceptionBackCounts" to matchReceptionBackCounts,
                "matchReceptionFrontCounts" to matchReceptionFrontCounts,
                "opponentAttackPattern" to opponentAttackPattern,
                "opponentAttackPatternByContext" to opponentAttackPatternByContext,
                "opponentAttackOrigins" to opponentAttackOrigins,
                "previousSetCounts" to previousSetCounts,
                "completedSetCounts" to completedSetCounts
            )
            stats.forEach { (name, map) -> maps.put(name, JSONObject(map as Map<*, *>)) }
            data.put("maps", maps)
            val nested = JSONObject()
            mapOf(
                "serverPreviousCounts" to serverPreviousCounts,
                "serverPreviousBackCounts" to serverPreviousBackCounts,
                "serverPreviousFrontCounts" to serverPreviousFrontCounts,
                "serverSetCounts" to serverSetCounts,
                "serverRotationBackCounts" to serverRotationBackCounts,
                "serverRotationFrontCounts" to serverRotationFrontCounts
            ).forEach { (name, map) ->
                val obj = JSONObject()
                map.forEach { (key, counts) -> obj.put(key, JSONObject(counts as Map<*, *>)) }
                nested.put(name, obj)
            }
            data.put("nested", nested)
            getSharedPreferences("volley_matchup3_match", MODE_PRIVATE).edit()
                .putString("current", data.toString()).commit()
        } catch (_: Exception) {
            Toast.makeText(this, "Αποτυχία αποθήκευσης αγώνα", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreMatchData() {
        val raw = getSharedPreferences("volley_matchup3_match", MODE_PRIVATE)
            .getString("current", null) ?: return
        try {
            val data = JSONObject(raw)
            currentSet = data.optInt("set", 1)
            servingTeam = data.optInt("serving", 0)
            receptionRecordedThisRally = data.optBoolean("receivingDone", false)
            ourAttackCountThisRally = data.optInt("attackCount", 0)
            fun loadArray(key: String, target: IntArray) {
                val values = data.optJSONArray(key) ?: return
                for (i in target.indices) if (i < values.length()) target[i] = values.optInt(i, target[i])
            }
            loadArray("score", score)
            loadArray("rotation", currentRotation)
            loadArray("selected0", selected[0]); loadArray("selected1", selected[1])
            loadArray("p1_0", p1Formation[0]); loadArray("p1_1", p1Formation[1])
            val names = data.optJSONArray("teams")
            if (names != null && names.length() >= 2) {
                teams[0].name = names.optString(0, teams[0].name)
                teams[1].name = names.optString(1, teams[1].name)
            }
            activeRolePlayers.clear()
            data.optJSONObject("activeRolePlayers")?.let { obj ->
                obj.keys().forEach { role -> activeRolePlayers[role] = obj.optInt(role, -1) }
            }
            opponentActiveRolePlayers.clear()
            data.optJSONObject("opponentActiveRolePlayers")?.let { obj ->
                obj.keys().forEach { role -> opponentActiveRolePlayers[role] = obj.optInt(role, -1) }
            }
            substitutionHistory.clear()
            data.optJSONArray("substitutionHistory")?.let { arr ->
                for (i in 0 until arr.length()) substitutionHistory.add(arr.optString(i))
            }
            val maps = data.optJSONObject("maps")
            fun loadMap(key: String, target: MutableMap<String, Int>) {
                val obj = maps?.optJSONObject(key) ?: return
                target.clear()
                obj.keys().forEach { name -> target[name] = obj.optInt(name) }
            }
            loadMap("ourAttackStats", ourAttackStats)
            loadMap("receptionCounts", receptionCounts)
            loadMap("receptionCountsByRotation", receptionCountsByRotation)
            loadMap("receptionByPlayerRotation", receptionByPlayerRotation)
            loadMap("receptionBackCounts", receptionBackCounts)
            loadMap("receptionFrontCounts", receptionFrontCounts)
            loadMap("receptionPositionCounts", receptionPositionCounts)
            loadMap("matchReceptionCounts", matchReceptionCounts)
            loadMap("matchReceptionBackCounts", matchReceptionBackCounts)
            loadMap("matchReceptionFrontCounts", matchReceptionFrontCounts)
            loadMap("opponentAttackPattern", opponentAttackPattern)
            loadMap("opponentAttackPatternByContext", opponentAttackPatternByContext)
            loadMap("opponentAttackOrigins", opponentAttackOrigins)
            loadMap("previousSetCounts", previousSetCounts)
            loadMap("completedSetCounts", completedSetCounts)
            val nested = data.optJSONObject("nested")
            fun loadNested(key: String, target: MutableMap<String, MutableMap<String, Int>>) {
                val obj = nested?.optJSONObject(key) ?: return
                target.clear()
                obj.keys().forEach { name ->
                    val inner = obj.optJSONObject(name) ?: return@forEach
                    val counts = mutableMapOf<String, Int>()
                    inner.keys().forEach { k -> counts[k] = inner.optInt(k) }
                    target[name] = counts
                }
            }
            loadNested("serverPreviousCounts", serverPreviousCounts)
            loadNested("serverPreviousBackCounts", serverPreviousBackCounts)
            loadNested("serverPreviousFrontCounts", serverPreviousFrontCounts)
            loadNested("serverSetCounts", serverSetCounts)
            loadNested("serverRotationBackCounts", serverRotationBackCounts)
            loadNested("serverRotationFrontCounts", serverRotationFrontCounts)
        } catch (_: Exception) {
            Toast.makeText(this, "Δεν ήταν δυνατή η ανάκτηση του αγώνα", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        saveMatchData()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        restoreMatchData()
        showHome()
    }

    private fun connectionIndicator(): TextView = TextView(this).apply {
        val connected = when (syncMode) {
            "VIEWER" -> syncConnected && viewerSocket?.isConnected == true && viewerSocket?.isClosed == false
            "HOST" -> synchronized(hostClients) { hostClients.any { it.isConnected && !it.isClosed } }
            else -> false
        }
        text = if (connected) "● ΣΥΝΔΕΔΕΜΕΝΟ" else "● ΜΗ ΣΥΝΔΕΔΕΜΕΝΟ"
        setTextColor(if (connected) Color.rgb(0,150,70) else Color.rgb(210,35,35))
        textSize = 14f; gravity = Gravity.CENTER; setPadding(0,0,0,10)
    }

    private fun syncStateJson(): String = JSONObject().apply {
        put("team0", teams[0].name); put("team1", teams[1].name)
        put("score0", score[0]); put("score1", score[1]); put("set", currentSet)
        put("servingTeam", servingTeam); put("lastAction", lastActionText)
        put("reception", JSONObject(receptionCounts as Map<*, *>))
        put("receptionRotation", JSONObject(receptionCountsByRotation as Map<*, *>))
        put("matchReception", JSONObject(matchReceptionCounts as Map<*, *>))
        put("matchFront", JSONObject(matchReceptionFrontCounts as Map<*, *>))
        put("matchBack", JSONObject(matchReceptionBackCounts as Map<*, *>))
        put("attackContext", JSONObject(opponentAttackPatternByContext as Map<*, *>))
        put("attackOrigins", JSONObject(opponentAttackOrigins as Map<*, *>))
        put("receptionBack", JSONObject(receptionBackCounts as Map<*, *>))
        put("receptionFront", JSONObject(receptionFrontCounts as Map<*, *>))
        put("receptionPosition", JSONObject(receptionPositionCounts as Map<*, *>))
        put("rotation0", currentRotation[0]); put("rotation1", currentRotation[1])
        for (ti in 0..1) {
            put("roster$ti", JSONArray().apply {
                teams[ti].roster.forEach { p ->
                    put(JSONObject().put("number", p.number).put("name", p.name).put("role", p.role))
                }
            })
            put("selected$ti", JSONArray().apply { selected[ti].forEach { put(it) } })
            put("p1Formation$ti", JSONArray().apply { p1Formation[ti].forEach { put(it) } })
        }
    }.toString()

    private fun broadcastState() {
        if (syncMode != "HOST") return
        val payload = syncStateJson()
        thread {
            synchronized(hostClients) {
                val dead = mutableListOf<Socket>()
                hostClients.forEach { s ->
                    try { PrintWriter(s.getOutputStream(), true).println(payload) }
                    catch (_: Exception) { dead.add(s) }
                }
                dead.forEach { try { it.close() } catch (_: Exception) {}; hostClients.removeAll(dead) }
            }
        }
    }

    private fun startHost() {
        syncMode = "HOST"
        thread {
            try {
                hostServer?.close()
                hostServer = ServerSocket(50505)
                startDiscoveryResponder()
                runOnUiThread { Toast.makeText(this, "HOST ενεργό — αυτόματος εντοπισμός", Toast.LENGTH_LONG).show() }
                while (syncMode == "HOST") {
                    val s = hostServer!!.accept()
                    synchronized(hostClients) { hostClients.add(s) }
                    syncConnected = true
                    runOnUiThread { if (currentScreen == "HOME") showHome() }
                    PrintWriter(s.getOutputStream(), true).println(syncStateJson())
                }
            } catch (_: Exception) {}
        }
    }

    private fun startDiscoveryResponder() {
        thread {
            try {
                discoverySocket?.close()
                discoverySocket = DatagramSocket(discoveryPort).apply { broadcast = true }
                val buffer = ByteArray(256)
                while (syncMode == "HOST") {
                    val packet = DatagramPacket(buffer, buffer.size)
                    discoverySocket!!.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (msg == "VOLLEY_MATCHUP2_FIND_HOST") {
                        val reply = "VOLLEY_MATCHUP2_HOST:50505".toByteArray()
                        discoverySocket!!.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun discoverAndConnectViewer() {
        syncMode = "VIEWER_SEARCH"
        base("VIEWER — ΑΝΑΖΗΤΗΣΗ HOST")
        root.addView(ProgressBar(this))
        root.addView(TextView(this).apply {
            text = "Αναζήτηση HOST στο ίδιο Hotspot/Wi-Fi…"
            gravity = Gravity.CENTER; textSize = 16f; setPadding(0,16,0,16)
        })
        root.addView(button("ΧΕΙΡΟΚΙΝΗΤΗ IP") { showViewerConnect() })
        thread {
            var found: String? = null
            try {
                val socket = DatagramSocket().apply { broadcast = true; soTimeout = 1200 }
                val request = "VOLLEY_MATCHUP2_FIND_HOST".toByteArray()
                repeat(4) {
                    if (found == null) {
                        socket.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), discoveryPort))
                        try {
                            val buf = ByteArray(256)
                            val reply = DatagramPacket(buf, buf.size)
                            socket.receive(reply)
                            val msg = String(reply.data, 0, reply.length)
                            if (msg.startsWith("VOLLEY_MATCHUP2_HOST")) found = reply.address.hostAddress
                        } catch (_: SocketTimeoutException) {}
                    }
                }
                socket.close()
            } catch (_: Exception) {}
            val ip = found
            if (ip != null) connectViewer(ip)
            else runOnUiThread {
                Toast.makeText(this, "Δεν βρέθηκε HOST — δοκίμασε ξανά ή χειροκίνητη IP", Toast.LENGTH_LONG).show()
                showViewerConnect()
            }
        }
    }

    private fun showViewerConnect() {
        currentScreen = "VIEWER_CONNECT"
        base("VIEWER")
        root.addView(TextView(this).apply {
            text = "Σύνδεσε και τα δύο κινητά στο ίδιο Hotspot/Wi-Fi και γράψε την IP του HOST."
            gravity = Gravity.CENTER; textSize = 16f
        })
        val ip = EditText(this).apply { hint = "IP HOST π.χ. 192.168.43.1"; setSingleLine() }
        root.addView(ip)
        root.addView(button("ΣΥΝΔΕΣΗ") { connectViewer(ip.text.toString().trim()) })
        root.addView(button("ΠΙΣΩ") { showHome() })
    }

    private fun connectViewer(ip: String) {
        if (ip.isBlank()) return
        thread {
            try {
                viewerSocket?.close()
                viewerSocket = Socket(ip, 50505)
                syncMode = "VIEWER"
                syncConnected = true
                val reader = BufferedReader(InputStreamReader(viewerSocket!!.getInputStream()))
                while (syncMode == "VIEWER") {
                    val line = reader.readLine() ?: break
                    applyViewerState(JSONObject(line))
                }
                syncConnected = false
                runOnUiThread { if (syncMode == "VIEWER") showViewer() }
            } catch (_: Exception) {
                syncConnected = false
                runOnUiThread { Toast.makeText(this, "VIEWER: Δεν έγινε σύνδεση", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun restoreJsonMap(dst: MutableMap<String, Int>, obj: JSONObject?) {
        if (obj == null) return
        dst.clear()
        obj.keys().forEach { k -> dst[k] = obj.optInt(k) }
    }

    private fun applyViewerState(j: JSONObject) {
        runOnUiThread {
            teams[0].name = j.optString("team0", teams[0].name)
            teams[1].name = j.optString("team1", teams[1].name)
            score[0] = j.optInt("score0"); score[1] = j.optInt("score1")
            currentSet = j.optInt("set", 1); servingTeam = j.optInt("servingTeam", 0)
            lastActionText = j.optString("lastAction", "")
            restoreJsonMap(receptionCounts, j.optJSONObject("reception"))
            restoreJsonMap(receptionCountsByRotation, j.optJSONObject("receptionRotation"))
            restoreJsonMap(matchReceptionCounts, j.optJSONObject("matchReception"))
            restoreJsonMap(matchReceptionFrontCounts, j.optJSONObject("matchFront"))
            restoreJsonMap(matchReceptionBackCounts, j.optJSONObject("matchBack"))
            restoreJsonMap(opponentAttackPatternByContext, j.optJSONObject("attackContext"))
            restoreJsonMap(opponentAttackOrigins, j.optJSONObject("attackOrigins"))
            restoreJsonMap(receptionBackCounts, j.optJSONObject("receptionBack"))
            restoreJsonMap(receptionFrontCounts, j.optJSONObject("receptionFront"))
            restoreJsonMap(receptionPositionCounts, j.optJSONObject("receptionPosition"))
            currentRotation[0] = j.optInt("rotation0", currentRotation[0])
            currentRotation[1] = j.optInt("rotation1", currentRotation[1])
            for (ti in 0..1) {
                j.optJSONArray("roster$ti")?.let { arr ->
                    for (i in 0 until minOf(arr.length(), teams[ti].roster.size)) {
                        arr.optJSONObject(i)?.let { p ->
                            teams[ti].roster[i].number = p.optString("number")
                            teams[ti].roster[i].name = p.optString("name")
                            teams[ti].roster[i].role = p.optString("role")
                        }
                    }
                }
                j.optJSONArray("selected$ti")?.let { arr ->
                    for (i in selected[ti].indices) selected[ti][i] = arr.optInt(i, -1)
                }
                j.optJSONArray("p1Formation$ti")?.let { arr ->
                    for (i in p1Formation[ti].indices) p1Formation[ti][i] = arr.optInt(i, -1)
                }
            }
            when (currentScreen) {
                "PATTERN" -> showServicePattern()
                "ATTACK_PATTERN" -> showAttackPattern()
                "REPORT" -> showPatternsReport()
                else -> showViewer()
            }
        }
    }

    private fun showViewer() {
        currentScreen = "VIEWER"
        base("VIEWER — LIVE")
        root.addView(connectionIndicator())
        root.addView(TextView(this).apply {
            text = "ΣΕΤ $currentSet     ${teams[0].name}  ${score[0]} - ${score[1]}  ${teams[1].name}"
            gravity = Gravity.CENTER; textSize = 22f; setPadding(0,0,0,18)
        })
        root.addView(TextView(this).apply {
            text = "ΤΕΛΕΥΤΑΙΑ ΕΝΕΡΓΕΙΑ: $lastActionText"; gravity = Gravity.CENTER; textSize = 15f
        })
        root.addView(button("ΜΟΤΙΒΟ SERVICE") { showServicePattern() })
        root.addView(button("ΜΟΤΙΒΟ ΕΠΙΘΕΣΗΣ") { showAttackPattern() })
        root.addView(button("MATCHUP") { showMatchup() })
        root.addView(button("REPORT ΜΟΤΙΒΩΝ") { showPatternsReport() })
        root.addView(button("ΕΚΤΥΠΩΣΗ / ΑΠΟΘΗΚΕΥΣΗ PDF") { printMatchReport() })
        root.addView(button("ΑΠΟΣΥΝΔΕΣΗ") {
            syncMode = "OFF"; syncConnected = false; try { viewerSocket?.close() } catch (_: Exception) {}; showHome()
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentScreen) {
            "PATTERN", "ATTACK_PATTERN", "REPORT" -> if (syncMode == "VIEWER") showViewer() else showCourt()
            "COURT", "ROSTER", "ROTATION", "MATCHUP", "VIEWER", "VIEWER_CONNECT" -> showHome()
            else -> AlertDialog.Builder(this)
                .setTitle("Έξοδος")
                .setMessage("Θέλεις να κλείσεις την εφαρμογή;")
                .setPositiveButton("ΝΑΙ") { _, _ -> finish() }
                .setNegativeButton("ΟΧΙ", null)
                .show()
        }
    }

    private fun base(title: String) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        currentScroll = scroll
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, maxOf(ime.bottom, bars.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(scroll)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28,36,28,120)
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text=title; textSize=24f; setTextColor(Color.rgb(32,32,95)); gravity=Gravity.CENTER; setPadding(0,0,0,24)
        })
        setContentView(scroll)
    }

    private fun button(label:String, action:()->Unit)=Button(this).apply { text=label; setOnClickListener { action() } }

    private fun showHome() {
        currentScreen = "HOME"
        base("VOLLEY MATCHUP 3")
        root.addView(TextView(this).apply { text="LIVE ΣΥΝΔΕΣΗ"; textSize=18f; gravity=Gravity.CENTER })
        if (syncMode == "HOST" || syncMode == "VIEWER") root.addView(connectionIndicator())
        root.addView(button("HOST") { startHost() })
        root.addView(button("VIEWER") { discoverAndConnectViewer() })
        root.addView(button("ROSTER — ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ"){ teamIndex=0; showRoster() })
        root.addView(button("ROSTER — ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ"){ teamIndex=1; showRoster() })
        root.addView(button("ΑΡΧΙΚΗ ΠΕΡΙΣΤΡΟΦΗ"){ showRotationSetup() })
        root.addView(button("ΓΗΠΕΔΟ"){ showCourt() })
        root.addView(button("MATCHUP"){ showMatchup() })
        root.addView(button("REPORT ΑΓΩΝΑ / PDF") { showPatternsReport() })
        root.addView(button("ΕΞΑΓΩΓΗ ΟΜΑΔΩΝ"){ exportTeams() })
        root.addView(button("ΕΙΣΑΓΩΓΗ ΟΜΑΔΩΝ"){ importTeams() })
    }

    private fun lineupForRotation(ti: Int, rotation: Int): IntArray {
        val a = p1Formation[ti].clone()
        val steps = when(rotation) { 1->0; 6->1; 5->2; 4->3; 3->4; 2->5; else->0 }
        repeat(steps) {
            val old = a.clone()
            a[5]=old[0]; a[4]=old[5]; a[3]=old[4]; a[2]=old[3]; a[1]=old[2]; a[0]=old[1]
        }
        return a
    }

    private fun showMatchup() {
        currentScreen = "MATCHUP"
        base("MATCHUP")
        val rotations = arrayOf("P1","P6","P5","P4","P3","P2")
        var ourRot = currentRotation[0]
        var oppRot = currentRotation[1]

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ourSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rotations)
            setSelection(rotations.indexOf("P$ourRot"))
            layoutParams = LinearLayout.LayoutParams(0,-2,1f)
        }
        val oppSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, rotations)
            setSelection(rotations.indexOf("P$oppRot"))
            layoutParams = LinearLayout.LayoutParams(0,-2,1f)
        }
        controls.addView(ourSpinner); controls.addView(oppSpinner)
        root.addView(TextView(this).apply { text="ΔΙΚΗ ΜΑΣ ΠΕΡΙΣΤΡΟΦΗ        ΑΝΤΙΠΑΛΗ"; gravity=Gravity.CENTER })
        root.addView(controls)
        val content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(content)

        fun render() {
            content.removeAllViews()
            val rotOrder = intArrayOf(1,6,5,4,3,2)
            fun nextRot(r:Int, steps:Int):Int {
                val ix=rotOrder.indexOf(r)
                return rotOrder[(ix+steps)%6]
            }
            fun matchupRoleAt(t:Int, rotation:Int, pos:Int, startRotation:Int):String? {
                // Special P1 formation applies only to the team that is serving
                // while its selected/current matchup rotation is P1.
                if (t == servingTeam && rotation == 1 && startRotation == 1) {
                    return when(pos) {
                        0 -> "Π"    // P1
                        5 -> "ΑΚ2"  // P6
                        4 -> "Λ"    // P5
                        3 -> "ΑΚ1"  // P4
                        2 -> "Κ2"   // P3
                        1 -> "Δ"    // P2
                        else -> null
                    }
                }
                return attackRoleAt(rotation,pos)
            }
            fun attackPlayer(t:Int, rotation:Int, pos:Int, startRotation:Int):Player? {
                val role=matchupRoleAt(t,rotation,pos,startRotation) ?: return null
                return teams[t].roster.firstOrNull { it.role==role }
            }
            fun label(p:Player?):String = p?.let { (it.name.ifBlank{"—"})+" ("+(it.role.ifBlank{"—"})+")" } ?: "—"

            fun side(title:String, attackTeam:Int, startAttackRot:Int, blockTeam:Int, startBlockRot:Int) {
                content.addView(TextView(this).apply {
                    text=title; textSize=18f; gravity=Gravity.CENTER; setPadding(0,18,0,8)
                })

                // Player-centric MATCHUP: starting from the selected rotations, follow both teams
                // synchronously through the six rotations. For each attacker, total only the
                // rotations in which she is in the front attacking zone P4/P3/P2.
                val playerTotals=linkedMapOf<String,LinkedHashMap<String,Int>>()
                for(step in 0 until 6) {
                    val ar=nextRot(startAttackRot,step)
                    val br=nextRot(startBlockRot,step)
                    for(attackPos in intArrayOf(3,2,1)) {
                        val attacker=attackPlayer(attackTeam,ar,attackPos,startAttackRot) ?: continue
                        val attackerKey=label(attacker)
                        val totals=playerTotals.getOrPut(attackerKey){ linkedMapOf() }

                        val blockPositions = when(attackPos) {
                            3 -> intArrayOf(1,2) // P4 -> P2 + middle
                            2 -> intArrayOf(2)   // middle -> middle
                            1 -> intArrayOf(3,2) // P2 -> P4 + middle
                            else -> intArrayOf()
                        }
                        blockPositions.forEach { bp ->
                            val blocker=attackPlayer(blockTeam,br,bp,startBlockRot) ?: return@forEach
                            val blockerKey=label(blocker)
                            totals[blockerKey]=(totals[blockerKey]?:0)+1
                        }
                    }
                }

                playerTotals.forEach { (attacker,totals) ->
                    content.addView(TextView(this@MainActivity).apply {
                        text=attacker+"\n→ "+totals.entries.joinToString("   ") { "${it.key} ×${it.value}" }
                        textSize=16f; setPadding(0,8,0,8)
                    })
                }
            }
            side("${teams[0].name} — ΣΥΝΟΛΟ ΜΠΡΟΣΤΑ ΘΕΣΕΩΝ",0,ourRot,1,oppRot)
            side("${teams[1].name} — ΣΥΝΟΛΟ ΜΠΡΟΣΤΑ ΘΕΣΕΩΝ",1,oppRot,0,ourRot)
        }
        val listener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent:AdapterView<*>?) {}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                ourRot=rotations[ourSpinner.selectedItemPosition].removePrefix("P").toInt()
                oppRot=rotations[oppSpinner.selectedItemPosition].removePrefix("P").toInt()
                render()
            }
        }
        ourSpinner.onItemSelectedListener=listener; oppSpinner.onItemSelectedListener=listener
        render()
        root.addView(button("ΠΙΣΩ"){showHome()})
    }

    private fun exportTeams() {
        val saved = prefs().getString("saved_teams","{}") ?: "{}"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "VolleyMatchup2-teams-backup.json")
        }
        startActivityForResult(intent, exportTeamsRequest)
    }

    private fun importTeams() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, importTeamsRequest)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            when (requestCode) {
                exportTeamsRequest -> {
                    val saved = prefs().getString("saved_teams","{}") ?: "{}"
                    contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(saved) }
                    Toast.makeText(this,"Οι ομάδες εξήχθησαν",Toast.LENGTH_SHORT).show()
                }
                importTeamsRequest -> {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return
                    val imported = JSONObject(text)
                    val existing = try { JSONObject(prefs().getString("saved_teams","{}") ?: "{}") } catch (_:Exception) { JSONObject() }
                    val keys = imported.keys()
                    var count = 0
                    while (keys.hasNext()) {
                        val key = keys.next()
                        existing.put(key, imported.get(key))
                        count++
                    }
                    prefs().edit().putString("saved_teams",existing.toString()).apply()
                    Toast.makeText(this,"Εισήχθησαν $count ομάδες",Toast.LENGTH_LONG).show()
                    showHome()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this,"Σφάλμα backup: ${e.message ?: "άγνωστο"}",Toast.LENGTH_LONG).show()
        }
    }

    private fun showRoster() {
        currentScreen = "ROSTER"
        base(if(teamIndex==0) "ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ" else "ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ")

        val teamName = EditText(this).apply {
            hint = "Όνομα ομάδας"
            setText(teams[teamIndex].name)
            textSize = 18f
        }
        teamName.addTextChangedListener(simpleWatcher { teams[teamIndex].name = it })
        root.addView(teamName)

        teams[teamIndex].roster.forEachIndexed { i,p ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            val no=EditText(this).apply { hint="No"; setText(p.number); layoutParams=LinearLayout.LayoutParams(0,-2,.7f) }
            val name=EditText(this).apply { hint="Παίκτρια ${i+1}"; setText(p.name); layoutParams=LinearLayout.LayoutParams(0,-2,2.3f) }
            val role=Spinner(this).apply {
                adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,roles)
                val ix=roles.indexOf(p.role); if(ix>=0)setSelection(ix)
                layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            }
            no.addTextChangedListener(simpleWatcher { p.number = it })
            name.addTextChangedListener(simpleWatcher { p.name = it })
            no.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) v.postDelayed({ scrollFieldIntoView(v) }, 250)
            }
            name.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) v.postDelayed({ scrollFieldIntoView(v) }, 250)
            }
            role.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                override fun onItemSelected(a:AdapterView<*>?,v:View?,pos:Int,id:Long){p.role=roles[pos]}
                override fun onNothingSelected(a:AdapterView<*>?){}
            }
            row.addView(no); row.addView(name); row.addView(role); root.addView(row)
        }

        addP1FormationEditor(teamIndex)
        root.addView(button("ΑΠΟΘΗΚΕΥΣΗ ΟΜΑΔΑΣ"){ saveCurrentTeam() })
        root.addView(button("ΦΟΡΤΩΣΗ ΟΜΑΔΑΣ"){ loadTeamDialog() })
        root.addView(button("ΠΙΣΩ"){ showHome() })
    }

    private fun scrollFieldIntoView(view: View) {
        currentScroll.postDelayed({
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            currentScroll.offsetDescendantRectToMyCoords(view, rect)
            currentScroll.smoothScrollBy(0, rect.bottom - currentScroll.height + 140)
        }, 350)
    }

    private fun simpleWatcher(onChange:(String)->Unit)=object:TextWatcher{
        override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
        override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){ onChange(s?.toString().orEmpty()) }
        override fun afterTextChanged(s:Editable?){}
    }

    private fun prefs() = getSharedPreferences("volley_matchup2", MODE_PRIVATE)

    private fun saveCurrentTeam() {
        currentFocus?.clearFocus()
        val team = teams[teamIndex]
        val input = EditText(this).apply {
            hint = "Όνομα ομάδας"
            setText(team.name)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Αποθήκευση ομάδας")
            .setView(input)
            .setPositiveButton("ΑΠΟΘΗΚΕΥΣΗ"){_,_->
                val saveName=input.text.toString().trim()
                if(saveName.isBlank()){
                    Toast.makeText(this,"Γράψε όνομα ομάδας",Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                team.name=saveName
                val all=try{JSONObject(prefs().getString("saved_teams","{}") ?: "{}")}catch(_:Exception){JSONObject()}
                val obj=JSONObject().put("name",team.name)
                val arr=JSONArray()
                team.roster.forEach { p->
                    arr.put(JSONObject().put("number",p.number).put("name",p.name).put("role",p.role))
                }
                obj.put("roster",arr)
                obj.put("p1Formation", JSONArray().apply {
                    p1Formation[teamIndex].forEach { put(it) }
                })
                all.put(saveName,obj)
                prefs().edit().putString("saved_teams",all.toString()).apply()
                Toast.makeText(this,"Η ομάδα αποθηκεύτηκε",Toast.LENGTH_SHORT).show()
                showRoster()
            }
            .setNegativeButton("ΑΚΥΡΟ",null)
            .show()
    }

    private fun loadTeamDialog() {
        val all=try{JSONObject(prefs().getString("saved_teams","{}") ?: "{}")}catch(_:Exception){JSONObject()}
        val names=mutableListOf<String>()
        val keys=all.keys()
        while(keys.hasNext()) names.add(keys.next())
        names.sort()
        if(names.isEmpty()){
            Toast.makeText(this,"Δεν υπάρχουν αποθηκευμένες ομάδες",Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Φόρτωση ομάδας")
            .setItems(names.toTypedArray()){_,which->
                val obj=all.optJSONObject(names[which]) ?: return@setItems
                val team=teams[teamIndex]
                team.name=obj.optString("name",names[which])
                val arr=obj.optJSONArray("roster") ?: JSONArray()
                for(i in 0 until 14){
                    val p=arr.optJSONObject(i)
                    team.roster[i].number=p?.optString("number","").orEmpty()
                    team.roster[i].name=p?.optString("name","").orEmpty()
                    team.roster[i].role=p?.optString("role","").orEmpty()
                }
                val formation = obj.optJSONArray("p1Formation")
                if (formation != null && formation.length() >= 6) {
                    for (pos in 0..5) p1Formation[teamIndex][pos] = formation.optInt(pos, -1)
                } else {
                    buildDefaultP1FromRoles(teamIndex)
                }
                p1Formation[teamIndex].copyInto(selected[teamIndex])
                initialRotation[teamIndex] = 1
                showRoster()
            }
            .setNegativeButton("ΑΚΥΡΟ",null)
            .show()
    }

    private fun showRotationSetup() {
        currentScreen = "ROTATION"
        base("ΑΡΧΙΚΗ ΠΕΡΙΣΤΡΟΦΗ")
        addInitialRotationSelector(0)
        addRotationPreview(0)
        addInitialRotationSelector(1)
        addRotationPreview(1)
        addStartingServiceSelector()
        root.addView(button("ΠΡΟΒΟΛΗ ΣΤΟ ΓΗΠΕΔΟ"){
            applyInitialRotations()
            showCourt()
        })
        root.addView(button("ΠΙΣΩ"){ showHome() })
    }

    private fun addInitialRotationSelector(ti: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 4)
        }
        row.addView(TextView(this).apply {
            text = "Αρχική περιστροφή"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.6f)
        })
        row.addView(Spinner(this).apply {
            val values = arrayOf("P1", "P2", "P3", "P4", "P5", "P6")
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
            setSelection(initialRotation[ti] - 1)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    initialRotation[ti] = pos + 1
                }
                override fun onNothingSelected(a: AdapterView<*>?) {}
            }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        root.addView(row)
    }

    private fun addStartingServiceSelector() {
        root.addView(TextView(this).apply {
            text = "ΠΟΙΑ ΟΜΑΔΑ ΞΕΚΙΝΑ ΜΕ SERVICE;"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0,20,0,6)
        })
        val options = arrayOf(teams[0].name, teams[1].name)
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
            setSelection(servingTeam)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    servingTeam = pos
                }
                override fun onNothingSelected(a: AdapterView<*>?) {}
            }
        })
    }

    private fun applyInitialRotations() {
        for (ti in 0..1) {
            p1Formation[ti].copyInto(selected[ti])
            // The selector names the volleyball rotation directly; it is NOT a count
            // of clockwise steps from P1.
            val steps = when (initialRotation[ti]) {
                1 -> 0
                6 -> 1
                5 -> 2
                4 -> 3
                3 -> 4
                2 -> 5
                else -> 0
            }
            repeat(steps) { rotatePlayersOnly(ti) }
            currentRotation[ti] = initialRotation[ti]
        }
        receptionRecordedThisRally = false
    }

    private fun addP1FormationEditor(ti: Int) {
        root.addView(TextView(this).apply {
            text = "ΒΑΣΙΚΗ P1 — ΑΠΟΘΗΚΕΥΕΤΑΙ ΜΕ ΤΗΝ ΟΜΑΔΑ"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0,24,0,8)
        })
        val labels = teams[ti].roster.mapIndexed { i,p ->
            if (p.name.isBlank()) "${i+1}. —" else "${p.number} ${p.name} (${p.role})"
        }
        for (pos in 0..5) {
            val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text="P${pos+1}"; textSize=16f; layoutParams=LinearLayout.LayoutParams(0,-2,.5f)
            })
            row.addView(Spinner(this).apply {
                adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels)
                val saved=p1Formation[ti][pos]
                if(saved in labels.indices) setSelection(saved)
                onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                    override fun onItemSelected(a:AdapterView<*>?,v:View?,i:Int,id:Long){ p1Formation[ti][pos]=i }
                    override fun onNothingSelected(a:AdapterView<*>?){}
                }
                layoutParams=LinearLayout.LayoutParams(0,-2,3f)
            })
            root.addView(row)
        }
    }

    private fun buildDefaultP1FromRoles(ti: Int) {
        val order = arrayOf("Π","ΑΚ1","Κ1","Δ","ΑΚ2","Κ2")
        order.forEachIndexed { pos, role ->
            p1Formation[ti][pos] = teams[ti].roster.indexOfFirst { it.role == role }
        }
    }

    private fun addRotationPreview(ti: Int) {
        root.addView(TextView(this).apply {
            val base = p1Formation[ti].clone()
            repeat(initialRotation[ti]-1) {
                val old=base.clone()
                base[5]=old[0]; base[4]=old[5]; base[3]=old[4]
                base[2]=old[3]; base[1]=old[2]; base[0]=old[1]
            }
            text = buildString {
                append(teams[ti].name)
                append(" — από αποθηκευμένη P1")
                for (pos in 0..5) {
                    val ix=base[pos]
                    val p=if(ix in 0..13) teams[ti].roster[ix] else null
                    append("\nP${pos+1}: ")
                    append(p?.name?.takeIf { it.isNotBlank() } ?: "—")
                }
            }
            textSize=14f
            setPadding(0,4,0,14)
        })
    }

    private fun addTeamRotation(ti:Int) {
        root.addView(TextView(this).apply { text=teams[ti].name; textSize=18f; setPadding(0,24,0,8) })
        val labels=teams[ti].roster.mapIndexed{i,p->if(p.name.isBlank())"${i+1}. —" else "${p.number} ${p.name} (${p.role})"}
        for(pos in 0..5){
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply { text="P${pos+1}"; textSize=17f; layoutParams=LinearLayout.LayoutParams(0,-2,.5f) })
            row.addView(Spinner(this).apply {
                adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,labels)
                if(selected[ti][pos]>=0)setSelection(selected[ti][pos])
                onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
                    override fun onItemSelected(a:AdapterView<*>?,v:View?,i:Int,id:Long){selected[ti][pos]=i}
                    override fun onNothingSelected(a:AdapterView<*>?){}
                }
                layoutParams=LinearLayout.LayoutParams(0,-2,3f)
            })
            root.addView(row)
        }
    }

    private fun showSubstitutionDialog(ti: Int) {
        val rolesOnCourt = listOf("Π", "ΑΚ1", "ΑΚ2", "Κ1", "Κ2", "Δ", "Λ")
        val roleLabels = rolesOnCourt.map { role ->
            val player = activePlayerForRole(role, ti)
            "$role — ${player?.name?.ifBlank { "Χωρίς όνομα" } ?: "—"}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("ΑΛΛΑΓΗ ΠΑΙΚΤΡΙΑΣ — ΠΟΙΑ ΒΓΑΙΝΕΙ;")
            .setItems(roleLabels) { _, roleIndex ->
                val role = rolesOnCourt[roleIndex]
                val old = activePlayerForRole(role, ti)
                val onCourt = rolesOnCourt.mapNotNull { activePlayerForRole(it, ti) }.toSet()
                val available = teams[ti].roster.withIndex().filter { (_, p) ->
                    p.name.isNotBlank() && p !== old && p !in onCourt
                }
                if (available.isEmpty()) {
                    Toast.makeText(this, "Δεν υπάρχει διαθέσιμη παίκτρια στον πάγκο. Συμπλήρωσε πρώτα το Roster.", Toast.LENGTH_LONG).show()
                } else {
                    AlertDialog.Builder(this).setTitle("ΠΟΙΑ ΜΠΑΙΝΕΙ ΣΤΗ ΘΕΣΗ $role;")
                        .setItems(available.map { (_, p) -> "${p.number} ${p.name}" }.toTypedArray()) { _, choice ->
                            val (idx, incoming) = available[choice]
                            AlertDialog.Builder(this)
                                .setTitle("Επιβεβαίωση αλλαγής")
                                .setMessage("${old?.name ?: role} → ${incoming.name}\nΡόλος: $role\nΧωρίς αλλαγή περιστροφής.")
                                .setPositiveButton("ΑΛΛΑΓΗ") { _, _ ->
                                    if (ti == 0) activeRolePlayers[role] = idx else opponentActiveRolePlayers[role] = idx
                                    val entry = "${teams[ti].name} · ΣΕΤ $currentSet · ${score[0]}-${score[1]} · $role: ${old?.name ?: "—"} → ${incoming.name}"
                                    substitutionHistory.add(entry)
                                    lastActionText = "ΑΛΛΑΓΗ ${teams[ti].name}: ${old?.name ?: role} → ${incoming.name}"
                                    if (ti == 1 && selected[1][0] in teams[1].roster.indices && old === teams[1].roster[selected[1][0]]) selected[1][0] = idx
                                    saveMatchData()
                                    showCourt()
                                }.setNegativeButton("ΑΚΥΡΟ", null).show()
                        }.setNegativeButton("ΑΚΥΡΟ", null).show()
                }
            }.setNegativeButton("ΑΚΥΡΟ", null).show()
    }

    private fun showCourt() {
        saveMatchData()
        if (servingTeam == 0 && ourAttackCountThisRally == 0) receptionRecordedThisRally = true
        broadcastState()
        currentScreen = "COURT"
        base("ΓΗΠΕΔΟ")
        root.addView(TextView(this).apply {
            text = "ΣΕΤ $currentSet     ${teams[0].name}  ${score[0]}  -  ${score[1]}  ${teams[1].name}"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(Color.rgb(32,32,95))
            setPadding(0,0,0,12)
        })
        root.addView(TextView(this).apply {
            text = if (servingTeam == 0) {
                val ix = selected[0][0]
                val server = if (ix in 0..13) teams[0].roster[ix] else null
                val who = server?.let { listOf(it.number, it.name).filter { s -> s.isNotBlank() }.joinToString(" ") }
                    ?.takeIf { it.isNotBlank() } ?: "—"
                "SERVICE: ${teams[0].name}\n🏐 SERVER: $who"
            } else {
                val server = currentOpponentServer()
                val who = server?.let { listOf(it.number, it.name).filter { s -> s.isNotBlank() }.joinToString(" ") }
                    ?.takeIf { it.isNotBlank() } ?: "—"
                "SERVICE: ${teams[1].name}\n🏐 SERVER: $who — P1"
            }
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0,0,0,12)
        })

        root.addView(TextView(this).apply {
            text = "ΤΕΛΕΥΤΑΙΑ ΕΝΕΡΓΕΙΑ: $lastActionText"
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0,0,0,8)
        })
        root.addView(button("↶  ΑΝΑΙΡΕΣΗ") { undoLastAction() })
        root.addView(button("ΑΛΛΑΓΗ ΠΑΙΚΤΡΙΑΣ — ΔΙΚΗ ΜΑΣ") { showSubstitutionDialog(0) })
        root.addView(button("ΑΛΛΑΓΗ ΠΑΙΚΤΡΙΑΣ — ΑΝΤΙΠΑΛΗ") { showSubstitutionDialog(1) })

        if (servingTeam == 1) {
            val server = currentOpponentServer()
            val sKey = server?.let { serverKey(it) }
            val serverLabel = server?.let {
                listOf(it.number, it.name).filter { value -> value.isNotBlank() }.joinToString(" ")
            }?.takeIf { it.isNotBlank() } ?: "SERVER"

            // 1. Current server in her current service rotation.
            val rotationCounts = if (sKey != null) serverSetCounts[sKey] else null
            val rotationTotal = rotationCounts?.values?.sum() ?: 0
            root.addView(patternPanel("1. ΜΟΤΙΒΟ SERVER — ΤΡΕΧΟΥΣΑ ΠΕΡΙΣΤΡΟΦΗ",
                rotationCounts ?: emptyMap(), rotationTotal, if (rotationTotal == 0) "$serverLabel: χωρίς καταγραφή" else serverLabel,
                if (sKey != null) serverRotationBackCounts[sKey] else null,
                if (sKey != null) serverRotationFrontCounts[sKey] else null))

            // 2. Current server across all sets played so far.
            val allServerCounts = if (sKey != null) serverPreviousCounts[sKey] else null
            val allServerTotal = allServerCounts?.values?.sum() ?: 0
            root.addView(patternPanel("2. ΜΟΤΙΒΟ SERVER — ΣΥΝΟΛΟ ΣΕΤ",
                allServerCounts ?: emptyMap(), allServerTotal, if (allServerTotal == 0) "$serverLabel: χωρίς καταγραφή" else serverLabel,
                if (sKey != null) serverPreviousBackCounts[sKey] else null,
                if (sKey != null) serverPreviousFrontCounts[sKey] else null))

            // 3. Opponent team overall across the match.
            val teamTotal = matchReceptionCounts.values.sum()
            root.addView(patternPanel("3. ΜΟΤΙΒΟ ΑΝΤΙΠΑΛΗΣ ΟΜΑΔΑΣ",
                matchReceptionCounts, teamTotal, if (teamTotal == 0) "Χωρίς καταγραφή" else null,
                matchReceptionBackCounts, matchReceptionFrontCounts))
        }
        // Our attack patterns remain visible regardless of which team serves.
        root.addView(ourAttackPanel("1. ΔΙΚΗ ΜΑΣ ΕΠΙΘΕΣΗ — SIDE-OUT", "SIDE_OUT"))
        root.addView(ourAttackPanel("2. ΔΙΚΗ ΜΑΣ ΕΠΙΘΕΣΗ — TRANSITION", "TRANSITION"))
        root.addView(attackRecommendationPanel())

        // Always show the opponent on the upper half of the board.
        addCourtHalf(1, teams[1].name, true)

        root.addView(TextView(this).apply {
            text = "────────────  ΦΙΛΕ  ────────────"
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0,6,0,6)
        })
        // Our team is always shown as a normal six-player court, just like the opponent.
        // Names and roles come from the active rotation and move automatically with rotation.
        addCourtHalf(0, teams[0].name, false)

        root.addView(button("ΠΟΝΤΟΣ ΔΙΚΟΣ ΜΑΣ") { captureForUndo("ΠΟΝΤΟΣ ΔΙΚΟΣ ΜΑΣ"); pointWonBy(0) })
        root.addView(button("ΠΟΝΤΟΣ ΑΝΤΙΠΑΛΟΥ") { captureForUndo("ΠΟΝΤΟΣ ΑΝΤΙΠΑΛΟΥ"); pointWonBy(1) })
        root.addView(button("ΜΟΤΙΒΟ SERVICE") { showServicePattern() })
        root.addView(button("ΜΟΤΙΒΟ ΕΠΙΘΕΣΗΣ") { showAttackPattern() })
        root.addView(button("📄  REPORT ΜΟΤΙΒΩΝ") { showPatternsReport() })
        root.addView(button("ΤΕΛΟΣ ΣΕΤ") { confirmEndSet() })
        root.addView(button("ΚΑΘΑΡΙΣΜΟΣ") { confirmClearMatch() })
        root.addView(button("ΠΙΣΩ"){showHome()})
    }

    private fun confirmClearMatch() {
        AlertDialog.Builder(this)
            .setTitle("Καθαρισμός")
            .setMessage("Να διαγραφούν όλα τα στατιστικά του αγώνα; Τα roster και η αποθηκευμένη P1 δεν θα διαγραφούν.")
            .setPositiveButton("ΝΑΙ") { _, _ ->
                ourAttackStats.clear()
                receptionByPlayerRotation.clear()
                ourAttackCountThisRally = 0
                receptionCounts.clear()
                receptionCountsByRotation.clear()
                receptionBackCounts.clear()
                receptionFrontCounts.clear()
                receptionPositionCounts.clear()
                previousSetCounts.clear()
                previousSetBackCounts.clear()
                previousSetFrontCounts.clear()
                matchReceptionCounts.clear()
                matchReceptionBackCounts.clear()
                matchReceptionFrontCounts.clear()
                completedSetCounts.clear()
                completedSetBackCounts.clear()
                completedSetFrontCounts.clear()
                serverPreviousCounts.clear()
                serverPreviousBackCounts.clear()
                serverPreviousFrontCounts.clear()
                serverSetCounts.clear()
                currentSet = 1
                score[0] = 0
                score[1] = 0
                undoSnapshot = null
                lastActionText = "Καμία ενέργεια"
                Toast.makeText(this, "Τα στατιστικά καθαρίστηκαν", Toast.LENGTH_SHORT).show()
                saveMatchData()
                showCourt()
            }
            .setNegativeButton("ΑΚΥΡΟ", null)
            .show()
    }

    private fun confirmEndSet() {
        AlertDialog.Builder(this)
            .setTitle("Τέλος σετ $currentSet")
            .setMessage("Να αποθηκευτούν οι υποδοχές ως προηγούμενο σετ και να ξεκινήσει το σετ ${currentSet + 1};")
            .setPositiveButton("ΝΑΙ") { _, _ -> endSet() }
            .setNegativeButton("ΑΚΥΡΟ", null)
            .show()
    }

    private fun endSet() {
        previousSetCounts.clear()
        previousSetCounts.putAll(receptionCounts)
        previousSetBackCounts.clear()
        previousSetBackCounts.putAll(receptionBackCounts)
        previousSetFrontCounts.clear()
        previousSetFrontCounts.putAll(receptionFrontCounts)

        receptionCounts.forEach { (role,n) -> completedSetCounts[role] = (completedSetCounts[role] ?: 0) + n }
        receptionBackCounts.forEach { (role,n) -> completedSetBackCounts[role] = (completedSetBackCounts[role] ?: 0) + n }
        receptionFrontCounts.forEach { (role,n) -> completedSetFrontCounts[role] = (completedSetFrontCounts[role] ?: 0) + n }

        receptionCounts.clear()
        receptionCountsByRotation.clear()
        receptionBackCounts.clear()
        receptionFrontCounts.clear()
        receptionPositionCounts.clear()
        serverSetCounts.clear()
        score[0] = 0
        score[1] = 0
        currentSet++
        Toast.makeText(this, "Έναρξη σετ $currentSet", Toast.LENGTH_SHORT).show()
        showCourt()
    }

    private fun pointWonBy(winner: Int) {
        ourAttackCountThisRally = 0
        score[winner]++
        if (winner != servingTeam) {
            rotateTeam(winner)
            servingTeam = winner
        }
        // Every new rally starts with the receiving team in reception formation.
        receptionRecordedThisRally = false
        rallyBallTeam = null
        lastActionWasBlock = false
        lastAttackerTeam = null
        lastAttackerName = null
        lastAttackerPosition = null
        lastAttackerContext = null
        nextAttackContext[0] = null
        nextAttackContext[1] = null
        lastTouchedTeam = null
        lastTouchedPos = null
        lastTouchWasBlock = false
        showCourt()
    }

    private fun rotatePlayersOnly(ti: Int) {
        val old = selected[ti].clone()
        selected[ti][5] = old[0] // P1 -> P6
        selected[ti][4] = old[5] // P6 -> P5
        selected[ti][3] = old[4] // P5 -> P4
        selected[ti][2] = old[3] // P4 -> P3
        selected[ti][1] = old[2] // P3 -> P2
        selected[ti][0] = old[1] // P2 -> P1
    }

    private fun rotateTeam(ti: Int) {
        rotatePlayersOnly(ti)
        currentRotation[ti] = when (currentRotation[ti]) {
            1 -> 6
            6 -> 5
            5 -> 4
            4 -> 3
            3 -> 2
            2 -> 1
            else -> 1
        }
    }

    private var pendingReception: Triple<Player?, String, Int>? = null

    private fun addReceptionView() {
        root.addView(TextView(this).apply {
            text = "${teams[0].name} — ΥΠΟΔΟΧΗ"
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(0,8,0,8)
        })

        val libero = teams[0].roster.firstOrNull { it.role == "Λ" }
        val setterPos = (0..5).firstOrNull { pos ->
            val ix = selected[0][pos]
            ix in 0..13 && teams[0].roster[ix].role == "Π"
        } ?: 0
        val rotation = setterPos + 1

        val receiverRoles = when(rotation) {
            1 -> arrayOf("ΑΚ1","Λ","ΑΚ2")
            2 -> arrayOf("Λ","ΑΚ2","ΑΚ1")
            3 -> arrayOf("ΑΚ2","Λ","ΑΚ1")
            4 -> arrayOf("Λ","ΑΚ1","ΑΚ2")
            5 -> arrayOf("Λ","ΑΚ1","ΑΚ2")
            6 -> arrayOf("ΑΚ1","Λ","ΑΚ2")
            else -> arrayOf("ΑΚ1","Λ","ΑΚ2")
        }

        root.addView(TextView(this).apply {
            text = "ΠΕΡΙΣΤΡΟΦΗ P$rotation — Επίλεξε υποδοχέα και μετά ΜΠΡΟΣΤΑ / ΠΙΣΩ"
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0,0,0,8)
        })

        val receivers = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        receiverRoles.forEach { role ->
            // Resolve the receiver from the six players actually on court first.
            // This keeps the reception board tied to the loaded/selected P1 instead
            // of accidentally showing only the role label.
            val player = if (role == "Λ") {
                libero
            } else {
                selected[0]
                    .asSequence()
                    .map { ix -> teams[0].roster.getOrNull(ix) }
                    .filterNotNull()
                    .firstOrNull { player -> player.role == role }
                    ?: teams[0].roster.firstOrNull { player -> player.role == role }
            }
            receivers.addView(Button(this).apply {
                text = if (player != null && player.name.isNotBlank()) {
                    "${player.name}\n[$role]"
                } else {
                    role
                }
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    pendingReception = Triple(player, role, rotation)
                    Toast.makeText(this@MainActivity, "Επίλεξε ΜΠΡΟΣΤΑ ή ΠΙΣΩ", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0,150,1f))
        }
        root.addView(receivers)

        val frontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        arrayOf(1,2,3).forEach { pos ->
            val ix = selected[0][pos]
            val player = if (ix in 0..13) teams[0].roster[ix] else null
            frontRow.addView(TextView(this).apply {
                text = buildString {
                    append("P${pos+1}")
                    if (player != null) {
                        append("\n")
                        append(player.name.takeIf { it.isNotBlank() } ?: player.role)
                        if (player.role.isNotBlank()) append("\n[${player.role}]")
                    }
                }
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.BLACK)
                setPadding(4,4,4,4)
                background = GradientDrawable().apply {
                    setColor(Color.argb(35,255,255,255))
                    setStroke(2,Color.WHITE)
                }
            }, LinearLayout.LayoutParams(0,150,1f))
        }
        root.addView(frontRow)

        val depthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        depthRow.addView(button("ΜΠΡΟΣΤΑ") { recordPendingReception(true) }, LinearLayout.LayoutParams(0,-2,1f))
        depthRow.addView(button("ΠΙΣΩ") { recordPendingReception(false) }, LinearLayout.LayoutParams(0,-2,1f))
        root.addView(depthRow)

        root.addView(TextView(this).apply {
            text = buildString {
                append("ΚΑΤΑΓΡΑΦΗ ΥΠΟΔΟΧΩΝ")
                arrayOf("ΑΚ1","ΑΚ2","Λ").forEach { role ->
                    val player = teams[0].roster.firstOrNull { it.role == role }
                    val name = player?.name?.takeIf { it.isNotBlank() } ?: role
                    val total = receptionCounts[role] ?: 0
                    val back = receptionBackCounts[role] ?: 0
                    val front = receptionFrontCounts[role] ?: 0
                    append("\n$name: $total σύνολο — $back πίσω — $front μπροστά")
                }
            }
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0,12,0,8)
        })
    }

    private fun recordPendingReception(front: Boolean) {
        val pending = pendingReception
        if (pending == null) {
            Toast.makeText(this, "Επίλεξε πρώτα υποδοχέα", Toast.LENGTH_SHORT).show()
            return
        }
        recordReception(pending.first, pending.second, pending.third, front)
        pendingReception = null
    }

    private fun recordReception(player: Player?, role: String, rotation: Int, front: Boolean, courtPos: Int? = null) {
        val whoForAction = player?.name?.takeIf { it.isNotBlank() } ?: role
        val depthForAction = ""
        captureForUndo("ΥΠΟΔΟΧΗ: $whoForAction")
        receptionCounts[role] = (receptionCounts[role] ?: 0) + 1
        matchReceptionCounts[role] = (matchReceptionCounts[role] ?: 0) + 1
        val server = currentOpponentServer()
        val serverKey = server?.let { serverKey(it) }
        if (serverKey != null) {
            val map = serverPreviousCounts.getOrPut(serverKey) { mutableMapOf() }
            map[role] = (map[role] ?: 0) + 1
            val setMap = serverSetCounts.getOrPut(serverKey) { mutableMapOf() }
            setMap[role] = (setMap[role] ?: 0) + 1
        }
        val playerKey = "P$rotation|$whoForAction"
        receptionByPlayerRotation[playerKey] = (receptionByPlayerRotation[playerKey] ?: 0) + 1
        val key = "P$rotation|$role"
        receptionCountsByRotation[key] = (receptionCountsByRotation[key] ?: 0) + 1
        if (courtPos != null) {
            val posKey = "P$rotation|$role|P${courtPos + 1}"
            receptionCountsByRotation[posKey] = (receptionCountsByRotation[posKey] ?: 0) + 1
        }


        val who = player?.name?.takeIf { it.isNotBlank() } ?: role
        receptionRecordedThisRally = true
        // recordReception() is used for our team reception.
        nextAttackContext[0] = "RECEPTION"
        ourAttackCountThisRally = 0
        Toast.makeText(this, "+1 υποδοχή: $who", Toast.LENGTH_SHORT).show()
        showCourt()
    }

    // A ranking score, not a predicted probability of winning the rally.
    // 70% smoothed attack success + 30% evidence/sample confidence.
    private fun attackRecommendationPanel(): TextView = TextView(this).apply {
        val rotation = teamRotation(0)
        val context = if (servingTeam == 1 && !receptionRecordedThisRally) "SIDE_OUT"
                      else if (servingTeam == 1 && ourAttackCountThisRally == 0) "SIDE_OUT"
                      else "TRANSITION"
        val label = if (context == "SIDE_OUT") "SIDE-OUT" else "TRANSITION"
        val rows = ourAttackStats.entries.filter { (key, _) ->
            val parts = key.split("|")
            parts.size == 6 && parts[0] == context && parts[1] == rotation.toString()
        }.groupBy { (key, _) ->
            val parts = key.split("|")
            "${parts[2]}|${parts[4]}"
        }.map { (who, events) ->
            val n = events.sumOf { it.value }
            val plus = events.filter { it.key.endsWith("|+") }.sumOf { it.value }
            val smoothed = (plus + 2.0) / (n + 4.0)
            val evidence = n.toDouble() / (n + 5.0)
            val score = 100.0 * (0.7 * smoothed + 0.3 * evidence)
            Triple(who, n to plus, score)
        }.sortedWith(compareByDescending<Triple<String, Pair<Int, Int>, Double>> { it.third }
            .thenByDescending { it.second.first }).take(3)
        val content = if (rows.isEmpty()) "Δεν υπάρχουν ακόμη δεδομένα $label στην R$rotation."
                      else rows.mapIndexed { index, (who, counts, score) ->
                          val name = android.text.TextUtils.htmlEncode(who.replace("|", " — "))
                          val n = counts.first
                          val plus = counts.second
                          val caution = if (n < 5) " · περιορισμένα δεδομένα" else ""
                          "${index + 1}. $name — ${"%.1f".format(score)}% · +$plus/$n$caution"
                      }.joinToString("<br>")
        text = Html.fromHtml(
            "<b>ΠΡΟΤΑΣΗ ΕΠΙΘΕΣΗΣ — R$rotation ($label)</b><br>$content<br>" +
            "<small>Βαθμός καταλληλότητας, όχι πιθανότητα πόντου. " +
            "70% εξομαλυμένη επιτυχία, 30% αξιοπιστία δείγματος.</small>",
            Html.FROM_HTML_MODE_LEGACY
        )
        gravity = Gravity.CENTER
        textSize = 15f
        setPadding(8, 12, 8, 12)
        background = GradientDrawable().apply {
            setColor(Color.argb(22, 30, 120, 80))
            setStroke(2, Color.rgb(55, 110, 80))
            cornerRadius = 10f
        }
    }

    private fun ourAttackPanel(title: String, context: String): TextView = TextView(this).apply {
        val rotation = teamRotation(0)
        val entries = ourAttackStats.filterKeys { key ->
            val parts = key.split("|")
            parts.size == 6 && parts[0] == context && parts[1] == rotation.toString()
        }
        val total = entries.values.sum()
        val groups = entries.entries.groupBy { entry ->
            entry.key.split("|").let { parts -> "${parts[2]}|${parts[4]}" }
        }
        val maxCount = groups.values.maxOfOrNull { group -> group.sumOf { it.value } } ?: 0
        val maxSuccess = groups.values.maxOfOrNull { group ->
            val n = group.sumOf { it.value }
            val plus = group.filter { it.key.endsWith("|+") }.sumOf { it.value }
            if (n == 0) 0.0 else plus.toDouble() / n
        } ?: 0.0
        val rows = if (total == 0) "Χωρίς καταγραφή" else groups.entries
            .sortedByDescending { it.value.sumOf { e -> e.value } }
            .joinToString("<br>") { (label, group) ->
                val count = group.sumOf { it.value }
                val plus = group.filter { it.key.endsWith("|+") }.sumOf { it.value }
                val selection = count * 100.0 / total
                val success = plus * 100.0 / count
                val selectPct = "${"%.1f".format(selection)}%"
                val successPct = "${"%.1f".format(success)}%"
                val selectionHtml = if (count == maxCount) "<font color='#D00000'><b>$selectPct</b></font>" else selectPct
                val successHtml = if (plus > 0 && plus.toDouble() / count == maxSuccess) "<font color='#D00000'><b>$successPct</b></font>" else successPct
                "${android.text.TextUtils.htmlEncode(label.replace("|", " "))}: $count/$total ($selectionHtml)  +$plus / -${count - plus}  Επιτυχία $successHtml"
            }
        text = Html.fromHtml("<b>$title — R$rotation</b><br>$rows", Html.FROM_HTML_MODE_LEGACY)
        gravity = Gravity.CENTER
        textSize = 15f
        setPadding(8, 10, 8, 10)
        background = GradientDrawable().apply {
            setColor(Color.argb(15, 30, 30, 70))
            setStroke(2, Color.rgb(32, 32, 95))
            cornerRadius = 10f
        }
    }

    private fun chooseOurAttack(player: Player, role: String, pos: Int) {
        val who = player.name.ifBlank { role }
        AlertDialog.Builder(this)
            .setTitle("Επίθεση: $who — P${pos + 1}")
            .setPositiveButton("+") { _, _ -> saveOurAttack(who, role, pos, true) }
            .setNegativeButton("−") { _, _ -> saveOurAttack(who, role, pos, false) }
            .setNeutralButton("ΑΚΥΡΟ", null)
            .show()
    }

    private fun saveOurAttack(who: String, role: String, pos: Int, success: Boolean) {
        val context = if (servingTeam == 1 && ourAttackCountThisRally == 0) "SIDE_OUT" else "TRANSITION"
        captureForUndo("ΕΠΙΘΕΣΗ $who: ${if (success) "+" else "−"} ($context)")
        val key = "$context|${teamRotation(0)}|P${pos + 1}|$role|$who|${if (success) "+" else "-"}"
        ourAttackStats[key] = (ourAttackStats[key] ?: 0) + 1
        ourAttackCountThisRally++
        lastTouchedTeam = 0
        lastTouchedPos = pos
        receptionRecordedThisRally = true
        if (success) pointWonBy(0) else showCourt()
    }

    private fun currentOpponentServer(): Player? {
        val ix = selected[1][0] // P1 is the serving position
        return if (ix in 0..13) teams[1].roster[ix] else null
    }

    private fun serverKey(player: Player): String =
        player.number.trim() + "|" + player.name.trim()

    private fun attackOriginPanel(title: String, context: String): TextView = TextView(this).apply {
        val rotation = teamRotation(1)
        val prefix = "$context|$rotation|"
        val entries = opponentAttackOrigins.filterKeys { it.startsWith(prefix) }
        val total = entries.values.sum()
        val sorted = entries.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val maxCount = sorted.maxOfOrNull { it.value } ?: 0
        val rows = if (total == 0) "Χωρίς καταγραφή" else sorted.joinToString("<br>") { (key, n) ->
            val p = key.split("|")
            val pct = (n * 100.0 / total).toInt()
            val row = "Από ${p[2]} — ${android.text.TextUtils.htmlEncode(p[3])}: $n ($pct%)"
            if (n == maxCount && maxCount > 0) "<font color='#D00000'><b>$row</b></font>" else row
        }
        text = Html.fromHtml("<b>$title — P$rotation</b><br>$rows", Html.FROM_HTML_MODE_LEGACY)
        gravity = Gravity.CENTER
        textSize = 14f
        setPadding(14, 12, 14, 12)
        background = GradientDrawable().apply {
            setColor(Color.argb(22,32,32,95)); setStroke(2, Color.rgb(32,32,95)); cornerRadius = 14f
        }
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,12) }
    }

    private fun patternHtml(counts: Map<String, Int>, total: Int, backs: Map<String,Int>? = null, fronts: Map<String,Int>? = null): String {
        val roles = arrayOf("ΑΚ1","ΑΚ2","Λ").sortedByDescending { counts[it] ?: 0 }
        val maxCount = roles.maxOfOrNull { counts[it] ?: 0 } ?: 0
        return roles.joinToString("<br>") { role ->
            val n = counts[role] ?: 0
            val pct = if (total == 0) 0 else ((n * 100.0) / total).toInt()
            val name = teams[0].roster.firstOrNull { it.role == role }?.name?.takeIf { it.isNotBlank() } ?: role
            val row = "$name — $n ($pct%)"
            if (maxCount > 0 && n == maxCount) "<font color='#D00000'><b>$row</b></font>" else row
        }
    }

    private fun patternPanel(title: String, counts: Map<String, Int>, total: Int, prefix: String? = null, backs: Map<String,Int>? = null, fronts: Map<String,Int>? = null): TextView =
        TextView(this).apply {
            val html = buildString {
                append("<b>").append(title).append("</b>")
                if (!prefix.isNullOrBlank()) append("<br>").append(prefix)
                append("<br>").append(patternHtml(counts, total, backs, fronts))
            }
            text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(14,12,14,12)
            background = GradientDrawable().apply {
                setColor(Color.argb(22,32,32,95))
                setStroke(2, Color.rgb(32,32,95))
                cornerRadius = 14f
            }
            layoutParams = LinearLayout.LayoutParams(-1,-2).apply { setMargins(0,0,0,12) }
        }

    private fun showServicePattern() {
        currentScreen = "PATTERN"
        base("ΜΟΤΙΒΟ SERVICE")
        val total = receptionCounts.values.sum()
        val servingPlayerIndex = selected[servingTeam][0]
        val servingPlayer = if (servingPlayerIndex in 0..13) teams[servingTeam].roster[servingPlayerIndex] else null
        val servingName = servingPlayer?.name?.takeIf { it.isNotBlank() } ?: "—"
        val servingRole = servingPlayer?.role?.takeIf { it.isNotBlank() }
        root.addView(TextView(this).apply {
            text = buildString {
                append("🏐 SERVICE: ")
                append(servingName)
                if (servingRole != null) append(" ($servingRole)")
                append(" — ")
                append(teams[servingTeam].name)
            }
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(0,0,0,12)
        })
        if (total == 0) {
            root.addView(TextView(this).apply {
                text = "Δεν υπάρχουν ακόμη καταγεγραμμένες υποδοχές."
                gravity = Gravity.CENTER
                textSize = 16f
                setPadding(0,20,0,20)
            })
            root.addView(button("ΠΙΣΩ"){ showCourt() })
            return
        }

        fun pct(n: Int) = ((n * 100.0) / total).toInt()

        root.addView(TextView(this).apply {
            text = "ΣΥΝΟΛΟ SERVICE: $total"
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(0,0,0,16)
        })

        val overallMax = arrayOf("ΑΚ1","ΑΚ2","Λ").maxOf { receptionCounts[it] ?: 0 }
        arrayOf("ΑΚ1","ΑΚ2","Λ")
            .sortedByDescending { receptionCounts[it] ?: 0 }
            .forEach { role ->
            val player = teams[0].roster.firstOrNull { it.role == role }
            val name = player?.name?.takeIf { it.isNotBlank() } ?: role
            val n = receptionCounts[role] ?: 0
            root.addView(TextView(this).apply {
                val row = "$name: $n (${pct(n)}%)"
                text = if (overallMax > 0 && n == overallMax)
                    Html.fromHtml("<font color='#D00000'><b>$row</b></font>", Html.FROM_HTML_MODE_LEGACY)
                else row
                textSize = 16f
                setPadding(0,8,0,8)
            })
        }

        root.addView(TextView(this).apply {
            text = "ΑΝΑ ΠΕΡΙΣΤΡΟΦΗ"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0,20,0,8)
        })
        for (rotation in 1..6) {
            val counts = arrayOf("ΑΚ1","ΑΚ2","Λ").associateWith { role ->
                receptionCountsByRotation["P$rotation|$role"] ?: 0
            }
            // Opponent server for this rotation, derived from the saved P1 lineup
            // using the exact P1 -> P6 -> P5 -> P4 -> P3 -> P2 sequence.
            val serverSteps = when (rotation) {
                1 -> 0
                6 -> 1
                5 -> 2
                4 -> 3
                3 -> 4
                2 -> 5
                else -> 0
            }
            val serverLineup = p1Formation[1].clone()
            repeat(serverSteps) {
                val oldLineup = serverLineup.clone()
                serverLineup[5] = oldLineup[0]
                serverLineup[4] = oldLineup[5]
                serverLineup[3] = oldLineup[4]
                serverLineup[2] = oldLineup[3]
                serverLineup[1] = oldLineup[2]
                serverLineup[0] = oldLineup[1]
            }
            val serverIx = serverLineup[0]
            val serverPlayer = teams[1].roster.getOrNull(serverIx)
            val serverName = serverPlayer?.name?.takeIf { it.isNotBlank() } ?: "—"
            val rotTotal = counts.values.sum()
            val maxInRotation = counts.values.maxOrNull() ?: 0
            val detail = if (rotTotal == 0) "χωρίς καταγραφή" else
                counts.entries.joinToString(" — ") { (role,n) ->
                    val playerName = teams[0].roster
                        .firstOrNull { it.role == role }
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                        ?: role
                    val posCounts = (1..6).associateWith { p -> receptionCountsByRotation["P$rotation|$role|P$p"] ?: 0 }
                    val receiverPos = posCounts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key
                    val posText = receiverPos?.let { "P$it" } ?: receptionRoles(rotation).indexOf(role).let { index -> if (index >= 0) listOf("P1", "P6", "P5")[index] else "—" }
                    val row = "$playerName ($posText) $n (${((n * 100.0) / rotTotal).toInt()}%)"
                    if (maxInRotation > 0 && n == maxInRotation) "<font color='#D00000'><b>$row</b></font>" else row
                }
            root.addView(TextView(this).apply {
                text = if (rotTotal == 0) "P$rotation — SERVICE: $serverName: $detail"
                else Html.fromHtml("<b>P$rotation — SERVICE: $serverName:</b> $detail", Html.FROM_HTML_MODE_LEGACY)
                textSize = 15f
                setPadding(0,5,0,5)
            })
        }

        root.addView(button("REPORT"){ showPatternsReport() })
        root.addView(button("ΠΙΣΩ"){ showCourt() })
    }

    private fun printMatchReport() {
        val escape: (String) -> String = { android.text.TextUtils.htmlEncode(it) }
        val html = StringBuilder()
        html.append("<html><head><meta charset='UTF-8'><style>")
        html.append("@page{size:A4;margin:8mm}body{font-family:sans-serif;color:#222;margin:0;padding:0;font-size:9px}h1{font-size:15px;margin:0 0 3px}h2{font-size:11px;margin:7px 0 2px;page-break-after:avoid}h3{font-size:10px;margin:4px 0 1px;page-break-after:avoid}p{margin:2px 0 4px}table{border-collapse:collapse;width:100%;font-size:9px;margin:1px 0 4px;page-break-inside:auto}tr{page-break-inside:avoid}td,th{border:1px solid #bbb;padding:2px 3px;text-align:left;line-height:1.12}th{background:#eee}.small{color:#555;font-size:8px}b[style*='color']{color:#c00000!important;-webkit-print-color-adjust:exact}")
        html.append("</style></head><body>")
        html.append("<h1>VOLLEY MATCHUP 3 — REPORT ΑΓΩΝΑ</h1>")
        html.append("<p>${escape(teams[0].name)} ${score[0]} - ${score[1]} ${escape(teams[1].name)} · ΣΕΤ $currentSet</p>")
        html.append("<h2>ΕΠΙΘΕΣΕΙΣ ΑΝΑ ΠΕΡΙΣΤΡΟΦΗ</h2>")
        for (rotation in listOf(1,6,5,4,3,2,0)) {
            html.append("<h2>" + (if(rotation == 0) "ΣΥΝΟΛΟ ΑΓΩΝΑ" else "ΠΕΡΙΣΤΡΟΦΗ R$rotation") + "</h2>")
            for (context in listOf("SIDE_OUT", "TRANSITION", "ALL")) {
                val label = if(context == "ALL") "ΣΥΝΟΛΟ" else if(context == "SIDE_OUT") "SIDE-OUT" else "TRANSITION"
                val entries = ourAttackStats.entries.filter { (key, _) ->
                    val p = key.split("|")
                    p.size == 6 && (rotation == 0 || p[1] == rotation.toString()) && (context == "ALL" || p[0] == context)
                }
                val grouped = entries.groupBy { (key, _) -> key.split("|").let { p -> p[2] + "|" + p[4] } }
                val total = entries.sumOf { it.value }
                val rows = grouped.map { (who, attacks) ->
                    val n = attacks.sumOf { it.value }
                    val plus = attacks.filter { it.key.endsWith("|+") }.sumOf { it.value }
                    Triple(who, n, plus)
                }.sortedByDescending { it.second }
                if (rows.isEmpty()) continue
                html.append("<h3>$label</h3>")
                val maxN = rows.maxOf { it.second }
                val maxSuccess = rows.maxOf { if (it.second == 0) 0.0 else it.third.toDouble() / it.second }
                html.append("<table><tr><th>Ζώνη / Παίκτρια</th><th>Επιθέσεις</th><th>Επιλογή</th><th>+</th><th>−</th><th>Επιτυχία</th></tr>")
                rows.forEach { (who,n,plus) ->
                    val selection = "%.1f%%".format(java.util.Locale.US, n * 100.0 / total)
                    val success = "%.1f%%".format(java.util.Locale.US, plus * 100.0 / n)
                    val selectionHtml = if(n == maxN) "<b style='color:#c00000'>$selection</b>" else selection
                    val successHtml = if(plus > 0 && plus.toDouble()/n == maxSuccess) "<b style='color:#c00000'>$success</b>" else success
                    html.append("<tr><td>${escape(who.replace("|", " — "))}</td><td>$n</td><td>$selectionHtml</td><td>$plus</td><td>${n-plus}</td><td>$successHtml</td></tr>")
                }
                html.append("</table>")
            }
        }
        html.append("<p>Κόκκινο: υψηλότερη επιλογή και υψηλότερη θετική επιτυχία, ανεξάρτητα.</p>")
        html.append("<h2>ΑΛΛΑΓΕΣ ΠΑΙΚΤΡΙΩΝ</h2>")
        if (substitutionHistory.isEmpty()) html.append("<p>Δεν έχουν καταγραφεί αλλαγές.</p>")
        else substitutionHistory.forEach { html.append("<p>${escape(it)}</p>") }
        html.append("<h2>ΥΠΟΔΟΧΕΣ ΑΝΑ ΠΕΡΙΣΤΡΟΦΗ</h2>")
        val receptionRolesList = listOf("ΑΚ1", "Λ", "ΑΚ2")
        val matchTotalsByRole = receptionRolesList.associateWith { role ->
            // Use the match-level counters, which include completed sets.
            matchReceptionCounts[role] ?: 0
        }
        for (rotation in listOf(1, 6, 5, 4, 3, 2)) {
            val rolesInFormation = receptionRoles(rotation)
            val rows = rolesInFormation.mapIndexed { index, role ->
                val playerName = teams[0].roster.firstOrNull { it.role == role }?.name
                    ?.takeIf { it.isNotBlank() } ?: role
                val count = receptionCountsByRotation["P$rotation|$role"] ?: 0
                val actualPosition = (1..6).maxByOrNull { p ->
                    receptionCountsByRotation["P$rotation|$role|P$p"] ?: 0
                }?.takeIf { p -> (receptionCountsByRotation["P$rotation|$role|P$p"] ?: 0) > 0 }
                val position = actualPosition?.let { "P$it" }
                    ?: listOf("P1", "P6", "P5").getOrElse(index) { "—" }
                Triple("$playerName — $position", count, role)
            }
            val total = rows.sumOf { it.second }
            val maxCount = rows.maxOfOrNull { it.second } ?: 0
            html.append("<h3>ΠΕΡΙΣΤΡΟΦΗ R$rotation</h3>")
            html.append("<table><tr><th>Παίκτρια / Θέση</th><th>Υποδοχές</th><th>Ποσοστό</th></tr>")
            rows.sortedByDescending { it.second }.forEach { (name, count, _) ->
                val pct = if (total == 0) "0.0%" else "%.1f%%".format(java.util.Locale.US, count * 100.0 / total)
                val formatted = if (count > 0 && count == maxCount) "<b style='color:#c00000'>$pct</b>" else pct
                html.append("<tr><td>${escape(name)}</td><td>$count</td><td>$formatted</td></tr>")
            }
            html.append("<tr><td><b>Σύνολο R$rotation</b></td><td><b>$total</b></td><td><b>${if (total > 0) "100.0%" else "0.0%"}</b></td></tr></table>")
        }
        html.append("<h2>ΣΥΝΟΛΟ ΑΓΩΝΑ — ΥΠΟΔΟΧΕΣ</h2>")
        html.append("<table><tr><th>Παίκτρια</th><th>Υποδοχές</th><th>Ποσοστό</th></tr>")
        val matchTotal = matchTotalsByRole.values.sum()
        val matchMax = matchTotalsByRole.values.maxOrNull() ?: 0
        matchTotalsByRole.entries.sortedByDescending { it.value }.forEach { (role, count) ->
            val name = teams[0].roster.firstOrNull { it.role == role }?.name?.takeIf { it.isNotBlank() } ?: role
            val pct = if (matchTotal == 0) "0.0%" else "%.1f%%".format(java.util.Locale.US, count * 100.0 / matchTotal)
            val formatted = if (count > 0 && count == matchMax) "<b style='color:#c00000'>$pct</b>" else pct
            html.append("<tr><td>${escape(name)}</td><td>$count</td><td>$formatted</td></tr>")
        }
        html.append("<tr><td><b>Σύνολο αγώνα</b></td><td><b>$matchTotal</b></td><td><b>${if (matchTotal > 0) "100.0%" else "0.0%"}</b></td></tr>")
        html.append("</table><p class='small'>Τα δεδομένα προέρχονται από τις καταγραφές της εφαρμογής. Επιλέξτε «Αποθήκευση ως PDF» στο μενού εκτύπωσης.</p></body></html>")
        val webView = android.webkit.WebView(this)
        webView.settings.defaultTextEncodingName = "UTF-8"
        val printManager = getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                printManager.print("VolleyMatchup3_Report_Set$currentSet",
                    webView.createPrintDocumentAdapter("VolleyMatchup3_Report"),
                    android.print.PrintAttributes.Builder()
                        .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                        .build())
            }
        }
        webView.loadDataWithBaseURL(null, html.toString(), "text/html", "UTF-8", null)
    }

    private fun showPatternsReport() {
        currentScreen = "REPORT"
        base("REPORT ΜΟΤΙΒΩΝ")
        root.addView(TextView(this).apply {
            text = "REPORT ΑΓΩΝΑ\n${teams[0].name}  ${score[0]} - ${score[1]}  ${teams[1].name}\nΣΕΤ $currentSet"
            gravity = Gravity.CENTER; textSize = 22f; setPadding(0,0,0,18)
        })

        root.addView(TextView(this).apply {
            text = "SERVICE — ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ"
            textSize = 19f; gravity = Gravity.CENTER; setPadding(0,12,0,8)
        })
        val serviceTotal = matchReceptionCounts.values.sum()
        if (serviceTotal == 0) {
            root.addView(TextView(this).apply { text="Χωρίς καταγραφές service."; gravity=Gravity.CENTER })
        } else {
            arrayOf("ΑΚ1","ΑΚ2","Λ").sortedByDescending { matchReceptionCounts[it] ?: 0 }.forEach { role ->
                val n=matchReceptionCounts[role]?:0
                val pct=((n*100.0)/serviceTotal).toInt()
                val name=teams[0].roster.firstOrNull{it.role==role}?.name?.takeIf{it.isNotBlank()}?:role
                root.addView(TextView(this).apply {
                    text="$name: $n/$serviceTotal ($pct%)"
                    textSize=15f; setPadding(0,4,0,4)
                })
            }
            root.addView(TextView(this).apply { text="ΑΝΑ ΠΕΡΙΣΤΡΟΦΗ"; textSize=17f; setPadding(0,14,0,6) })
            arrayOf(1,6,5,4,3,2).forEach { r ->
                val counts=arrayOf("ΑΚ1","ΑΚ2","Λ").associateWith { role -> receptionCountsByRotation["P$r|$role"]?:0 }
                val total=counts.values.sum()

                // Find the opponent server for this rotation from the saved P1 lineup.
                val steps=when(r){1->0;6->1;5->2;4->3;3->4;2->5;else->0}
                val lineup=p1Formation[1].clone()
                repeat(steps) {
                    val old=lineup.clone()
                    lineup[5]=old[0]; lineup[4]=old[5]; lineup[3]=old[4]
                    lineup[2]=old[3]; lineup[1]=old[2]; lineup[0]=old[1]
                }
                val server=teams[1].roster.getOrNull(lineup[0])
                val serverName=server?.name?.takeIf{it.isNotBlank()} ?: "—"

                val targets=counts.entries.filter{it.value>0}.sortedByDescending{it.value}.joinToString(" — ") { (role,n) ->
                    val receiver=teams[0].roster.firstOrNull{it.role==role}
                    val receiverName=receiver?.name?.takeIf{it.isNotBlank()} ?: role
                    val pct=if(total==0) 0 else (n*100.0/total).toInt()
                    val posCounts=(1..6).associateWith { p -> receptionCountsByRotation["P$r|$role|P$p"] ?: 0 }
                    val receiverPos=posCounts.maxByOrNull { it.value }?.takeIf { it.value>0 }?.key
                    val posText=receiverPos?.let{"P$it"} ?: role
                    "$receiverName ($posText) $n ($pct%)"
                }
                val line=if(total==0) "P$r $serverName: χωρίς καταγραφή"
                    else "P$r $serverName σημαδεύει στο $targets"
                root.addView(TextView(this).apply { text=line; textSize=14f; setPadding(0,3,0,3) })
            }
        }

        fun attackSection(context:String,title:String) {
            root.addView(TextView(this).apply { text=title; textSize=19f; gravity=Gravity.CENTER; setPadding(0,18,0,8) })
            var any=false
            arrayOf(1,6,5,4,3,2).forEach { rotation ->
                val prefix="$context|$rotation|"
                val entries=opponentAttackPatternByContext.filterKeys{it.startsWith(prefix)}
                if(entries.isNotEmpty()) {
                    any=true
                    root.addView(TextView(this).apply { text="ΠΕΡΙΣΤΡΟΦΗ P$rotation"; textSize=16f; setPadding(0,8,0,3) })
                    val grouped=entries.entries.groupBy { val p=it.key.split("|"); "${p[2]}|${p[3]}" }
                    grouped.forEach { (ak,attacks) ->
                        val ap=ak.split("|"); val counts=mutableMapOf<String,Int>()
                        attacks.forEach { e -> val target=e.key.split("|")[4]; counts[target]=(counts[target]?:0)+e.value }
                        val total=counts.values.sum()
                        val detail=arrayOf("P1","P6","P5","P4","P3","P2").filter{(counts[it]?:0)>0}
                            .joinToString(" — "){p->"$p ${counts[p]}/$total"}
                        root.addView(TextView(this).apply { text="${ap[0]} (${ap[1]}): $detail"; textSize=14f; setPadding(0,3,0,3) })
                    }
                }
            }
            if(!any) root.addView(TextView(this).apply { text="Χωρίς καταγραφές."; gravity=Gravity.CENTER })
        }
        attackSection("RECEPTION","ΕΠΙΘΕΣΗ ΑΝΤΙΠΑΛΟΥ ΜΕΤΑ ΑΠΟ ΥΠΟΔΟΧΗ")
        attackSection("DEFENSE","ΕΠΙΘΕΣΗ ΑΝΤΙΠΑΛΟΥ ΜΕΤΑ ΑΠΟ ΑΜΥΝΑ")
        root.addView(button("REPORT"){ showPatternsReport() })
        root.addView(button("ΕΚΤΥΠΩΣΗ / ΑΠΟΘΗΚΕΥΣΗ PDF") { printMatchReport() })
        root.addView(button("ΠΙΣΩ"){ showCourt() })
    }

    private fun showAttackPattern() {
        currentScreen = "ATTACK_PATTERN"
        base("ΜΟΤΙΒΟ ΕΠΙΘΕΣΗΣ — ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ")
        for (rotation in listOf(1,6,5,4,3,2,0)) {
            root.addView(TextView(this).apply {
                text = if (rotation == 0) "ΟΛΟΣ Ο ΑΓΩΝΑΣ" else "ΠΕΡΙΣΤΡΟΦΗ R$rotation"
                textSize = 20f
                setPadding(0,18,0,8)
            })
            for (context in listOf("SIDE_OUT","TRANSITION","ΣΥΝΟΛΟ")) {
                val entries = ourAttackStats.filterKeys { key ->
                    val p = key.split("|")
                    (rotation == 0 || p[1] == rotation.toString()) &&
                    (context == "ΣΥΝΟΛΟ" || p[0] == context)
                }
                val grouped = entries.entries.groupBy { it.key.split("|").let { p -> "${p[2]}|${p[4]}" } }
                val total = entries.values.sum()
                val maxCount = grouped.values.maxOfOrNull { group -> group.sumOf { it.value } } ?: 0
                val maxSuccess = grouped.values.maxOfOrNull { group ->
                    val n = group.sumOf { it.value }
                    val plus = group.filter { it.key.endsWith("|+") }.sumOf { it.value }
                    if (n == 0) 0.0 else plus.toDouble() / n
                } ?: 0.0
                root.addView(TextView(this).apply {
                    text = context
                    textSize = 17f
                    setPadding(0,8,0,4)
                })
                if (total == 0) root.addView(TextView(this).apply { text = "Χωρίς καταγραφές" })
                grouped.forEach { (label, group) ->
                    val n = group.sumOf { it.value }
                    val plus = group.filter { it.key.endsWith("|+") }.sumOf { it.value }
                    val selection = n * 100.0 / total
                    val success = plus * 100.0 / n
                    val selectPct = "${"%.1f".format(selection)}%"
                    val successPct = "${"%.1f".format(success)}%"
                    val selectionHtml = if (n == maxCount) "<font color='#D00000'><b>$selectPct</b></font>" else selectPct
                    val successHtml = if (plus > 0 && plus.toDouble() / n == maxSuccess) "<font color='#D00000'><b>$successPct</b></font>" else successPct
                    val safeName = android.text.TextUtils.htmlEncode(label.replace("|", " "))
                    val line = "$safeName — $n ($selectionHtml) — +$plus / −${n-plus} — Επιτυχία $successHtml"
                    root.addView(TextView(this).apply {
                        text = Html.fromHtml(line, Html.FROM_HTML_MODE_LEGACY)
                        textSize = 15f
                        setPadding(0,4,0,4)
                    })
                }
            }
        }
        root.addView(button("ΠΙΣΩ") { showCourt() })
    }

    private fun receptionRoles(rotation: Int): Array<String> = when(rotation) {
        1 -> arrayOf("ΑΚ1","Λ","ΑΚ2")
        2 -> arrayOf("Λ","ΑΚ2","ΑΚ1")
        3 -> arrayOf("ΑΚ2","Λ","ΑΚ1")
        4 -> arrayOf("Λ","ΑΚ1","ΑΚ2")
        5 -> arrayOf("Λ","ΑΚ1","ΑΚ2")
        6 -> arrayOf("ΑΚ1","Λ","ΑΚ2")
        else -> arrayOf("ΑΚ1","Λ","ΑΚ2")
    }

    private fun attackRoleAt(rotation: Int, pos: Int): String? {
        // Exact attack formations supplied by the coach.
        // Array order is physical court position P1,P2,P3,P4,P5,P6.
        val formation = when (rotation) {
            1 -> arrayOf("Π","ΑΚ1","Κ2","Δ","Λ","ΑΚ2")
            6 -> arrayOf("Π","Δ","Κ2","ΑΚ2","Λ","ΑΚ1")
            5 -> arrayOf("Π","Δ","Κ1","ΑΚ2","Κ2","ΑΚ1")
            4 -> arrayOf("Δ","Π","Κ1","ΑΚ2","Λ","ΑΚ1")
            3 -> arrayOf("Δ","Π","Κ1","ΑΚ1","Λ","ΑΚ2")
            2 -> arrayOf("Δ","Π","Κ2","ΑΚ1","Κ1","ΑΚ2")
            else -> return null
        }
        return formation[pos]
    }

    private fun teamRotation(ti: Int): Int = currentRotation[ti]

    private fun addCourtHalf(ti:Int,title:String,opponent:Boolean){
        val receiving = servingTeam != ti
        val showReceptionFormation = receiving && !receptionRecordedThisRally
        root.addView(TextView(this).apply {
            text = if (showReceptionFormation) "$title — ΥΠΟΔΟΧΗ" else if (receiving) "$title — ΕΠΙΘΕΣΗ" else title
            gravity=Gravity.CENTER; textSize=18f; setPadding(0,8,0,8)
        })
        val court=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(8,8,8,8)
            background=GradientDrawable().apply { setColor(Color.rgb(238,177,104)); setStroke(3,Color.WHITE) }
        }

        val rotation = teamRotation(ti)
        val reception = receptionRoles(rotation)
        val libero = activePlayerForRole("Λ", ti)

        // Visual orientation:
        // opponent: P1 P6 P5 / P2 P3 P4
        // ours (180°): P4 P3 P2 / P5 P6 P1
        val rows = if (opponent) {
            // Opponent orientation exactly as defined:
            // top left -> right: P1, P6, P5
            // bottom right -> left: P4, P3, P2
            // therefore screen left -> right on bottom: P2, P3, P4.
            arrayOf(intArrayOf(0,5,4), intArrayOf(1,2,3))
        } else {
            // Our court remains rotated 180 degrees.
            arrayOf(intArrayOf(3,2,1), intArrayOf(4,5,0))
        }

        rows.forEach { positions ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
            positions.forEach { p ->
                // When receiving, P1/P6/P5 show the agreed reception formation.
                val receptionIndex = when(p) { 0 -> 0; 5 -> 1; 4 -> 2; else -> -1 }
                val receptionRole = if (showReceptionFormation && receptionIndex >= 0) reception[receptionIndex] else null
                // During reception show ONLY the three receivers (AK1, AK2, libero).
                // All other court cells stay empty until reception is recorded.
                val hideDuringReception = showReceptionFormation && receptionIndex < 0
                // Special serving formation only for rotation P1:
                // P1=Π, P6=ΑΚ2, P5=Λ, P4=ΑΚ1, P3=Κ2, P2=Δ.
                val servingP1Role = if (!receiving && rotation == 1) {
                    when (p) {
                        0 -> "Π"
                        5 -> "ΑΚ2"
                        4 -> "Λ"
                        3 -> "ΑΚ1"
                        2 -> "Κ2"
                        1 -> "Δ"
                        else -> null
                    }
                } else null
                // Serving team goes directly to its attack formation.
                // Receiving team goes to attack only after reception is recorded.
                val attackRole = if (!receiving || receptionRecordedThisRally) attackRoleAt(rotation, p) else null
                // Keep the special P1 serving variation as the only override.
                val baseDisplayRole = receptionRole ?: servingP1Role ?: attackRole
                // Libero substitution rule:
                // in back-row positions P1/P6/P5, K1 or K2 is replaced by libero,
                // except while that middle is actually serving from P1.
                val backRow = p == 0 || p == 5 || p == 4
                val middleServing = !receiving && p == 0 && baseDisplayRole in arrayOf("Κ1","Κ2")
                val displayRole = if (
                    backRow &&
                    baseDisplayRole in arrayOf("Κ1","Κ2") &&
                    !middleServing
                ) "Λ" else baseDisplayRole
                val displayPlayer = if (displayRole == "Λ") libero else if (displayRole != null) {
                    activePlayerForRole(displayRole, ti)
                } else null
                row.addView(
                    if (hideDuringReception) emptyCourtBox() else playerBox(ti,p,displayPlayer,displayRole,showReceptionFormation),
                    LinearLayout.LayoutParams(0,150,1f)
                )
            }
            court.addView(row)
        }
        root.addView(court)
    }

    private fun emptyCourtBox() = TextView(this).apply {
        text = ""
        gravity = Gravity.CENTER
        setPadding(4,4,4,4)
        background = GradientDrawable().apply {
            setColor(Color.argb(35,255,255,255))
            setStroke(2,Color.WHITE)
        }
    }

    private fun playerBox(
        ti:Int,
        pos:Int,
        overridePlayer: Player? = null,
        overrideRole: String? = null,
        receiving: Boolean = false
    )=TextView(this).apply {
        val ix=selected[ti][pos]
        val normalPlayer=if(ix in 0..13)teams[ti].roster[ix] else null
        val player=overridePlayer ?: normalPlayer
        val role=overrideRole ?: player?.role
        val isLastTouched = lastTouchedTeam == ti && lastTouchedPos == pos
        val isServerMarker = !receptionRecordedThisRally && servingTeam == ti && pos == 0
        text=buildString {
            append("P${pos+1}")
            if (isServerMarker) append("   🏐")
            else if (isLastTouched) append("  🏐")
            if(player!=null && player.name.isNotBlank()){
                append("\n"); append(player.name)
                if(!role.isNullOrBlank()) append("  [$role]")
            } else if (!role.isNullOrBlank()) {
                append("\n"); append(role)
            }
        }
        if (isServerMarker) textSize = 16f
        gravity=Gravity.CENTER; textSize=14f; setTextColor(Color.BLACK); setPadding(4,4,4,4)
        background=GradientDrawable().apply { setColor(Color.argb(35,255,255,255)); setStroke(2,Color.WHITE) }

        if (ti == 0 && !receiving && player != null && role != "Λ" && role != "Π") {
            setOnClickListener { chooseOurAttack(player, role ?: "", pos) }
        }
        if (ti == 0 && receiving && overrideRole in arrayOf("ΑΚ1","ΑΚ2","Λ") && player != null) {
            setOnClickListener {
                lastTouchedTeam = 0
                lastTouchedPos = pos
                recordReception(player, overrideRole!!, teamRotation(0), false, pos)
            }
        }

    }
}

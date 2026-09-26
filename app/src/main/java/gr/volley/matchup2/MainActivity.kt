package gr.volley.matchup2

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
    private val initialRotation = intArrayOf(1, 1)
    private val receptionCounts = mutableMapOf<String, Int>()
    private val receptionCountsByRotation = mutableMapOf<String, Int>()
    private val receptionBackCounts = mutableMapOf<String, Int>()
    private val receptionFrontCounts = mutableMapOf<String, Int>()
    private val receptionPositionCounts = mutableMapOf<String, Int>()
    private var currentSet = 1
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
    private lateinit var root: LinearLayout
    private lateinit var currentScroll: ScrollView
    private var currentScreen = "HOME"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (currentScreen) {
            "PATTERN" -> showCourt()
            "COURT", "ROSTER", "ROTATION" -> showHome()
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
        base("VOLLEY MATCHUP 2")
        root.addView(button("ROSTER — ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ"){ teamIndex=0; showRoster() })
        root.addView(button("ROSTER — ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ"){ teamIndex=1; showRoster() })
        root.addView(button("ΑΡΧΙΚΗ ΠΕΡΙΣΤΡΟΦΗ"){ showRotationSetup() })
        root.addView(button("ΓΗΠΕΔΟ"){ showCourt() })
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

    private fun applyInitialRotations() {
        for (ti in 0..1) {
            p1Formation[ti].copyInto(selected[ti])
            repeat(initialRotation[ti] - 1) {
                rotateTeam(ti)
            }
        }
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

    private fun showCourt() {
        currentScreen = "COURT"
        base("ΓΗΠΕΔΟ")
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
                rotationCounts ?: emptyMap(), rotationTotal, if (rotationTotal == 0) "$serverLabel: χωρίς καταγραφή" else serverLabel))

            // 2. Current server across all sets played so far.
            val allServerCounts = if (sKey != null) serverPreviousCounts[sKey] else null
            val allServerTotal = allServerCounts?.values?.sum() ?: 0
            root.addView(patternPanel("2. ΜΟΤΙΒΟ SERVER — ΣΥΝΟΛΟ ΣΕΤ",
                allServerCounts ?: emptyMap(), allServerTotal, if (allServerTotal == 0) "$serverLabel: χωρίς καταγραφή" else serverLabel))

            // 3. Opponent team overall across the match.
            val teamTotal = matchReceptionCounts.values.sum()
            root.addView(patternPanel("3. ΜΟΤΙΒΟ ΑΝΤΙΠΑΛΗΣ ΟΜΑΔΑΣ",
                matchReceptionCounts, teamTotal, if (teamTotal == 0) "Χωρίς καταγραφή" else null))
        }

        if (servingTeam == 1) {
            addReceptionView()
        } else {
            addCourtHalf(0, teams[0].name, false)
        }

        root.addView(button("ΠΟΝΤΟΣ ΔΙΚΟΣ ΜΑΣ") { pointWonBy(0) })
        root.addView(button("ΠΟΝΤΟΣ ΑΝΤΙΠΑΛΟΥ") { pointWonBy(1) })
        root.addView(button("ΜΟΤΙΒΟ SERVICE") { showServicePattern() })
        root.addView(button("ΤΕΛΟΣ ΣΕΤ") { confirmEndSet() })
        root.addView(button("ΚΑΘΑΡΙΣΜΟΣ") { confirmClearMatch() })
        root.addView(button("ΠΙΣΩ"){showHome()})
    }

    private fun confirmClearMatch() {
        AlertDialog.Builder(this)
            .setTitle("Καθαρισμός")
            .setMessage("Να διαγραφούν όλα τα στατιστικά του αγώνα; Τα roster και η αποθηκευμένη P1 δεν θα διαγραφούν.")
            .setPositiveButton("ΝΑΙ") { _, _ ->
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
                Toast.makeText(this, "Τα στατιστικά καθαρίστηκαν", Toast.LENGTH_SHORT).show()
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
        currentSet++
        Toast.makeText(this, "Έναρξη σετ $currentSet", Toast.LENGTH_SHORT).show()
        showCourt()
    }

    private fun pointWonBy(winner: Int) {
        if (winner != servingTeam) {
            rotateTeam(winner)
            servingTeam = winner
        }
        showCourt()
    }

    private fun rotateTeam(ti: Int) {
        // Volleyball clockwise rotation:
        // player at P1 -> P6 -> P5 -> P4 -> P3 -> P2 -> P1
        val old = selected[ti].clone()
        selected[ti][5] = old[0] // P1 -> P6
        selected[ti][4] = old[5] // P6 -> P5
        selected[ti][3] = old[4] // P5 -> P4
        selected[ti][2] = old[3] // P4 -> P3
        selected[ti][1] = old[2] // P3 -> P2
        selected[ti][0] = old[1] // P2 -> P1
    }

    private fun addReceptionView() {
        root.addView(TextView(this).apply {
            text = "${teams[0].name} — ΥΠΟΔΟΧΗ"
            gravity = Gravity.CENTER
            textSize = 18f
            setPadding(0,8,0,8)
        })

        val backMiddle = (0..5)
            .mapNotNull { pos ->
                val ix = selected[0][pos]
                if (ix in 0..13) teams[0].roster[ix] to pos else null
            }
            .firstOrNull { (player, pos) ->
                player.role in arrayOf("Κ1", "Κ2") && pos in arrayOf(0,4,5)
            }?.first
        val libero = teams[0].roster.firstOrNull { it.role == "Λ" }

        val setterPos = (0..5).firstOrNull { pos ->
            val ix = selected[0][pos]
            ix in 0..13 && teams[0].roster[ix].role == "Π"
        } ?: 0
        val rotation = setterPos + 1

        val receiverRoles = when(rotation) {
            1,6 -> arrayOf("ΑΚ1","Λ","ΑΚ2")
            4,5 -> arrayOf("Λ","ΑΚ1","ΑΚ2")
            2,3 -> arrayOf("ΑΚ2","Λ","ΑΚ1")
            else -> arrayOf("ΑΚ1","Λ","ΑΚ2")
        }

        root.addView(TextView(this).apply {
            text = "ΠΕΡΙΣΤΡΟΦΗ P$rotation — Πάτησε τη θέση της υποδοχής"
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0,0,0,8)
        })

        val court = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8,8,8,8)
            background = GradientDrawable().apply {
                setColor(Color.rgb(238,177,104))
                setStroke(3,Color.WHITE)
            }
        }

        // Each reception lane belongs to the same receiver in front and back:
        // P1/P2, P6/P3, P5/P4.
        val positionRows = arrayOf(
            arrayOf("P1","P6","P5"),
            arrayOf("P2","P3","P4")
        )

        positionRows.forEach { positions ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            positions.forEachIndexed { column, courtPos ->
                val role = receiverRoles[column]
                val player = if(role == "Λ") libero
                             else teams[0].roster.firstOrNull { it.role == role }
                row.addView(Button(this).apply {
                    text = buildString {
                        append(courtPos)
                        append("\n")
                        if(player != null && player.name.isNotBlank()) append(player.name) else append(role)
                        append("\n[")
                        if(role == "Λ" && backMiddle != null) append("Λ αντί ${backMiddle.role}")
                        else append(role)
                        append("]")
                    }
                    textSize = 12f
                    isAllCaps = false
                    setOnClickListener {
                        recordReception(player, role, rotation, courtPos)
                    }
                }, LinearLayout.LayoutParams(0,150,1f))
            }
            court.addView(row)
        }
        root.addView(court)

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

    private fun recordReception(player: Player?, role: String, rotation: Int, courtPos: String) {
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
        val key = "P$rotation|$role"
        receptionCountsByRotation[key] = (receptionCountsByRotation[key] ?: 0) + 1

        receptionPositionCounts[courtPos] = (receptionPositionCounts[courtPos] ?: 0) + 1
        if (courtPos in arrayOf("P1","P6","P5")) {
            receptionBackCounts[role] = (receptionBackCounts[role] ?: 0) + 1
            matchReceptionBackCounts[role] = (matchReceptionBackCounts[role] ?: 0) + 1
            if (serverKey != null) {
                val map = serverPreviousBackCounts.getOrPut(serverKey) { mutableMapOf() }
                map[role] = (map[role] ?: 0) + 1
            }
        } else if (courtPos in arrayOf("P2","P3","P4")) {
            receptionFrontCounts[role] = (receptionFrontCounts[role] ?: 0) + 1
            matchReceptionFrontCounts[role] = (matchReceptionFrontCounts[role] ?: 0) + 1
            if (serverKey != null) {
                val map = serverPreviousFrontCounts.getOrPut(serverKey) { mutableMapOf() }
                map[role] = (map[role] ?: 0) + 1
            }
        }

        val who = player?.name?.takeIf { it.isNotBlank() } ?: role
        val zone = if (courtPos in arrayOf("P1","P6","P5")) "πίσω" else "μπροστά"
        Toast.makeText(this, "+1 υποδοχή: $who ($zone)", Toast.LENGTH_SHORT).show()
        showCourt()
    }

    private fun currentOpponentServer(): Player? {
        val ix = selected[1][0] // P1 is the serving position
        return if (ix in 0..13) teams[1].roster[ix] else null
    }

    private fun serverKey(player: Player): String =
        player.number.trim() + "|" + player.name.trim()

    private fun patternHtml(counts: Map<String, Int>, total: Int): String {
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

    private fun patternPanel(title: String, counts: Map<String, Int>, total: Int, prefix: String? = null): TextView =
        TextView(this).apply {
            val html = buildString {
                append("<b>").append(title).append("</b>")
                if (!prefix.isNullOrBlank()) append("<br>").append(prefix)
                append("<br>").append(patternHtml(counts, total))
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

        arrayOf("ΑΚ1","ΑΚ2","Λ")
            .sortedByDescending { receptionCounts[it] ?: 0 }
            .forEach { role ->
            val player = teams[0].roster.firstOrNull { it.role == role }
            val name = player?.name?.takeIf { it.isNotBlank() } ?: role
            val n = receptionCounts[role] ?: 0
            val back = receptionBackCounts[role] ?: 0
            val front = receptionFrontCounts[role] ?: 0
            root.addView(TextView(this).apply {
                text = "$name: $n (${pct(n)}%) — πίσω $back — μπροστά $front"
                textSize = 16f
                setPadding(0,8,0,8)
            })
        }

        root.addView(TextView(this).apply {
            text = "ΣΤΟΧΟΣ ΑΝΑ ΘΕΣΗ"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0,20,0,8)
        })
        arrayOf("P1","P6","P5","P2","P3","P4").forEach { pos ->
            val n = receptionPositionCounts[pos] ?: 0
            root.addView(TextView(this).apply {
                text = "$pos: $n (${pct(n)}%)"
                textSize = 16f
                setPadding(0,5,0,5)
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
            val rotTotal = counts.values.sum()
            val detail = if (rotTotal == 0) "χωρίς καταγραφή" else
                counts.entries.joinToString(" — ") { (role,n) ->
                    "$role $n (${((n * 100.0) / rotTotal).toInt()}%)"
                }
            root.addView(TextView(this).apply {
                text = "P$rotation: $detail"
                textSize = 15f
                setPadding(0,5,0,5)
            })
        }

        root.addView(button("ΠΙΣΩ"){ showCourt() })
    }

    private fun addCourtHalf(ti:Int,title:String,opponent:Boolean){
        root.addView(TextView(this).apply { text=title; gravity=Gravity.CENTER; textSize=18f; setPadding(0,8,0,8) })
        val court=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(8,8,8,8)
            background=GradientDrawable().apply { setColor(Color.rgb(238,177,104)); setStroke(3,Color.WHITE) }
        }
        // Visual layout requested:
        // top, left -> right:    P1 P6 P5
        // bottom, left -> right: P2 P3 P4  (so bottom right -> left is P4 P3 P2)
        val rows = arrayOf(intArrayOf(0,5,4), intArrayOf(1,2,3))
        rows.forEach { positions ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
            positions.forEach { p -> row.addView(playerBox(ti,p),LinearLayout.LayoutParams(0,150,1f)) }
            court.addView(row)
        }
        root.addView(court)
    }

    private fun playerBox(ti:Int,pos:Int)=TextView(this).apply {
        val ix=selected[ti][pos]
        val player=if(ix in 0..13)teams[ti].roster[ix] else null
        text=buildString {
            append("P${pos+1}")
            if(player!=null && player.name.isNotBlank()){ append("\n"); append(player.name); if(player.role.isNotBlank())append("  [${player.role}]") }
        }
        gravity=Gravity.CENTER; textSize=14f; setTextColor(Color.BLACK); setPadding(4,4,4,4)
        background=GradientDrawable().apply { setColor(Color.argb(35,255,255,255)); setStroke(2,Color.WHITE) }
    }
}

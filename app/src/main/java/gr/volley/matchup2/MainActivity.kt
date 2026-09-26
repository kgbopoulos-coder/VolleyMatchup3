package gr.volley.matchup2

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

data class Player(var number: String = "", var name: String = "", var role: String = "")
data class Team(var name: String, val roster: MutableList<Player> = MutableList(14) { Player() })

class MainActivity : AppCompatActivity() {
    private val teams = arrayOf(Team("ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ"), Team("ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ"))
    private val roles = arrayOf("Π", "Δ", "ΑΚ", "Κ", "Λ")
    private val selected = Array(2) { IntArray(6) { -1 } }
    private var teamIndex = 0
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showHome()
    }

    private fun base(title: String) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
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
        base("VOLLEY MATCHUP 2")
        root.addView(button("ROSTER — ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ"){ teamIndex=0; showRoster() })
        root.addView(button("ROSTER — ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ"){ teamIndex=1; showRoster() })
        root.addView(button("ΑΡΧΙΚΗ ΠΕΡΙΣΤΡΟΦΗ"){ showRotationSetup() })
        root.addView(button("ΓΗΠΕΔΟ"){ showCourt() })
    }

    private fun showRoster() {
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

        root.addView(button("ΑΠΟΘΗΚΕΥΣΗ ΟΜΑΔΑΣ"){ saveCurrentTeam() })
        root.addView(button("ΦΟΡΤΩΣΗ ΟΜΑΔΑΣ"){ loadTeamDialog() })
        root.addView(button("ΠΙΣΩ"){ showHome() })
    }

    private fun scrollFieldIntoView(view: View) {
        var parent = view.parent
        while (parent != null && parent !is ScrollView) parent = parent.parent
        val scroll = parent as? ScrollView ?: return
        scroll.post {
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            scroll.offsetDescendantRectToMyCoords(view, rect)
            val extra = 80
            scroll.smoothScrollTo(0, (rect.bottom - scroll.height + extra).coerceAtLeast(0))
        }
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
                selected[teamIndex].fill(-1)
                showRoster()
            }
            .setNegativeButton("ΑΚΥΡΟ",null)
            .show()
    }

    private fun showRotationSetup() {
        base("ΑΡΧΙΚΗ ΠΕΡΙΣΤΡΟΦΗ")
        addTeamRotation(0); addTeamRotation(1)
        root.addView(button("ΠΡΟΒΟΛΗ ΣΤΟ ΓΗΠΕΔΟ"){ showCourt() })
        root.addView(button("ΠΙΣΩ"){ showHome() })
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
        base("ΓΗΠΕΔΟ")
        addCourtHalf(1, teams[1].name, true)
        root.addView(TextView(this).apply {
            text="────────  ΦΙΛΕΣ  ────────"; gravity=Gravity.CENTER; textSize=16f; setPadding(0,12,0,12)
        })
        addCourtHalf(0, teams[0].name, false)
        root.addView(button("ΠΙΣΩ"){showHome()})
    }

    private fun addCourtHalf(ti:Int,title:String,opponent:Boolean){
        root.addView(TextView(this).apply { text=title; gravity=Gravity.CENTER; textSize=18f; setPadding(0,8,0,8) })
        val court=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(8,8,8,8)
            background=GradientDrawable().apply { setColor(Color.rgb(238,177,104)); setStroke(3,Color.WHITE) }
        }
        val rows=if(opponent) arrayOf(intArrayOf(4,3,2),intArrayOf(5,0,1))
                 else arrayOf(intArrayOf(3,2,1),intArrayOf(4,5,0))
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

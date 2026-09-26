package gr.volley.matchup2

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

data class Player(var number: String = "", var name: String = "", var role: String = "")
data class Team(val name: String, val roster: MutableList<Player> = MutableList(14) { Player() })

class MainActivity : AppCompatActivity() {
    private val teams = arrayOf(Team("ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ"), Team("ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ"))
    private val roles = arrayOf("Π", "Δ", "ΑΚ", "Κ", "Λ")
    private var teamIndex = 0
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun base(title: String) {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 36)
        }
        scroll.addView(root)
        root.addView(TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(Color.rgb(32,32,95))
            gravity = Gravity.CENTER
            setPadding(0,0,0,24)
        })
        setContentView(scroll)
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { action() } }

    private fun showHome() {
        base("VOLLEY MATCHUP 2")
        root.addView(button("ROSTER — ΔΙΚΗ ΜΑΣ ΟΜΑΔΑ") { teamIndex=0; showRoster() })
        root.addView(button("ROSTER — ΑΝΤΙΠΑΛΗ ΟΜΑΔΑ") { teamIndex=1; showRoster() })
        root.addView(button("ΑΡΧΙΚΗ ΠΕΡΙΣΤΡΟΦΗ") { showRotationSetup() })
    }

    private fun showRoster() {
        base(teams[teamIndex].name)
        teams[teamIndex].roster.forEachIndexed { i, p ->
            val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            val no = EditText(this).apply {
                hint="No"; setText(p.number)
                layoutParams=LinearLayout.LayoutParams(0,-2,0.7f)
            }
            val name = EditText(this).apply {
                hint="Παίκτρια ${i+1}"; setText(p.name)
                layoutParams=LinearLayout.LayoutParams(0,-2,2.3f)
            }
            val role = Spinner(this).apply {
                adapter=ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, roles)
                val ix=roles.indexOf(p.role); if(ix>=0) setSelection(ix)
                layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            }
            row.addView(no); row.addView(name); row.addView(role); root.addView(row)
            no.setOnFocusChangeListener { _, _ -> p.number=no.text.toString() }
            name.setOnFocusChangeListener { _, _ -> p.name=name.text.toString() }
            role.onItemSelectedListener=object: AdapterView.OnItemSelectedListener {
                override fun onItemSelected(a:AdapterView<*>?,v:View?,pos:Int,id:Long){ p.role=roles[pos] }
                override fun onNothingSelected(a:AdapterView<*>?){}
            }
        }
        root.addView(button("ΑΠΟΘΗΚΕΥΣΗ & ΠΙΣΩ") {
            currentFocus?.clearFocus()
            showHome()
        })
    }

    private fun showRotationSetup() {
        base("ΑΡΧΙΚΗ ΠΕΡΙΣΤΡΟΦΗ")
        addTeamRotation(0)
        addTeamRotation(1)
        root.addView(button("ΠΙΣΩ") { showHome() })
    }

    private fun addTeamRotation(ti:Int) {
        root.addView(TextView(this).apply {
            text=teams[ti].name; textSize=18f; setPadding(0,24,0,8)
        })
        val available = teams[ti].roster.mapIndexed { i,p ->
            if(p.name.isBlank()) "${i+1}. —" else "${p.number} ${p.name} (${p.role})"
        }
        for(pos in listOf("P1","P2","P3","P4","P5","P6")) {
            val row=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text=pos; textSize=17f
                layoutParams=LinearLayout.LayoutParams(0,-2,.5f)
            })
            row.addView(Spinner(this).apply {
                adapter=ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, available)
                layoutParams=LinearLayout.LayoutParams(0,-2,3f)
            })
            root.addView(row)
        }
        root.addView(TextView(this).apply {
            text="Περιστροφή: P1 → P6 → P5 → P4 → P3 → P2 → P1"
            setPadding(0,10,0,12)
        })
    }
}

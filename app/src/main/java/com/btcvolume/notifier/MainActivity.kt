package com.btcvolume.notifier

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val intervals = listOf("1m", "5m", "15m", "1h")

    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
            status.text = prefs.getString("status", "Arrêté")
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, pad, 0, 4)
        }

        root.addView(TextView(this).apply {
            text = "BTC/USDT – Alerte de volume"
            textSize = 22f
        })

        root.addView(label("Taille des bougies"))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, intervals)
        spinner.setSelection(intervals.indexOf(prefs.getString("interval", "5m")).coerceAtLeast(0))
        root.addView(spinner)

        root.addView(label("Seuil (multiple de la moyenne, ex : 2.5)"))
        val threshold = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat("threshold", 2.5f).toString())
        }
        root.addView(threshold)

        val drop = CheckBox(this).apply {
            text = "Notifier aussi les chutes de volume"
            isChecked = prefs.getBoolean("drop", false)
        }
        root.addView(drop)

        val start = Button(this).apply { text = "Démarrer" }
        val stop = Button(this).apply { text = "Arrêter" }
        val battery = Button(this).apply { text = "Désactiver l'optimisation batterie" }
        root.addView(start)
        root.addView(stop)
        root.addView(battery)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)

        start.setOnClickListener {
            val t = threshold.text.toString().toFloatOrNull()?.coerceAtLeast(1.05f) ?: 2.5f
            prefs.edit()
                .putString("interval", spinner.selectedItem as String)
                .putFloat("threshold", t)
                .putBoolean("drop", drop.isChecked)
                .putString("status", "Démarrage…")
                .apply()
            startForegroundService(Intent(this, VolumeService::class.java))
        }
        stop.setOnClickListener {
            stopService(Intent(this, VolumeService::class.java))
        }
        battery.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}

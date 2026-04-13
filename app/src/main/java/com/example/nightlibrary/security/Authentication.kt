package com.example.nightlibrary.security

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.nightlibrary.R


class Authentication : AppCompatActivity() {
    private lateinit var dots: List<View>
    private var pin = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_authentication)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        dots = listOf(
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3),
            findViewById(R.id.dot4)
        )

        setupKeypad()
        setupFingerprint()
    }
    private fun setupKeypad() {

        val buttonMap = mapOf(
            R.id.btn1 to "1",
            R.id.btn2 to "2",
            R.id.btn3 to "3",
            R.id.btn4 to "4",
            R.id.btn5 to "5",
            R.id.btn6 to "6",
            R.id.btn7 to "7",
            R.id.btn8 to "8",
            R.id.btn9 to "9",
            R.id.btn0 to "0"
        )

        buttonMap.forEach { (id, value) ->
            val button = findViewById<View>(id)
            button.findViewById<TextView>(R.id.keyText).text = value
            button.setOnClickListener {
                onNumberClick(value)
                animatePress(button)
            }
        }

        val deleteBtn = findViewById<View>(R.id.btnDelete)
        deleteBtn.findViewById<TextView>(R.id.keyText).text = "⌫"
        deleteBtn.setOnClickListener {
            if (pin.isNotEmpty()) {
                pin = pin.dropLast(1)
                updateDots()
            }
            animatePress(deleteBtn)
        }
    }

    private fun onNumberClick(number: String) {
        if (pin.length < 4) {
            pin += number
            updateDots()

            if (pin.length == 4) validatePin()
        }
    }

    private fun updateDots() {
        dots.forEachIndexed { index, view ->
            if (index < pin.length) {
                view.setBackgroundResource(R.drawable.bg_pin_dot_filled)
            } else {
                view.setBackgroundResource(R.drawable.bg_pin_dot_empty)
            }
        }
    }

    private fun validatePin() {
        if (pin == "1234") {
            Toast.makeText(this, "Unlocked", Toast.LENGTH_SHORT).show()
        } else {
            shakeAnimation()
            pin = ""
            updateDots()
        }
    }

    private fun shakeAnimation() {
        val root = findViewById<View>(android.R.id.content)

        root.animate()
            .translationX(20f)
            .setDuration(50)
            .withEndAction {
                root.animate().translationX(-20f).setDuration(50).withEndAction {
                    root.animate().translationX(0f).setDuration(50)
                }
            }
    }

    private fun animatePress(view: View) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(80)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(80)
            }
    }
// Changed from fingerprint container to fingerprinticon??
    private fun setupFingerprint() {
        val fingerprint = findViewById<View>(R.id.fingerPrintIcon)

        fingerprint.setOnClickListener {
            startGlowAnimation(fingerprint)
        }
    }
    private fun startGlowAnimation(view: View) {

        view.animate()
            .scaleX(1.1f)
            .scaleY(1.1f)
            .alpha(0.7f)
            .setDuration(400)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(400)
                    .withEndAction {
                        startGlowAnimation(view) // loop manually
                    }
            }
    }


}
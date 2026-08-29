package com.audioshare.usbcompanion

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(244, 246, 252))
            addView(TextView(context).apply {
                setText(R.string.app_name)
                textSize = 28f
                setTextColor(Color.rgb(22, 28, 45))
            })
            addView(TextView(context).apply {
                setText(R.string.ready_for_host)
                textSize = 18f
                setTextColor(Color.rgb(49, 92, 255))
                setPadding(0, padding / 2, 0, 0)
            })
            addView(TextView(context).apply {
                setText(R.string.connection_instructions)
                textSize = 15f
                setTextColor(Color.DKGRAY)
                setPadding(0, padding, 0, 0)
            })
        }
        setContentView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }
}

package com.nexus.cli

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** BottomSheet "Kustomisasi Widget": pilih widget yang tampil di halaman Quick Launch. */
class WidgetCustomizeSheet(
    context: Context,
    private val initial: Set<String>,
    private val onSave: (Set<String>) -> Unit
) : BottomSheetDialog(context) {

    private val neon = ContextCompat.getColor(context, R.color.nexus_green)
    private val white = ContextCompat.getColor(context, R.color.text_primary)
    private val checks = LinkedHashMap<String, CheckBox>()

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun buildContent(): View {
        val r = dp(24).toFloat()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121216"))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), ContextCompat.getColor(context, R.color.glass_stroke))
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        root.addView(TextView(context).apply {
            text = "Kustomisasi Widget"
            setTextColor(white)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        QuickWidgets.all.forEach { (key, label) ->
            val cb = CheckBox(context).apply {
                text = label
                isChecked = key in initial
                setTextColor(white)
                textSize = 15f
                buttonTintList = ColorStateList.valueOf(neon)
            }
            checks[key] = cb
            list.addView(cb)
        }
        root.addView(
            ScrollView(context).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)).apply { topMargin = dp(8) }
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(
            pill("Batal", Color.TRANSPARENT, white, true) { dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) }
        )
        buttons.addView(pill("Simpan", neon, Color.BLACK, false) {
            val chosen = QuickWidgets.all.keys.filter { checks[it]?.isChecked == true }.toSet()
            onSave(chosen)
            dismiss()
        })
        root.addView(
            buttons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(12) }
        )
        return root
    }

    private fun pill(label: String, bg: Int, fg: Int, outline: Boolean, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            setTextColor(fg)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(bg)
                if (outline) setStroke(dp(1), Color.parseColor("#55FFFFFF"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
}
package com.sudosilo.cliprig

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

const val BLUE = 0xFF1E6BFF.toInt()
const val BLUE_DARK = 0xFF0F4FCC.toInt()
const val BLUE_SOFT = 0xFFE8F0FF.toInt()
const val INK = 0xFF1A2233.toInt()
const val MUTED = 0xFF5B6478.toInt()
const val PAPER = 0xFFF7F9FC.toInt()

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (stroke > 0) setStroke(stroke, strokeColor)
}

fun Context.label(t: String, size: Float = 14f, color: Int = MUTED, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = size; setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.blueButton(t: String, filled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
    text = t
    textSize = 16f
    gravity = Gravity.CENTER
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(if (filled) Color.WHITE else BLUE)
    background = if (filled) rounded(BLUE, dp(14).toFloat())
    else rounded(Color.WHITE, dp(14).toFloat(), dp(2), BLUE)
    setPadding(dp(16), dp(12), dp(16), dp(12))
    isClickable = true
    setOnClickListener { onClick() }
}

fun Context.field(hint: String, value: String = "", multi: Boolean = false) = EditText(this).apply {
    this.hint = hint
    setText(value)
    textSize = 16f
    setTextColor(INK)
    background = rounded(Color.WHITE, dp(12).toFloat(), dp(1), 0xFFC9D6F2.toInt())
    setPadding(dp(14), dp(12), dp(14), dp(12))
    if (multi) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP or Gravity.START
        minLines = 5; maxLines = 5
        isVerticalScrollBarEnabled = true
    } else {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        isSingleLine = true
    }
}

fun Context.picker(options: List<String>, selected: Int, onPick: (Int) -> Unit): Spinner {
    val sp = Spinner(this)
    sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
    sp.background = rounded(Color.WHITE, dp(12).toFloat(), dp(1), 0xFFC9D6F2.toInt())
    sp.setPadding(dp(8), dp(6), dp(8), dp(6))
    if (selected in options.indices) sp.setSelection(selected, false)
    sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
    return sp
}

fun Context.column(pad: Int = 16) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun ViewGroup.add(v: View, topGap: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    lp.topMargin = (topGap * resources.displayMetrics.density).toInt()
    addView(v, lp)
    return v
}

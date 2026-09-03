/*
 * Copyright 2023 Miklos Vajna
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** [ValueFormatter] for showing dates on the axis of a graph. */
class DateAxisFormatter : ValueFormatter() {
    override fun getAxisLabel(value: Float, axis: AxisBase?): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(value.toLong()))
    }

    override fun getFormattedValue(value: Float): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Date(value.toLong()))
    }
}

/** [ValueFormatter] for showing times on the axis of a graph, converted to UTC. */
class TimeAxisFormatter : ValueFormatter() {
    private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun getAxisLabel(value: Float, axis: AxisBase?): String {
        return sdf.format(Date(value.toLong()))
    }

    override fun getFormattedValue(value: Float): String {
        return sdf.format(Date(value.toLong()))
    }

    override fun getPointLabel(entry: Entry?): String {
        return entry?.y?.toLong()?.let { sdf.format(Date(it)) } ?: ""
    }
}

/** [ValueFormatter] for showing float numbers on the axis of a graph, up to one decimal place. */
class FloatAxisFormatter : ValueFormatter() {
    private val decimalFormat = DecimalFormat("0.#")
        .apply { isDecimalSeparatorAlwaysShown = false }

    override fun getAxisLabel(value: Float, axis: AxisBase?): String {
        return decimalFormat.format(value)
    }

    override fun getFormattedValue(value: Float): String {
        return decimalFormat.format(value)
    }

    override fun getPointLabel(entry: Entry?): String {
        return entry?.y?.let { decimalFormat.format(it) } ?: ""
    }
}

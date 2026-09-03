/*
 * Copyright 2026 Miklos Vajna
 *
 * SPDX-License-Identifier: MIT
 */

package hu.vmiklos.plees_tracker

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Displays a tabular view of sleep analysis data, listing rows per date/day with columns
 * per plotted metric.
 */
class TableActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var tableLayout: TableLayout
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_table)

        DataModel.handleWindowInsets(this)

        title = getString(R.string.table_view)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        tableLayout = findViewById(R.id.data_table_layout)
        emptyView = findViewById(R.id.table_empty_view)

        viewModel = ViewModelProvider.AndroidViewModelFactory(application)
            .create(MainViewModel::class.java)

        val idealSleep = getIdealSleep()

        viewModel.durationSleepsLive.observe(this) { sleeps ->
            if (sleeps.isNullOrEmpty()) {
                tableLayout.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
            } else {
                emptyView.visibility = View.GONE
                tableLayout.visibility = View.VISIBLE
                populateTable(sleeps, idealSleep)
            }
        }
    }

    private fun getIdealSleep(): Float {
        val preferences = PreferenceManager.getDefaultSharedPreferences(application)
        val idealSleepStr = preferences.getString("ideal_sleep_length", "8.0") ?: "8.0"
        return idealSleepStr.toFloatOrNull() ?: 8f
    }

    private fun populateTable(sleeps: List<Sleep>, idealSleep: Float) {
        tableLayout.removeAllViews()

        // 1. Header Row
        val headerRow = TableRow(this).apply {
            setPadding(4, 8, 4, 8)
        }
        val headers = listOf(
            R.string.table_header_date,
            R.string.table_header_length,
            R.string.table_header_deficit,
            R.string.table_header_midpoint,
            R.string.table_header_debt_7d,
            R.string.table_header_start,
            R.string.table_header_stop,
            R.string.table_header_rating
        )
        val textColor = ContextCompat.getColor(this, R.color.textColor)
        for (resId in headers) {
            val tv = TextView(this).apply {
                text = getString(resId)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(16, 12, 16, 12)
            }
            headerRow.addView(tv)
        }
        tableLayout.addView(headerRow)

        // 2. Aggregate data per day
        val dayGroups = sleeps.groupBy { it.stop.stripTime() }
        val sortedDays = dayGroups.keys.sortedDescending()

        // Calculate chronological 7d debt mapping
        val chronologicalDays = dayGroups.keys.sorted()
        val lengthList = chronologicalDays.map { day ->
            day to (dayGroups[day]?.map { it.lengthHours }?.sum() ?: 0f)
        }
        val debt7dMap = lengthList.rolling7dDebt(idealSleep).toMap()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        var rowIndex = 0
        for (day in sortedDays) {
            val daySleeps = dayGroups[day] ?: continue
            val totalLength = daySleeps.map { it.lengthHours }.sum()
            val deficit = totalLength - idealSleep

            val earliest = daySleeps.minByOrNull { it.start } ?: Sleep()
            val latest = daySleeps.maxByOrNull { it.stop } ?: Sleep()
            val midpointMs = (earliest.start + latest.stop) / 2
            val midpointOfDay = midpointMs - day
            val startOfDay = earliest.start - day
            val stopOfDay = latest.stop - day

            val avgRating = daySleeps.sumOf { it.rating }.toFloat() / daySleeps.size
            val debt7d = debt7dMap[day] ?: 0f

            val row = TableRow(this).apply {
                setPadding(4, 6, 4, 6)
                if (rowIndex % 2 == 1) {
                    setBackgroundColor(0x11888888)
                }
            }

            val cells = listOf(
                dateFormat.format(Date(day)),
                String.format(Locale.getDefault(), "%.1f h", totalLength),
                String.format(Locale.getDefault(), "%+.1f h", deficit),
                timeFormat.format(Date(midpointOfDay)),
                String.format(Locale.getDefault(), "%+.1f h", debt7d),
                timeFormat.format(Date(startOfDay)),
                timeFormat.format(Date(stopOfDay)),
                String.format(Locale.getDefault(), "%.1f", avgRating)
            )

            for (cellText in cells) {
                val tv = TextView(this).apply {
                    text = cellText
                    setTextColor(textColor)
                    gravity = Gravity.CENTER
                    setPadding(16, 10, 16, 10)
                    textSize = 13f
                }
                row.addView(tv)
            }

            tableLayout.addView(row)
            rowIndex++
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

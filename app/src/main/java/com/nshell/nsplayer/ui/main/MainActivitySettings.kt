package com.nshell.nsplayer.ui.main

import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.nshell.nsplayer.R
import com.nshell.nsplayer.ui.settings.advanced.AdvancedSettingsActivity

internal fun MainActivity.showSettingsDialog(anchor: View) {
    val content = layoutInflater.inflate(R.layout.popup_settings, null)
    val modeFolders = content.findViewById<TextView>(R.id.settingsModeFolders)
    val modeHierarchy = content.findViewById<TextView>(R.id.settingsModeHierarchy)
    val modeVideos = content.findViewById<TextView>(R.id.settingsModeVideos)
    val displayList = content.findViewById<TextView>(R.id.settingsDisplayList)
    val displayTile = content.findViewById<TextView>(R.id.settingsDisplayTile)
    val displayTileMultipliers = content.findViewById<View>(R.id.settingsDisplayTileMultipliers)
    val displayTileX2Row = content.findViewById<View>(R.id.settingsDisplayTileX2Row)
    val displayTileX3Row = content.findViewById<View>(R.id.settingsDisplayTileX3Row)
    val displayTileX4Row = content.findViewById<View>(R.id.settingsDisplayTileX4Row)
    val displayTileX2 = content.findViewById<TextView>(R.id.settingsDisplayTileX2)
    val displayTileX3 = content.findViewById<TextView>(R.id.settingsDisplayTileX3)
    val displayTileX4 = content.findViewById<TextView>(R.id.settingsDisplayTileX4)
    val displayTileX2Icon = content.findViewById<ImageView>(R.id.settingsDisplayTileX2Icon)
    val displayTileX3Icon = content.findViewById<ImageView>(R.id.settingsDisplayTileX3Icon)
    val displayTileX4Icon = content.findViewById<ImageView>(R.id.settingsDisplayTileX4Icon)
    val sortTitle = content.findViewById<TextView>(R.id.settingsSortTitle)
    val sortModified = content.findViewById<TextView>(R.id.settingsSortModified)
    val sortDuration = content.findViewById<TextView>(R.id.settingsSortDuration)
    val sortAsc = content.findViewById<TextView>(R.id.settingsSortAsc)
    val sortDesc = content.findViewById<TextView>(R.id.settingsSortDesc)
    val advancedRow = content.findViewById<View>(R.id.settingsAdvancedRow)
    val cancelButton = content.findViewById<Button>(R.id.settingsCancel)
    val confirmButton = content.findViewById<Button>(R.id.settingsConfirm)
    val defaultColor = modeFolders.currentTextColor
    val selectedColor = getColor(R.color.brand_green)
    var pendingMode = browserState.currentMode
    var pendingDisplay = browserState.videoDisplayMode
    var pendingTileSpan = browserState.tileSpanCount
    var pendingSort = browserState.sortMode
    var pendingOrder = browserState.sortOrder
    updateModeSelectionUI(
        modeFolders,
        modeHierarchy,
        modeVideos,
        pendingMode,
        selectedColor,
        defaultColor
    )
    updateDisplaySelectionUI(
        displayList,
        displayTile,
        displayTileMultipliers,
        pendingDisplay,
        selectedColor,
        defaultColor
    )
    updateTileSpanSelectionUI(
        displayTileX2,
        displayTileX3,
        displayTileX4,
        displayTileX2Icon,
        displayTileX3Icon,
        displayTileX4Icon,
        pendingTileSpan,
        selectedColor,
        defaultColor
    )
    updateSortSelectionUI(
        sortTitle,
        sortModified,
        sortDuration,
        sortAsc,
        sortDesc,
        pendingSort,
        pendingOrder,
        selectedColor,
        defaultColor
    )

    val dialog = AlertDialog.Builder(this)
        .setView(content)
        .create()
    dialog.setCanceledOnTouchOutside(true)

    modeFolders.setOnClickListener {
        pendingMode = VideoMode.FOLDERS
        updateModeSelectionUI(
            modeFolders,
            modeHierarchy,
            modeVideos,
            pendingMode,
            selectedColor,
            defaultColor
        )
    }
    modeHierarchy.setOnClickListener {
        pendingMode = VideoMode.HIERARCHY
        updateModeSelectionUI(
            modeFolders,
            modeHierarchy,
            modeVideos,
            pendingMode,
            selectedColor,
            defaultColor
        )
    }
    modeVideos.setOnClickListener {
        pendingMode = VideoMode.VIDEOS
        updateModeSelectionUI(
            modeFolders,
            modeHierarchy,
            modeVideos,
            pendingMode,
            selectedColor,
            defaultColor
        )
    }

    displayList.setOnClickListener {
        pendingDisplay = VideoDisplayMode.LIST
        updateDisplaySelectionUI(
            displayList,
            displayTile,
            displayTileMultipliers,
            pendingDisplay,
            selectedColor,
            defaultColor
        )
    }
    displayTile.setOnClickListener {
        val wasTile = pendingDisplay == VideoDisplayMode.TILE
        pendingDisplay = VideoDisplayMode.TILE
        if (!wasTile) {
            pendingTileSpan = 2
            updateTileSpanSelectionUI(
                displayTileX2,
                displayTileX3,
                displayTileX4,
                displayTileX2Icon,
                displayTileX3Icon,
                displayTileX4Icon,
                pendingTileSpan,
                selectedColor,
                defaultColor
            )
        }
        updateDisplaySelectionUI(
            displayList,
            displayTile,
            displayTileMultipliers,
            pendingDisplay,
            selectedColor,
            defaultColor
        )
    }

    displayTileX2Row.setOnClickListener {
        pendingTileSpan = 2
        updateTileSpanSelectionUI(
            displayTileX2,
            displayTileX3,
            displayTileX4,
            displayTileX2Icon,
            displayTileX3Icon,
            displayTileX4Icon,
            pendingTileSpan,
            selectedColor,
            defaultColor
        )
    }
    displayTileX3Row.setOnClickListener {
        pendingTileSpan = 3
        updateTileSpanSelectionUI(
            displayTileX2,
            displayTileX3,
            displayTileX4,
            displayTileX2Icon,
            displayTileX3Icon,
            displayTileX4Icon,
            pendingTileSpan,
            selectedColor,
            defaultColor
        )
    }
    displayTileX4Row.setOnClickListener {
        pendingTileSpan = 4
        updateTileSpanSelectionUI(
            displayTileX2,
            displayTileX3,
            displayTileX4,
            displayTileX2Icon,
            displayTileX3Icon,
            displayTileX4Icon,
            pendingTileSpan,
            selectedColor,
            defaultColor
        )
    }

    sortTitle.setOnClickListener {
        pendingSort = VideoSortMode.TITLE
        updateSortSelectionUI(
            sortTitle,
            sortModified,
            sortDuration,
            sortAsc,
            sortDesc,
            pendingSort,
            pendingOrder,
            selectedColor,
            defaultColor
        )
    }
    sortModified.setOnClickListener {
        pendingSort = VideoSortMode.MODIFIED
        updateSortSelectionUI(
            sortTitle,
            sortModified,
            sortDuration,
            sortAsc,
            sortDesc,
            pendingSort,
            pendingOrder,
            selectedColor,
            defaultColor
        )
    }
    sortDuration.setOnClickListener {
        pendingSort = VideoSortMode.LENGTH
        updateSortSelectionUI(
            sortTitle,
            sortModified,
            sortDuration,
            sortAsc,
            sortDesc,
            pendingSort,
            pendingOrder,
            selectedColor,
            defaultColor
        )
    }
    sortAsc.setOnClickListener {
        pendingOrder = VideoSortOrder.ASC
        updateSortSelectionUI(
            sortTitle,
            sortModified,
            sortDuration,
            sortAsc,
            sortDesc,
            pendingSort,
            pendingOrder,
            selectedColor,
            defaultColor
        )
    }
    sortDesc.setOnClickListener {
        pendingOrder = VideoSortOrder.DESC
        updateSortSelectionUI(
            sortTitle,
            sortModified,
            sortDuration,
            sortAsc,
            sortDesc,
            pendingSort,
            pendingOrder,
            selectedColor,
            defaultColor
        )
    }

    advancedRow.setOnClickListener {
        dialog.dismiss()
        startActivity(Intent(this, AdvancedSettingsActivity::class.java))
    }
    cancelButton.setOnClickListener { dialog.dismiss() }
    confirmButton.setOnClickListener {
        val modeChanged = pendingMode != browserState.currentMode
        val displayChanged = pendingDisplay != browserState.videoDisplayMode
        val tileSpanChanged = pendingTileSpan != browserState.tileSpanCount
        val sortChanged = pendingSort != browserState.sortMode
        val orderChanged = pendingOrder != browserState.sortOrder
        if (displayChanged) {
            settingsViewModel.updateDisplayMode(pendingDisplay)
        }
        if (tileSpanChanged) {
            settingsViewModel.updateTileSpanCount(pendingTileSpan)
        }
        if (sortChanged || orderChanged) {
            settingsViewModel.updateSortMode(pendingSort)
            settingsViewModel.updateSortOrder(pendingOrder)
        }
        if (displayChanged || tileSpanChanged || sortChanged || orderChanged) {
            viewModel.updateState {
                it.copy(
                    videoDisplayMode = pendingDisplay,
                    tileSpanCount = pendingTileSpan,
                    sortMode = pendingSort,
                    sortOrder = pendingOrder
                )
            }
        }
        if (modeChanged) {
            settingsViewModel.updateMode(pendingMode)
            setMode(pendingMode)
        } else if (
            displayChanged ||
            (tileSpanChanged && pendingDisplay == VideoDisplayMode.TILE)
        ) {
            applyVideoDisplayMode()
        }
        if ((sortChanged || orderChanged) && !modeChanged) {
            loadIfPermitted()
        }
        dialog.dismiss()
    }

    dialog.show()
}

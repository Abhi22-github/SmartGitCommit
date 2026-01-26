package com.example.smartgitcommit

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.time.ZoneId
import java.util.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

class SmartGitPanel(
    private val project: Project?
) : JPanel(BorderLayout()), Disposable {

    // ================= TOP =================

    private val masterCheckbox = JCheckBox("Changes 0 files", true)

    private val sectionsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
        alignmentX = LEFT_ALIGNMENT
    }

    private val changesScroll = JScrollPane(sectionsContainer).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        viewport.background = JBColor.background()
    }

    // ================= BOTTOM =================

    private val dateSpinner = JSpinner(SpinnerDateModel())
    private val commitMessage = JTextArea()
    private val commitScroll = JScrollPane(commitMessage)

    private val commitButton = JButton("Commit")
    private val commitPushButton = JButton("Commit and Push…")

    private var suppressMasterEvents = false

    init {
        background = JBColor.background()
        border = JBUI.Borders.empty()

        add(buildMainSplitPane(), BorderLayout.CENTER)
        add(buildBottomButtons(), BorderLayout.SOUTH)

        setupCommitEditor()
        setupActions()
        registerVcsListener()

        refreshChanges()
    }

    // =====================================================
    // VCS LISTENER (CRITICAL)
    // =====================================================

    private fun registerVcsListener() {
        val proj = project ?: return
        ChangeListManager.getInstance(proj).addChangeListListener(
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    ApplicationManager.getApplication().invokeLater {
                        refreshChanges()
                    }
                }
            },
            this
        )
    }

    override fun dispose() {
        // auto-disposed
    }

    // =====================================================
    // MAIN SPLIT
    // =====================================================

    private fun buildMainSplitPane(): JComponent {

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = JBColor.background()
            border = JBUI.Borders.empty(6, 8)
            add(masterCheckbox)
            add(Box.createHorizontalGlue())
        }

        val topPanel = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(header, BorderLayout.NORTH)
            add(changesScroll, BorderLayout.CENTER)
        }

        val bottomPanel = JPanel(BorderLayout()).apply {
            background = JBColor.background()
        }

        val dateRow = buildDateRow().apply {
            minimumSize = preferredSize
            maximumSize = preferredSize // 🔒 fixed height
        }

        bottomPanel.add(dateRow, BorderLayout.NORTH)
        bottomPanel.add(commitScroll, BorderLayout.CENTER)

        return JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanel).apply {
            resizeWeight = 0.6
            dividerSize = 6
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            ui = object : BasicSplitPaneUI() {
                override fun createDefaultDivider(): BasicSplitPaneDivider =
                    object : BasicSplitPaneDivider(this) {
                        override fun paint(g: Graphics) {}
                    }.apply {
                       // isOpaque = false
                    }
            }
        }
    }

    // =====================================================
    // DATE ROW
    // =====================================================

    private fun buildDateRow(): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = JBColor.background()
            border = JBUI.Borders.empty(6, 8)

            add(JLabel("Change Date"))
            add(Box.createHorizontalStrut(8))
            dateSpinner.editor = JSpinner.DateEditor(dateSpinner, "dd/MM/yy, h:mm a")
            add(dateSpinner)
            add(Box.createHorizontalGlue())
        }

    // =====================================================
    // BOTTOM BUTTONS
    // =====================================================

    private fun buildBottomButtons(): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = JBColor.background()
            border = JBUI.Borders.empty(8)
            add(Box.createHorizontalGlue())
            add(commitButton)
            add(Box.createHorizontalStrut(8))
            add(commitPushButton)
        }

    // =====================================================
    // COMMIT EDITOR
    // =====================================================

    private fun setupCommitEditor() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        commitMessage.apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
            background = scheme.defaultBackground
            foreground = scheme.defaultForeground
            caretColor = scheme.defaultForeground
        }
        commitScroll.border = JBUI.Borders.empty()
        commitScroll.viewport.background = scheme.defaultBackground
    }

    // =====================================================
    // ACTIONS
    // =====================================================

    private fun setupActions() {
        masterCheckbox.addActionListener {
            if (suppressMasterEvents) return@addActionListener
            setAllFileCheckboxes(masterCheckbox.isSelected)
        }

        commitButton.addActionListener { doCommit(false) }
        commitPushButton.addActionListener { doCommit(true) }
    }

    // =====================================================
    // FILES (VCS SOURCE OF TRUTH)
    // =====================================================

    private fun refreshChanges() {
        val proj = project ?: return
        val clm = ChangeListManager.getInstance(proj)
        val changes: List<Change> = clm.allChanges.toList()

        sectionsContainer.removeAll()

        if (changes.isEmpty()) {
            sectionsContainer.add(JLabel("No changes").apply {
                border = JBUI.Borders.empty(6, 16)
            })
            updateMaster(false, false)
        } else {
            val section = SectionPanel("Changes", changes.size)

            changes.forEach { change ->
                val vf = change.virtualFile ?: return@forEach
                section.addRow(createFileRow(vf.path))
            }

            sectionsContainer.add(section)
            sectionsContainer.add(Box.createVerticalGlue())
            updateMaster(true, true)
        }

        sectionsContainer.revalidate()
        sectionsContainer.repaint()
    }

    private fun createFileRow(path: String): RowPanel {
        val row = RowPanel(path)
        row.checkbox.isSelected = true

        val name = path.substringAfterLast('/')
        val parent = path.substringBeforeLast('/', "")

        val label = JLabel(
            "<html><b>$name</b> <span style='color:gray'>($parent)</span></html>"
        )

        row.add(row.checkbox)
        row.add(Box.createHorizontalStrut(6))
        row.add(label)
        row.add(Box.createHorizontalGlue())

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (SwingUtilities.getDeepestComponentAt(row, e.x, e.y) is JCheckBox) return
                openFile(path)
            }
        })

        row.checkbox.addActionListener { syncMasterFromRows() }
        return row
    }

    private fun setAllFileCheckboxes(selected: Boolean) {
        sectionsContainer.components
            .filterIsInstance<SectionPanel>()
            .forEach { it.setAll(selected) }
    }

    private fun syncMasterFromRows() {
        var all = true
        var any = false

        sectionsContainer.components
            .filterIsInstance<SectionPanel>()
            .forEach {
                val (a, n) = it.summary()
                all = all && a
                any = any || n
            }

        updateMaster(all, any)
    }

    private fun updateMaster(all: Boolean, any: Boolean) {
        suppressMasterEvents = true
        masterCheckbox.isSelected = all && any
        masterCheckbox.text = "Changes ${countRows()} files"
        suppressMasterEvents = false
    }

    private fun countRows(): Int =
        sectionsContainer.components
            .filterIsInstance<SectionPanel>()
            .sumOf { it.rowCount }

    // =====================================================
    // COMMIT (WITH VCS REFRESH)
    // =====================================================

    private fun doCommit(push: Boolean) {
        val message = commitMessage.text.trim()
        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Commit message required")
            return
        }

        val dateTime = (dateSpinner.value as Date)
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()

        val selectedFiles = sectionsContainer.components
            .filterIsInstance<SectionPanel>()
            .flatMap { it.selectedFiles() }

        project?.let { proj ->
            if (selectedFiles.isNotEmpty()) {
                GitRunner.commitSelectedFiles(proj, selectedFiles, message, dateTime)
            } else {
                GitRunner.commit(proj, message, dateTime)
            }

            if (push) {
                GitRunner.push(proj)
            }

            VcsDirtyScopeManager.getInstance(proj).markEverythingDirty()
        }

        commitMessage.text = ""
    }


    private fun openFile(path: String) {
        val vf = LocalFileSystem.getInstance().findFileByPath(path)
        if (vf != null && project != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    // =====================================================
    // SECTION / ROW
    // =====================================================

    private class SectionPanel(title: String, count: Int) : JPanel() {
        private val rows = mutableListOf<RowPanel>()
        val rowCount get() = rows.size

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = JBColor.background()
            border = EmptyBorder(4, 12, 4, 12)

            val header = JLabel("$title ($count)").apply {
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.emptyBottom(6)
            }
            add(header)
        }

        fun addRow(row: RowPanel) {
            rows.add(row)
            add(row)
        }

        fun setAll(selected: Boolean) {
            rows.forEach { it.checkbox.isSelected = selected }
        }

        fun summary(): Pair<Boolean, Boolean> {
            var all = true
            var any = false
            rows.forEach {
                all = all && it.checkbox.isSelected
                any = any || it.checkbox.isSelected
            }
            return all to any
        }

        fun selectedFiles(): List<String> =
            rows.filter { it.checkbox.isSelected }.map { it.filePath }
    }

    private class RowPanel(val filePath: String) : JPanel() {
        val checkbox = JCheckBox()

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            background = JBColor.background()
            border = EmptyBorder(4, 24, 4, 8)
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
            preferredSize = Dimension(100, 28)
        }
    }
}

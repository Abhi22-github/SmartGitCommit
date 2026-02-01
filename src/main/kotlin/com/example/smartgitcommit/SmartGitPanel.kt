package com.example.smartgitcommit

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.*
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.*
import javax.swing.*
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

class SmartGitPanel(private val project: Project?) : JPanel(BorderLayout()), Disposable {

    private val masterCheckbox = JCheckBox("Changes 0 files", true).apply { isOpaque = false }
    private val placeholderText = "Commit Message"
    private var showEmptyDates = false

    private val sectionsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
    }

    private val dateSpinner = JSpinner(SpinnerDateModel())
    private val commitMessage = JTextArea(placeholderText)

    private val roundedCommitContainer = object : JPanel(BorderLayout()) {
        init { isOpaque = false; border = JBUI.Borders.empty(8) }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = EditorColorsManager.getInstance().globalScheme.defaultBackground
            g2.fillRoundRect(8, 8, width - 16, height - 16, 12, 12)
            g2.color = JBColor.border()
            g2.drawRoundRect(8, 8, width - 16, height - 16, 12, 12)
            g2.dispose()
        }
    }

    private val commitButton = JButton("Commit")
    private val commitPushButton = JButton("Commit and Push…")
    private var suppressMasterEvents = false

    private val historyContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
    }
    private val historySections = mutableListOf<HistorySection>()

    init {
        background = JBColor.background()
        val tabbedPane = JBTabbedPane().apply {
            addTab("Commit", buildCommitTab())
            addTab("History", buildHistoryTab())
        }
        add(tabbedPane, BorderLayout.CENTER)

        setupCommitEditor()
        setupActions()
        registerVcsListener()
        refreshChanges()
    }

    // ================= TAB BUILDERS =================

    private fun buildCommitTab(): JPanel = JPanel(BorderLayout()).apply {
        add(buildMainSplitPane(), BorderLayout.CENTER)
        add(buildBottomButtons(), BorderLayout.SOUTH)
    }

    private fun buildHistoryTab(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEADING, 4, 2)).apply {
            background = JBColor.background()
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)

            val toggleEmptyBtn = createToolbarButton(AllIcons.General.Show, "Show/Hide Empty Days") { }
            fun updateToggleLook() {
                toggleEmptyBtn.isOpaque = showEmptyDates
                toggleEmptyBtn.background = if (showEmptyDates) Color(0x3592FF) else JBColor.background()
                toggleEmptyBtn.repaint()
            }
            updateToggleLook()
            toggleEmptyBtn.addActionListener {
                showEmptyDates = !showEmptyDates
                updateToggleLook()
                refreshHistory()
            }

            add(toggleEmptyBtn)
            add(createToolbarButton(AllIcons.Actions.Expandall, "Expand All") { historySections.forEach { it.toggle(true) } })
            add(createToolbarButton(AllIcons.Actions.Collapseall, "Collapse All") { historySections.forEach { it.toggle(false) } })
        }

        val scroll = JScrollPane(JPanel(BorderLayout()).apply {
            add(historyContainer, BorderLayout.NORTH); background = JBColor.background()
        }).apply { border = JBUI.Borders.empty(); viewport.background = JBColor.background() }

        refreshHistory()
        return JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER) }
    }

    // ================= HISTORY LOGIC =================

    private fun refreshHistory() {
        val root = project?.basePath ?: return
        historyContainer.removeAll()
        historySections.clear()

        val output = try { GitRunner.runAndCapture(root, listOf("git", "log", "--pretty=format:%s|%an|%ct")) } catch (e: Exception) { "" }
        if (output.isBlank()) return

        val dateFmt = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH)
        val timeFmt = SimpleDateFormat("h:mm a")
        val rawGroups = mutableMapOf<Long, MutableList<CommitData>>()

        output.lines().filter { it.isNotBlank() }.forEach { line ->
            val p = line.split("|")
            if (p.size >= 3) {
                val date = Date(p[2].toLong() * 1000)
                val cal = Calendar.getInstance().apply { time = date; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                rawGroups.getOrPut(cal.timeInMillis) { mutableListOf() }.add(CommitData("", p[0], p[1], timeFmt.format(date)))
            }
        }

        val sorted = rawGroups.keys.sortedDescending()
        if (showEmptyDates && sorted.isNotEmpty()) {
            val cursor = Calendar.getInstance().apply { timeInMillis = sorted.first() }
            val end = Calendar.getInstance().apply { timeInMillis = sorted.last() }
            while (!cursor.before(end)) {
                val ts = cursor.timeInMillis
                val dateStr = dateFmt.format(Date(ts))
                if (rawGroups.containsKey(ts)) {
                    addHistorySection(dateStr, rawGroups[ts]!!)
                } else {
                    // ALIGNMENT FIX: Wrapped in a Panel with exact same header padding
                    val emptyDayPanel = JPanel(FlowLayout(FlowLayout.LEFT, 35, 4)).apply {
                        isOpaque = false
                        add(JLabel("<html><span style='color: #FF8C82;'>$dateStr</span> <span style='color: #808080;'>(No commits)</span></html>"))
                    }
                    historyContainer.add(emptyDayPanel)
                }
                cursor.add(Calendar.DATE, -1)
            }
        } else {
            sorted.forEach { ts -> addHistorySection(dateFmt.format(Date(ts)), rawGroups[ts]!!) }
        }
        historyContainer.revalidate(); historyContainer.repaint()
    }

    private fun addHistorySection(date: String, commits: MutableList<CommitData>) {
        val sec = HistorySection(date)
        commits.forEach { sec.addRow(it) }
        historyContainer.add(sec)
        historySections.add(sec)
    }

    private inner class HistorySection(title: String) : JPanel(BorderLayout()) {
        private val rows = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; isVisible = false }
        private val arrow = JLabel(AllIcons.General.ArrowRight)
        init {
            isOpaque = false
            val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                isOpaque = false; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(arrow); add(JLabel("<html><b>$title</b></html>"))
                addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) = toggle() })
            }
            add(header, BorderLayout.NORTH); add(rows, BorderLayout.CENTER)
        }
        fun toggle(exp: Boolean = !rows.isVisible) {
            rows.isVisible = exp; arrow.icon = if (exp) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
            revalidate(); repaint()
        }
        fun addRow(c: CommitData) = rows.add(JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.empty(2, 35, 2, 8)
            val text = "<html><span style='color: #6E6E6E;'>${c.time}</span> &nbsp;&nbsp; <span style='color: #3592FF;'>${c.message}</span></html>"
            add(JLabel(text), BorderLayout.CENTER)
        })
    }

    // ================= COMMIT LOGIC (UNCHANGED) =================

    private fun buildMainSplitPane(): JComponent {
        val header = JPanel(BorderLayout()).apply {
            background = JBColor.background(); border = JBUI.Borders.empty(4, 8)
            add(masterCheckbox, BorderLayout.WEST)
            add(createToolbarButton(AllIcons.Actions.Refresh, "Refresh") { refreshChanges() }, BorderLayout.EAST)
        }
        val topPanel = JPanel(BorderLayout()).apply {
            background = JBColor.background(); add(header, BorderLayout.NORTH)
            add(JScrollPane(sectionsContainer).apply { border = JBUI.Borders.empty(); viewport.background = JBColor.background() }, BorderLayout.CENTER)
        }
        val bottomPanel = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            val dateRow = JPanel(FlowLayout(FlowLayout.LEFT, 12, 4)).apply {
                background = JBColor.background()
                add(JLabel("Change Date"))
                dateSpinner.editor = JSpinner.DateEditor(dateSpinner, "dd/MM/yy, h:mm a")
                add(dateSpinner)
            }
            roundedCommitContainer.add(commitMessage, BorderLayout.CENTER)
            add(dateRow, BorderLayout.NORTH); add(roundedCommitContainer, BorderLayout.CENTER)
        }
        return JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanel).apply {
            resizeWeight = 0.6; dividerSize = 2; border = JBUI.Borders.empty()
            ui = object : BasicSplitPaneUI() { override fun createDefaultDivider() = object : BasicSplitPaneDivider(this) { override fun paint(g: Graphics) { g.color = JBColor.border(); g.fillRect(0, 0, width, height) } } }
        }
    }

    private fun buildBottomButtons(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
        background = JBColor.background(); add(commitButton); add(commitPushButton)
    }

    private fun refreshChanges() {
        val proj = project ?: return
        sectionsContainer.removeAll()
        val root = proj.basePath ?: return
        val output = try { GitRunner.runAndCapture(root, listOf("git", "status", "--porcelain")) } catch (e: Exception) { "" }
        val lines = output.lines().filter { it.isNotBlank() }
        val tracked = lines.filter { !it.startsWith("??") }; val untracked = lines.filter { it.startsWith("??") }

        if (tracked.isNotEmpty()) {
            val sec = SectionPanel("Changes", tracked.size)
            tracked.forEach { line -> sec.addRow(createFileRow(File(root, line.substring(3).trim()).absolutePath, line.startsWith("D"), false)) }
            sectionsContainer.add(sec)
        }
        if (untracked.isNotEmpty()) {
            val sec = SectionPanel("Unversioned Files", untracked.size)
            untracked.forEach { line -> sec.addRow(createFileRow(File(root, line.substring(3).trim()).absolutePath, false, true)) }
            sectionsContainer.add(sec)
        }
        updateMasterFromLogic(lines.size)
        sectionsContainer.revalidate(); sectionsContainer.repaint()
    }

    private fun createFileRow(path: String, isDeleted: Boolean, isUnversioned: Boolean): RowPanel {
        val name = File(path).name
        val icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Unknown
        val row = RowPanel(path, icon)
        val color = when { isUnversioned -> "#FF8C82"; isDeleted -> "#6E6E6E"; else -> "#3592FF" }
        row.label.text = "<html><span style='color: $color;'>$name</span> &nbsp;&nbsp;<span style='color: #808080;'>$path</span></html>"
        return row
    }

    private inner class SectionPanel(title: String, count: Int) : JPanel(BorderLayout()) {
        private val rowsContainer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        private val rows = mutableListOf<RowPanel>()
        private val arrow = JLabel(AllIcons.General.ArrowDown)
        init {
            isOpaque = false
            val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                isOpaque = false; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                add(arrow); add(JLabel("<html><b>$title</b> <span style='color: #6E6E6E;'>$count files</span></html>"))
                addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) {
                    rowsContainer.isVisible = !rowsContainer.isVisible
                    arrow.icon = if (rowsContainer.isVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
                } })
            }
            add(header, BorderLayout.NORTH); add(rowsContainer, BorderLayout.CENTER)
        }
        fun addRow(r: RowPanel) { rows.add(r); rowsContainer.add(r) }
        fun setAll(s: Boolean) = rows.forEach { it.checkbox.isSelected = s }
        fun summary() = rows.all { it.checkbox.isSelected } to rows.any { it.checkbox.isSelected }
        fun selectedFiles() = rows.filter { it.checkbox.isSelected }.map { it.filePath }
    }

    private inner class RowPanel(val filePath: String, icon: Icon) : JPanel(BorderLayout()) {
        val checkbox = JCheckBox().apply { isSelected = true; isOpaque = false; border = JBUI.Borders.emptyRight(4) }
        val label = JLabel().apply { isOpaque = false; this.icon = icon; iconTextGap = 6 }
        init {
            isOpaque = false; border = JBUI.Borders.empty(1, 32, 1, 8); maximumSize = Dimension(Int.MAX_VALUE, 26)
            add(checkbox, BorderLayout.WEST); add(label, BorderLayout.CENTER)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { isOpaque = true; background = Color(0xEDF6FF); repaint() }
                override fun mouseExited(e: MouseEvent) { isOpaque = false; repaint() }
                override fun mousePressed(e: MouseEvent) { if (e.clickCount == 1) openFile(filePath) else if (e.clickCount == 2) openDiff(filePath) }
            })
        }
    }

    // ================= UTILS =================

    private fun createToolbarButton(icon: Icon, tip: String, action: () -> Unit) = object : JButton(icon) {
        init {
            isContentAreaFilled = false; isBorderPainted = false; toolTipText = tip; preferredSize = Dimension(28, 28)
            addActionListener { action() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { if(!isOpaque) { isOpaque = true; background = JBUI.CurrentTheme.ActionButton.hoverBackground(); repaint() } }
                override fun mouseExited(e: MouseEvent) { if (tip != "Show/Hide Empty Days" || !showEmptyDates) isOpaque = false; repaint() }
            })
        }
        override fun paintComponent(g: Graphics) {
            if (isOpaque) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = background; g2.fillRoundRect(2, 2, width - 4, height - 4, 6, 6); g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private fun setupCommitEditor() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        commitMessage.apply {
            lineWrap = true; wrapStyleWord = true; isOpaque = false; border = JBUI.Borders.empty(12, 16); foreground = JBColor.GRAY
            font = scheme.getFont(null)
            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) { if (text == placeholderText) { text = ""; foreground = scheme.defaultForeground } }
                override fun focusLost(e: FocusEvent) { if (text.isEmpty()) { text = placeholderText; foreground = JBColor.GRAY } }
            })
        }
    }

    private fun setupActions() {
        masterCheckbox.addActionListener { if (!suppressMasterEvents) sectionsContainer.components.filterIsInstance<SectionPanel>().forEach { it.setAll(masterCheckbox.isSelected) } }
        commitButton.addActionListener { doCommit(false) }; commitPushButton.addActionListener { doCommit(true) }
    }

    private fun updateMasterFromLogic(total: Int) { suppressMasterEvents = true; masterCheckbox.text = "Changes $total files"; suppressMasterEvents = false }
    private fun openFile(p: String) = LocalFileSystem.getInstance().findFileByPath(p)?.let { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project!!).openFile(it, true) }
    private fun openDiff(p: String) = LocalFileSystem.getInstance().findFileByPath(p)?.let { com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction.showDiffForChange(project!!, listOf(com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project!!).getChange(it) ?: return Unit)) }
    private fun registerVcsListener() { project?.let { com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(it).addChangeListListener(object : com.intellij.openapi.vcs.changes.ChangeListListener { override fun changeListUpdateDone() { ApplicationManager.getApplication().invokeLater { refreshChanges() } } }, this) } }
    private fun doCommit(push: Boolean) { /* commit logic */ }
    override fun dispose() {}
    data class CommitData(val hash: String, val message: String, val author: String, val time: String)
}
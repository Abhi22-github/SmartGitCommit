package com.example.smartgitcommit

import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
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
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.ZoneId
import java.util.*
import javax.swing.*
import javax.swing.plaf.basic.BasicSplitPaneDivider
import javax.swing.plaf.basic.BasicSplitPaneUI

class SmartGitPanel(private val project: Project?) : JPanel(BorderLayout()), Disposable {

    private val masterCheckbox = JCheckBox("Changes 0 files", true).apply { isOpaque = false }
    private val placeholderText = "Commit Message"

    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
        isContentAreaFilled = false
        isBorderPainted = false
        toolTipText = "Refresh Changes"
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val sectionsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor.background()
    }

    private val topWrapper = JPanel(BorderLayout()).apply {
        background = JBColor.background()
        add(sectionsContainer, BorderLayout.NORTH)
    }

    private val changesScroll = JScrollPane(topWrapper).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        viewport.background = JBColor.background()
    }

    private val dateSpinner = JSpinner(SpinnerDateModel())
    private val commitMessage = JTextArea(placeholderText)

    private val roundedCommitContainer = object : JPanel(BorderLayout()) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(8)
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = EditorColorsManager.getInstance().globalScheme.defaultBackground
            g2.color = bg
            g2.fillRoundRect(8, 8, width - 16, height - 16, 12, 12)
            g2.color = JBColor.border()
            g2.drawRoundRect(8, 8, width - 16, height - 16, 12, 12)
            g2.dispose()
        }
    }

    private val commitButton = JButton("Commit")
    private val commitPushButton = JButton("Commit and Push…")
    private var suppressMasterEvents = false

    init {
        background = JBColor.background()
        add(buildMainSplitPane(), BorderLayout.CENTER)
        add(buildBottomButtons(), BorderLayout.SOUTH)

        setupCommitEditor()
        setupActions()
        registerVcsListener()
        refreshChanges()
    }

    private fun registerVcsListener() {
        val proj = project ?: return
        ChangeListManager.getInstance(proj).addChangeListListener(
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    ApplicationManager.getApplication().invokeLater { refreshChanges() }
                }
            },
            this
        )
    }

    override fun dispose() {}

    private fun buildMainSplitPane(): JComponent {
        val header = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            border = JBUI.Borders.empty(4, 8)
            add(masterCheckbox, BorderLayout.WEST)
            add(refreshButton, BorderLayout.EAST)
        }

        val topPanel = JPanel(BorderLayout()).apply {
            background = JBColor.background()
            add(header, BorderLayout.NORTH)
            add(changesScroll, BorderLayout.CENTER)
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
            add(dateRow, BorderLayout.NORTH)
            add(roundedCommitContainer, BorderLayout.CENTER)
        }

        return JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanel).apply {
            resizeWeight = 0.6
            dividerSize = 2
            isContinuousLayout = true
            border = JBUI.Borders.empty()
            ui = object : BasicSplitPaneUI() {
                override fun createDefaultDivider(): BasicSplitPaneDivider =
                    object : BasicSplitPaneDivider(this) {
                        override fun paint(g: Graphics) {
                            g.color = JBColor.border()
                            g.fillRect(0, 0, width, height)
                        }
                    }
            }
        }
    }

    private fun buildBottomButtons(): JComponent = JPanel().apply {
        layout = FlowLayout(FlowLayout.LEFT, 8, 8)
        background = JBColor.background()
        add(commitButton)
        add(commitPushButton)
    }

    private fun setupCommitEditor() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        commitMessage.apply {
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty(12, 16)
            background = Color(0, 0, 0, 0)
            foreground = JBColor.GRAY
            caretColor = scheme.defaultForeground
            font = scheme.getFont(null)

            addFocusListener(object : FocusListener {
                override fun focusGained(e: FocusEvent) {
                    if (text == placeholderText) {
                        text = ""
                        foreground = scheme.defaultForeground
                    }
                }
                override fun focusLost(e: FocusEvent) {
                    if (text.isEmpty()) {
                        text = placeholderText
                        foreground = JBColor.GRAY
                    }
                }
            })
        }
    }

    private fun setupActions() {
        masterCheckbox.addActionListener {
            if (suppressMasterEvents) return@addActionListener
            sectionsContainer.components.filterIsInstance<SectionPanel>().forEach {
                it.setAll(masterCheckbox.isSelected)
            }
        }
        refreshButton.addActionListener { refreshChanges() }
        commitButton.addActionListener { doCommit(false) }
        commitPushButton.addActionListener { doCommit(true) }
    }

    private fun refreshChanges() {
        val proj = project ?: return
        sectionsContainer.removeAll()

        val root = proj.basePath ?: return
        val output = try {
            GitRunner.runAndCapture(root, listOf("git", "status", "--porcelain"))
        } catch (e: Exception) {
            showNotification("Error", "Git status failed", NotificationType.ERROR)
            ""
        }

        val lines = output.lines().filter { it.isNotBlank() }
        val unversionedLines = lines.filter { it.startsWith("??") }
        val trackedLines = lines.filter { !it.startsWith("??") }

        if (trackedLines.isNotEmpty()) {
            val section = SectionPanel("Changes", trackedLines.size)
            trackedLines.forEach { line ->
                val relativePath = line.substring(3).trim()
                val absolutePath = File(root, relativePath).absolutePath
                val isDeleted = line.startsWith(" D") || line.startsWith("D ")
                section.addRow(createFileRow(absolutePath, isDeleted = isDeleted, isUnversioned = false))
            }
            sectionsContainer.add(section)
        }

        if (unversionedLines.isNotEmpty()) {
            val section = SectionPanel("Unversioned Files", unversionedLines.size)
            unversionedLines.forEach { line ->
                val relativePath = line.substring(3).trim()
                val absolutePath = File(root, relativePath).absolutePath
                section.addRow(createFileRow(absolutePath, isDeleted = false, isUnversioned = true))
            }
            sectionsContainer.add(section)
        }

        updateMasterFromLogic(lines.size)
        sectionsContainer.revalidate()
        sectionsContainer.repaint()
    }

    private fun createFileRow(path: String, isDeleted: Boolean, isUnversioned: Boolean): RowPanel {
        val file = File(path)
        val name = file.name
        val parentDir = file.parent ?: ""

        val fileTypeIcon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
        val row = RowPanel(path, fileTypeIcon ?: AllIcons.FileTypes.Unknown)
        row.checkbox.isSelected = true

        val statusColor = when {
            isUnversioned -> "#FF8C82"
            isDeleted -> "#6E6E6E"
            else -> "#3592FF"
        }

        row.label.text = "<html><nobr><span style='color: $statusColor;'>$name</span>" +
                "<span style='color: #808080;'>&nbsp;&nbsp;$parentDir</span></nobr></html>"

        row.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.clickCount == 1) {
                    openFile(path)
                } else if (e.clickCount == 2) {
                    openDiff(path)
                }
            }
        })

        return row
    }

    private fun openFile(path: String) {
        val vf = LocalFileSystem.getInstance().findFileByPath(path)
        if (vf != null && project != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }


    private fun openDiff(path: String) {
        val proj = project ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return

        // Get the Change object from IntelliJ's VCS system
        val change = ChangeListManager.getInstance(proj).getChange(vf)

        if (change != null) {
            // This handles the comparison between local and the last committed version
            val changes = listOf(change)
            ShowDiffAction.showDiffForChange(proj, changes)
        } else {
            // If it's unversioned or the above still fails, fallback to simple file open
            openFile(path)
        }
    }

    private fun updateMasterFromLogic(total: Int) {
        suppressMasterEvents = true
        val sections = sectionsContainer.components.filterIsInstance<SectionPanel>()
        val count = if (total >= 0) total else sections.sumOf { it.rowCount }
        masterCheckbox.text = "Changes $count files"

        var all = true
        var any = false
        sections.forEach {
            val (sAll, sAny) = it.summary()
            all = all && sAll
            any = any || sAny
        }
        masterCheckbox.isSelected = all && any
        suppressMasterEvents = false
    }

    private fun doCommit(push: Boolean) {
        val msg = commitMessage.text.trim()
        val proj = project ?: return

        if (msg.isEmpty() || msg == placeholderText) {
            showNotification("Commit Message Required", "Please enter a message.", NotificationType.WARNING)
            return
        }

        val selectedPaths = sectionsContainer.components.filterIsInstance<SectionPanel>()
            .flatMap { it.selectedFiles() }

        if (selectedPaths.isEmpty()) {
            showNotification("No Files Selected", "Select files to commit.", NotificationType.WARNING)
            return
        }

        val spinnerDate = dateSpinner.value as Date
        val localDateTime = spinnerDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                GitRunner.commitSelectedFiles(proj, selectedPaths, msg, localDateTime)
                if (push) GitRunner.push(proj)

                ApplicationManager.getApplication().invokeLater {
                    commitMessage.text = placeholderText
                    commitMessage.foreground = JBColor.GRAY
                    VcsDirtyScopeManager.getInstance(proj).markEverythingDirty()
                    refreshChanges()
                    showNotification("Git Success", "Commit completed successfully.", NotificationType.INFORMATION)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    showNotification("Git Error", e.message ?: "Action failed", NotificationType.ERROR)
                }
            }
        }
    }

    private fun showNotification(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Smart Git Notifications")
            .createNotification(title, content, type)
            .notify(project)
    }

    // ================= INNER COMPONENTS =================

    private inner class SectionPanel(title: String, count: Int) : JPanel(BorderLayout()) {
        private val rowsContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        private val rows = mutableListOf<RowPanel>()
        private var isExpanded = true
        private val arrowLabel = JLabel(AllIcons.General.ArrowDown)

        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            val header = JPanel(GridBagLayout()).apply {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                val gbc = GridBagConstraints().apply {
                    anchor = GridBagConstraints.WEST
                    insets = JBUI.insets(2, 4)
                }
                add(arrowLabel, gbc)

                gbc.weightx = 1.0
                gbc.insets = JBUI.insets(2, 0, 2, 4)
                add(JLabel("<html><b>$title</b> <span style='color: #6E6E6E;'>$count ${if (count == 1) "file" else "files"}</span></html>"), gbc)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        isExpanded = !isExpanded
                        arrowLabel.icon = if (isExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
                        rowsContainer.isVisible = isExpanded
                        revalidate()
                        repaint()
                    }
                })
            }
            add(header, BorderLayout.NORTH)
            add(rowsContainer, BorderLayout.CENTER)
        }
        fun addRow(row: RowPanel) { rows.add(row); rowsContainer.add(row) }
        fun setAll(sel: Boolean) = rows.forEach { it.checkbox.isSelected = sel }
        fun summary(): Pair<Boolean, Boolean> = rows.all { it.checkbox.isSelected } to rows.any { it.checkbox.isSelected }
        fun selectedFiles(): List<String> = rows.filter { it.checkbox.isSelected }.map { it.filePath }
        val rowCount get() = rows.size
    }

    private inner class RowPanel(val filePath: String, icon: Icon) : JPanel(BorderLayout()) {
        val checkbox = JCheckBox().apply {
            isOpaque = false
            border = JBUI.Borders.emptyRight(4)
        }
        val label = JLabel().apply {
            isOpaque = false
            this.icon = icon
            iconTextGap = 6
        }

        private val hoverColor = JBColor.namedColor("Table.selectionBackground", Color(0xEDF6FF))

        init {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(1, 20, 1, 8)
            add(checkbox, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, 26)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) {
                    isOpaque = true
                    background = hoverColor
                    repaint()
                }
                override fun mouseExited(e: MouseEvent) {
                    isOpaque = false
                    repaint()
                }
            })
        }
    }
}
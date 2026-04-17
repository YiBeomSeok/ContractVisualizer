package org.bmsk.contractvisualizer.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiTreeChangeAdapter
import com.intellij.psi.PsiTreeChangeEvent
import com.intellij.util.Alarm
import org.bmsk.contractvisualizer.mermaid.MermaidGenerator
import org.bmsk.contractvisualizer.parser.PsiContractParser
import org.bmsk.contractvisualizer.toolwindow.LayoutMode
import org.bmsk.contractvisualizer.toolwindow.MermaidRenderPanel
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

class ContractDiagramEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val renderPanel = MermaidRenderPanel()
    private var lastMermaidCode: String? = null
    private val updateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val mainPanel = JPanel(BorderLayout()).apply {
        add(createToolbar().component, BorderLayout.NORTH)
        add(renderPanel, BorderLayout.CENTER)
    }

    init {
        updateDiagram()

        val psiManager = PsiManager.getInstance(project)
        psiManager.addPsiTreeChangeListener(object : PsiTreeChangeAdapter() {
            override fun childrenChanged(event: PsiTreeChangeEvent) {
                if (event.file?.virtualFile == file) {
                    updateAlarm.cancelAllRequests()
                    updateAlarm.addRequest({ updateDiagram() }, 300)
                }
            }
        }, this)
    }

    private fun updateDiagram() {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return
        val contract = PsiContractParser.parse(psiFile)
        if (contract != null) {
            lastMermaidCode = MermaidGenerator.generate(contract)
            renderPanel.updateContent(contract)
        } else {
            lastMermaidCode = null
            renderPanel.showEmptyState()
        }
    }

    private fun createToolbar(): ActionToolbar {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Toggle Layout", "Switch layout mode", com.intellij.icons.AllIcons.Actions.SplitHorizontally) {
                override fun actionPerformed(e: AnActionEvent) {
                    renderPanel.toggleLayout()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.icon = when (renderPanel.currentLayout) {
                        LayoutMode.HORIZONTAL -> com.intellij.icons.AllIcons.Actions.SplitHorizontally
                        LayoutMode.VERTICAL -> com.intellij.icons.AllIcons.Actions.SplitVertically
                        LayoutMode.FLOW -> com.intellij.icons.AllIcons.Actions.PreviewDetails
                    }
                    e.presentation.text = when (renderPanel.currentLayout) {
                        LayoutMode.HORIZONTAL -> "Horizontal Layout"
                        LayoutMode.VERTICAL -> "Vertical Layout"
                        LayoutMode.FLOW -> "Flow Layout"
                    }
                }
            })

            add(object : AnAction("Fit to View", "Fit diagram to panel", com.intellij.icons.AllIcons.General.FitContent) {
                override fun actionPerformed(e: AnActionEvent) {
                    renderPanel.fitToView()
                }
            })

            add(object : AnAction("Copy Mermaid", "Copy Mermaid code to clipboard", com.intellij.icons.AllIcons.Actions.Copy) {
                override fun actionPerformed(e: AnActionEvent) {
                    val code = lastMermaidCode ?: return
                    CopyPasteManager.getInstance().setContents(StringSelection(code))
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = lastMermaidCode != null
                }
            })
        }

        return ActionManager.getInstance()
            .createActionToolbar("ContractDiagramEditor", actionGroup, true)
            .apply { targetComponent = mainPanel }
    }

    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent = renderPanel
    override fun getName(): String = "Contract Diagram"
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}

package org.bmsk.contractvisualizer.editor

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview

class ContractSplitEditor(
    textEditor: TextEditor,
    diagramEditor: ContractDiagramEditor,
) : TextEditorWithPreview(textEditor, diagramEditor, "Contract Visualizer")

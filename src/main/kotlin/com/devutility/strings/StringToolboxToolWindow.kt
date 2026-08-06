package com.devutility.strings

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class StringToolboxToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = StringToolboxPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

object StringToolboxToolWindow {

    const val ID = "String Toolbox"

    fun show(project: Project, initialInput: String? = null) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? StringToolboxPanel }
            if (initialInput != null) panel?.setInput(initialInput)
        }
    }
}

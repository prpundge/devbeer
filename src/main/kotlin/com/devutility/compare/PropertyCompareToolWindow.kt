package com.devutility.compare

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class PropertyCompareToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PropertyComparePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

object PropertyCompareToolWindow {

    const val ID = "Property Compare"

    /** Opens the tool window and, if given files, loads them into the two sides. */
    fun show(project: Project, left: VirtualFile? = null, right: VirtualFile? = null) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? PropertyComparePanel }
            if (left != null || right != null) panel?.load(left, right)
        }
    }
}

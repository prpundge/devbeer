package com.devutility.json

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

private const val NOTIFICATION_GROUP = "DevBeer"

/** Tools | DevBeer | JSON Formatter */
class OpenJsonFormatterAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        JsonToolWindow.show(e.project ?: return)
    }
}

/**
 * Reformats JSON in place — the selection if there is one, otherwise the whole file — as a
 * single undoable command. Syntax errors are reported with their position instead of mangling
 * the document.
 */
class FormatJsonAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && editor.document.isWritable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val model = editor.selectionModel

        val start = if (model.hasSelection()) model.selectionStart else 0
        val end = if (model.hasSelection()) model.selectionEnd else editor.document.textLength
        val source = editor.document.getText(com.intellij.openapi.util.TextRange(start, end))

        val formatted = try {
            // Lenient on input so hand-edited config files with comments still reformat,
            // while the output is always strict JSON.
            JsonFormatter.format(source, JsonFormatOptions(lenient = true))
        } catch (ex: JsonSyntaxException) {
            notify(project, "Not valid JSON", "${ex.reason} at line ${ex.line}, column ${ex.column}")
            return
        } catch (ex: Exception) {
            notify(project, "Could not format JSON", ex.message ?: ex.javaClass.simpleName)
            return
        }

        WriteCommandAction.runWriteCommandAction(project, "Format JSON", "DevBeer", {
            editor.document.replaceString(start, end, formatted)
        })
    }

    private fun notify(project: Project?, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.WARNING)
            .notify(project)
    }
}

package gov.anzong.fim.completion.startup

import com.intellij.ide.DataManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.registry.Registry
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ForceDisableBuiltInCompletionActivity : ProjectActivity {
    private val log = Logger.getInstance(ForceDisableBuiltInCompletionActivity::class.java)

    // 独立的防抖调度器，专为强行唤醒服务
    private val debounceExecutor = Executors.newSingleThreadScheduledExecutor()
    private var lastSchedule: ScheduledFuture<*>? = null

    override suspend fun execute(project: Project) {
        // 1. 恢复官方底层总闸（保证底层的 Tab 键总监听器不被注销）
        try {
            val inlineRegistry = Registry.get("inline.completion.enabled")
            if (!inlineRegistry.asBoolean()) inlineRegistry.setValue(true)
            PropertiesComponent.getInstance().setValue("ml.completion.full.line.enabled", true)
        } catch (e: Exception) {}
        // 2. 注入全局键盘监听器
        val eventMulticaster = EditorFactory.getInstance().eventMulticaster
        eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val newLength = event.newLength
                val oldLength = event.oldLength

                // 正常单字符打字不干预，仅在退格删除或 Tab 采纳时强行干预
                if (newLength == 1 && oldLength == 0) return

                lastSchedule?.cancel(false)
                lastSchedule = debounceExecutor.schedule({
                    ApplicationManager.getApplication().invokeLater {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
                        if (editor.document != event.document) return@invokeLater

                        val actionManager = ActionManager.getInstance()
                        val actionIds = listOf("CallInlineCompletionAction", "EditorShowInlineCompletion")
                        val triggerAction = actionIds.firstNotNullOfOrNull { actionManager.getAction(it) }

                        if (triggerAction != null) {
                            println("[AnzongFIM-Hacker] 强制唤醒 IDEA 被隐藏的 Inline 事件")

                            // 🌟 核心修复：直接使用官方标准的同步 DataManager 获取合规的 DataContext
                            val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
                            val actionEvent = AnActionEvent.createFromDataContext(ActionPlaces.UNKNOWN, Presentation(), dataContext)
                            ActionUtil.performActionDumbAwareWithCallbacks(triggerAction, actionEvent)
                        }
                    }
                }, 400, TimeUnit.MILLISECONDS)
            }
        }, project)

        log.info("AnzongFIM: Global Hacker Listener injected successfully.")
    }
}
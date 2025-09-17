@file:OptIn(FlowPreview::class, ExperimentalJewelApi::class, InternalJewelApi::class)

package org.jetbrains.plugins.template.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.task.ProjectTaskManager
import com.intellij.ui.content.ContentFactory
import com.intellij.util.application
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.jetbrains.jewel.bridge.LocalComponent
import org.jetbrains.jewel.bridge.actionSystem.RootDataProviderModifier
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi
import org.jetbrains.jewel.ui.component.Text
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel

@ExperimentalJewelApi
class ComposePreviewToolWindowFactory : ToolWindowFactory {

    private val watcher = ComposableModificationWatcher

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val wrapperPanel = JewelComposePanelWrapper()

        val composePanel = ComposePanel()

        composePanel.setContent(wrapperPanel) {
            Column(
                Modifier.background(Color.White)
            ) {

                Text(
                    "Preview will appear here\nPreview will appear here\nPreview will appear here\nPreview will appear here\nPreview will appear here\n",
                )
            }
        }

        wrapperPanel.add(composePanel, BorderLayout.CENTER)

        val toolWindowContent = contentFactory.createContent(wrapperPanel, "", false)
        toolWindow.contentManager.addContent(toolWindowContent)

        val coroutineScope = project.service<MyCoroutineScopeHolder>().coroutineScope
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            watcher.observeEditorContentChanges(toolWindow.disposable).collect { (text, virtualFile) ->
                compileCode(virtualFile, project)

                withContext(Dispatchers.EDT) {
                    composePanel.setContent(wrapperPanel) {
                        Column() {
                            Text(text, Modifier.wrapContentSize())
                        }
                    }
                }
            }
        }
    }
}

fun compileCode(fileToCompile: VirtualFile, project: Project) {
    val module = ModuleUtilCore.findModuleForFile(fileToCompile, project) ?: return

    application.invokeLater {
        if (project.isDisposed) return@invokeLater
        if (module.isDisposed) return@invokeLater
        if (!fileToCompile.isValid) return@invokeLater

        ProjectTaskManager.getInstance(project).compile(fileToCompile).onSuccess {
            project.service<MyCoroutineScopeHolder>().coroutineScope.launch {
                println("Compiled successfully")

                val allPaths = readAction {
                    ModuleUtilCore.findModuleForFile(fileToCompile, project)!!
                    OrderEnumerator.orderEntries(module)
                        .recursively().withoutSdk().pathsList.pathList
                }

                println(allPaths)
            }
        }
    }
}

private fun ComposePanel.setContent(wrapperPanel: JewelComposePanelWrapper, content: @Composable () -> Unit) {
    setContent {
        SwingBridgeTheme {
            CompositionLocalProvider(LocalComponent provides this@setContent) {
                ComponentDataProviderBridge(wrapperPanel, content = content)
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
fun ComponentDataProviderBridge(
    component: JewelComposePanelWrapper,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val rootDataProviderModifier = remember { RootDataProviderModifier() }

    Box(modifier = Modifier.then(rootDataProviderModifier).then(modifier)) { content() }

    DisposableEffect(component) {
        component.targetProvider = rootDataProviderModifier

        onDispose {
            if (component.targetProvider == rootDataProviderModifier) {
                component.targetProvider = null
            }
        }
    }
}

class JewelComposePanelWrapper : JPanel(), UiDataProvider {
    internal var targetProvider: UiDataProvider? = null

    val composePanel: ComposePanel
        get() =
            components.singleOrNull() as? ComposePanel
                ?: error("JewelComposePanelWrapper was not initialized with a ComposePanel")

    override fun addImpl(comp: Component, constraints: Any?, index: Int) {
        require(components.isEmpty()) {
            "JewelComposePanelWrapper can only contain a single ComposePanel, attempt to add another component"
        }

        require(comp is ComposePanel) {
            "JewelComposePanelWrapper can only contain ComposePanel, attempt to add ${comp::class.java.name}"
        }

        super.addImpl(comp, constraints, index)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        targetProvider?.uiDataSnapshot(sink)
    }
}

object ComposableModificationWatcher {
    fun observeEditorContentChanges(disposable: Disposable): Flow<Pair<String, VirtualFile>> {
        return callbackFlow {
            val listener = object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val document = event.document
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return

                    trySend(document.text to vf!!)
                }
            }

            EditorFactory.getInstance().eventMulticaster.addDocumentListener(listener, disposable)

            val editorOpenListener = object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    super.editorCreated(event)

                    val document = event.editor.document
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    trySend(document.text to vf)
                }
            }

            EditorFactory.getInstance().addEditorFactoryListener(editorOpenListener, disposable)
            awaitClose {
                EditorFactory.getInstance().eventMulticaster.removeDocumentListener(listener)
            }
        }
    }
}
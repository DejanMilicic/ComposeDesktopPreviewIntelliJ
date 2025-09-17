package org.jetbrains.plugins.template.preview

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.PROJECT)
internal class MyCoroutineScopeHolder(val project: Project, val coroutineScope: CoroutineScope)
// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diagnostic

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Attachment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import java.awt.event.InputEvent
import java.util.*

private const val TEST_LOGGER = "TEST.LOGGER"
private const val TEST_MESSAGE = "test exception; please ignore"

class DropAnErrorAction : DumbAwareAction("Drop an error") {
  override fun actionPerformed(e: AnActionEvent) {
    Logger.getInstance(TEST_LOGGER).error(TEST_MESSAGE, Exception())
  }
}

class DropAnErrorWithAttachmentsAction : DumbAwareAction("Drop an error with attachments", "Hold down SHIFT for multiple attachments", null) {
  override fun actionPerformed(e: AnActionEvent) {
    val attachments = if (e.modifiers and InputEvent.SHIFT_MASK == 0) {
      arrayOf(Attachment("attachment.txt", "content"))
    }
    else {
      arrayOf(Attachment("first.txt", "content"), Attachment("second.txt", "more content"), Attachment("third.txt", "even more content"))
    }
    Logger.getInstance(TEST_LOGGER).error(TEST_MESSAGE, Exception(), *attachments)
  }
}

class DropPluginErrorAction : DumbAwareAction("Drop an error in a random plugin") {
  override fun actionPerformed(e: AnActionEvent) {
    val plugins = PluginManager.getPlugins()
    val victim = plugins[Random().nextInt(plugins.size)]
    Logger.getInstance(TEST_LOGGER).error(TEST_MESSAGE, PluginException(TEST_MESSAGE, victim.pluginId))
  }
}

class DropAnOutOfMemoryErrorAction : DumbAwareAction("Drop an OutOfMemoryError", "Hold down SHIFT for OOME in Metaspace", null) {
  override fun actionPerformed(e: AnActionEvent) {
    if (e.modifiers and InputEvent.SHIFT_MASK == 0) {
      val array = arrayOfNulls<Any>(Integer.MAX_VALUE)
      for (i in array.indices) {
        array[i] = arrayOfNulls<Any>(Integer.MAX_VALUE)
      }
      throw OutOfMemoryError()
    }
    else {
      throw OutOfMemoryError("foo Metaspace foo")
    }
  }
}
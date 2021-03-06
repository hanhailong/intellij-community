// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.diagnostic

import com.intellij.CommonBundle
import com.intellij.credentialStore.hasOnlyUserName
import com.intellij.errorreport.bean.ErrorBean
import com.intellij.errorreport.error.InternalEAPException
import com.intellij.errorreport.error.NoSuchEAPUserException
import com.intellij.errorreport.error.UpdateAvailableException
import com.intellij.ide.DataManager
import com.intellij.ide.plugins.PluginManager
import com.intellij.idea.IdeaLogger
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.util.Consumer
import com.intellij.xml.util.XmlStringUtil
import java.awt.Component
import java.lang.Exception
import javax.swing.Icon

private var previousExceptionThreadId = 0

open class ITNReporter : ErrorReportSubmitter() {
  override fun getReportActionText(): String = DiagnosticBundle.message("error.report.to.jetbrains.action")

  override fun submit(events: Array<IdeaLoggingEvent>,
                      additionalInfo: String?,
                      parentComponent: Component,
                      consumer: Consumer<SubmittedReportInfo>): Boolean {
    return submit(events[0], parentComponent, consumer, ErrorBean(events[0].throwable, IdeaLogger.ourLastActionId), additionalInfo)
  }

  /**
   * Used to enable error reporting even in release versions.
   */
  open fun showErrorInRelease(event: IdeaLoggingEvent) = false
}

fun setPluginInfo(event: IdeaLoggingEvent, errorBean: ErrorBean) {
  val t = event.throwable
  if (t != null) {
    val pluginId = IdeErrorsDialog.findPluginId(t)
    if (pluginId != null) {
      val ideaPluginDescriptor = PluginManager.getPlugin(pluginId)
      if (ideaPluginDescriptor != null && (!ideaPluginDescriptor.isBundled || ideaPluginDescriptor.allowBundledUpdate())) {
        errorBean.pluginName = ideaPluginDescriptor.name
        errorBean.pluginVersion = ideaPluginDescriptor.version
      }
    }
  }
}

private fun showMessageDialog(parentComponent: Component, project: Project?, message: String, title: String, icon: Icon) {
  if (parentComponent.isShowing) {
    Messages.showMessageDialog(parentComponent, message, title, icon)
  }
  else {
    Messages.showMessageDialog(project, message, title, icon)
  }
}

private fun submit(event: IdeaLoggingEvent,
                   parentComponent: Component,
                   callback: Consumer<SubmittedReportInfo>,
                   errorBean: ErrorBean,
                   description: String?): Boolean {
  var credentials = ErrorReportConfigurable.getCredentials()
  if (credentials.hasOnlyUserName()) {
    // ask password only if user name was specified
    if (!showJetBrainsAccountDialog(parentComponent).showAndGet()) {
      return false
    }

    credentials = ErrorReportConfigurable.getCredentials()
  }

  errorBean.description = description
  errorBean.message = event.message

  if (previousExceptionThreadId != 0) {
    errorBean.previousException = previousExceptionThreadId
  }

  setPluginInfo(event, errorBean)

  val data = event.data
  if (data is AbstractMessage) {
    errorBean.assigneeId = data.assigneeId
    errorBean.attachments = data.includedAttachments
  }

  val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(parentComponent))
  ITNProxy.sendError(project, credentials?.userName, credentials?.getPasswordAsString(), errorBean,
                     { threadId -> onSuccess(threadId, callback, project) },
                     { e -> onError(e, parentComponent, project, callback, event, errorBean, description) })
  return true
}

private fun onSuccess(threadId: Int, callback: Consumer<SubmittedReportInfo>, project: Project?) {
  previousExceptionThreadId = threadId
  val linkText = threadId.toString()
  val reportInfo = SubmittedReportInfo(ITNProxy.getBrowseUrl(threadId), linkText, SubmittedReportInfo.SubmissionStatus.NEW_ISSUE)
  callback.consume(reportInfo)
  ApplicationManager.getApplication().invokeLater {
    val text = StringBuilder()
    IdeErrorsDialog.appendSubmissionInformation(reportInfo, text)
    text.append('.').append("<br/>").append(DiagnosticBundle.message("error.report.gratitude"))
    val content = XmlStringUtil.wrapInHtml(text)
    ReportMessages.GROUP.createNotification(ReportMessages.ERROR_REPORT, content, NotificationType.INFORMATION,
                                            NotificationListener.URL_OPENING_LISTENER).setImportant(false).notify(project)
  }
}

private fun onError(e: Exception,
                    parentComponent: Component,
                    project: Project?,
                    callback: Consumer<SubmittedReportInfo>,
                    event: IdeaLoggingEvent,
                    errorBean: ErrorBean,
                    description: String?) {
  Logger.getInstance(ITNReporter::class.java).info("reporting failed: $e")
  ApplicationManager.getApplication().invokeLater {
    val msg = when (e) {
      is NoSuchEAPUserException -> DiagnosticBundle.message("error.report.authentication.failed")
      is InternalEAPException -> DiagnosticBundle.message("error.report.posting.failed", e.message)
      else -> DiagnosticBundle.message("error.report.sending.failure")
    }

    if (e is UpdateAvailableException) {
      val message = DiagnosticBundle.message("error.report.new.eap.build.message", e.message)
      showMessageDialog(parentComponent, project, message, CommonBundle.getWarningTitle(), Messages.getWarningIcon())
      callback.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED))
    }
    else if (!MessageDialogBuilder.yesNo(ReportMessages.ERROR_REPORT, msg).project(project).isYes) {
      callback.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.FAILED))
    }
    else {
      if (e is NoSuchEAPUserException) {
        showJetBrainsAccountDialog(parentComponent, project).show()
      }
      ApplicationManager.getApplication().invokeLater { submit(event, parentComponent, callback, errorBean, description) }
    }
  }
}

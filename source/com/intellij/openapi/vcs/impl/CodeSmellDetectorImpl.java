package com.intellij.openapi.vcs.impl;

import com.intellij.codeInsight.CodeSmellInfo;
import com.intellij.codeInsight.daemon.HighlightDisplayKey;
import com.intellij.codeInsight.daemon.impl.*;
import com.intellij.ide.errorTreeView.NewErrorTreeViewPanel;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vcs.AbstractVcsHelper;
import com.intellij.openapi.vcs.CodeSmellDetector;
import com.intellij.openapi.vcs.VcsBundle;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.ui.MessageCategory;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author yole
 */
public class CodeSmellDetectorImpl extends CodeSmellDetector {
  private Project myProject;

  public CodeSmellDetectorImpl(final Project project) {
    myProject = project;
  }

  public void showCodeSmellErrors(final List<CodeSmellInfo> smellList) {
    Collections.sort(smellList, new Comparator<CodeSmellInfo>() {
      public int compare(final CodeSmellInfo o1, final CodeSmellInfo o2) {
        return o1.getTextRange().getStartOffset() - o2.getTextRange().getStartOffset();
      }
    });

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      public void run() {
        if (myProject.isDisposed()) return;
        if (smellList.isEmpty()) {
          return;
        }

        final VcsErrorViewPanel errorTreeView = new VcsErrorViewPanel(myProject);
        AbstractVcsHelperImpl helper = (AbstractVcsHelperImpl)AbstractVcsHelper.getInstance(myProject);
        helper.openMessagesView(errorTreeView, VcsBundle.message("code.smells.error.messages.tab.name"));

        FileDocumentManager fileManager = FileDocumentManager.getInstance();

        for (CodeSmellInfo smellInfo : smellList) {
          final VirtualFile file = fileManager.getFile(smellInfo.getDocument());
          final OpenFileDescriptor navigatable =
            new OpenFileDescriptor(myProject, file, smellInfo.getStartLine(), smellInfo.getStartColumn());
          final String exportPrefix = NewErrorTreeViewPanel.createExportPrefix(smellInfo.getStartLine() + 1);
          final String rendererPrefix =
            NewErrorTreeViewPanel.createRendererPrefix(smellInfo.getStartLine() + 1, smellInfo.getStartColumn() + 1);
          if (smellInfo.getSeverity() == HighlightSeverity.ERROR) {
            errorTreeView.addMessage(MessageCategory.ERROR, new String[]{smellInfo.getDescription()}, file.getPresentableUrl(), navigatable,
                                     exportPrefix, rendererPrefix, null);
          }
          else {//if (smellInfo.getSeverity() == HighlightSeverity.WARNING) {
            errorTreeView.addMessage(MessageCategory.WARNING, new String[]{smellInfo.getDescription()}, file.getPresentableUrl(),
                                     navigatable, exportPrefix, rendererPrefix, null);
          }

        }
      }
    });

  }


  public List<CodeSmellInfo> findCodeSmells(final List<VirtualFile> filesToCheck) throws ProcessCanceledException {
    final List<CodeSmellInfo> result = new ArrayList<CodeSmellInfo>();
    final PsiManager manager = PsiManager.getInstance(myProject);
    final FileDocumentManager fileManager = FileDocumentManager.getInstance();
    PsiDocumentManager.getInstance(myProject).commitAllDocuments();

    boolean completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(new Runnable() {
      public void run() {
        @Nullable final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
        for (int i = 0; i < filesToCheck.size(); i++) {

          if (progress != null && progress.isCanceled()) throw new ProcessCanceledException();

          VirtualFile file = filesToCheck.get(i);

          if (progress != null) {
            progress.setText(VcsBundle.message("searching.for.code.smells.processing.file.progress.text", file.getPresentableUrl()));
            progress.setFraction((double)i / (double)filesToCheck.size());
          }

          final PsiFile psiFile = manager.findFile(file);
          if (psiFile != null) {
            final Document document = fileManager.getDocument(file);
            if (document != null) {
              final List<CodeSmellInfo> codeSmells = findCodeSmells(psiFile, progress, document);
              result.addAll(codeSmells);
            }
          }
        }
      }
    }, VcsBundle.message("checking.code.smells.progress.title"), true, myProject);

    if (!completed) throw new ProcessCanceledException();

    return result;
  }

  private List<CodeSmellInfo> findCodeSmells(final PsiFile psiFile, final ProgressIndicator progress, final Document document) {

    final List<CodeSmellInfo> result = new ArrayList<CodeSmellInfo>();

    GeneralHighlightingPass action1 = new GeneralHighlightingPass(myProject, psiFile, document, 0, psiFile.getTextLength(), true);
    action1.doCollectInformation(progress);

    collectErrorsAndWarnings(action1.getHighlights(), result, document);

    PostHighlightingPass action2 = new PostHighlightingPass(myProject, psiFile, document, 0, psiFile.getTextLength());
    action2.doCollectInformation(progress);

    collectErrorsAndWarnings(action2.getHighlights(), result, document);

    LocalInspectionsPass action3 = new LocalInspectionsPass(psiFile, document, 0, psiFile.getTextLength());
    action3.doCollectInformation(progress);

    collectErrorsAndWarnings(action3.getHighlights(), result, document);

    return result;

  }

  private void collectErrorsAndWarnings(final Collection<HighlightInfo> highlights,
                                               final List<CodeSmellInfo> result,
                                               final Document document) {
    if (highlights == null) return;
    for (HighlightInfo highlightInfo : highlights) {
      final HighlightSeverity severity = highlightInfo.getSeverity();
      if (SeverityRegistrar.getInstance(myProject).compare(severity, HighlightSeverity.WARNING) >= 0) {
        result.add(new CodeSmellInfo(document, getDescription(highlightInfo),
                                     new TextRange(highlightInfo.startOffset, highlightInfo.endOffset), severity));
      }
    }
  }

  private static String getDescription(final HighlightInfo highlightInfo) {
    final String description = highlightInfo.description;
    final HighlightInfoType type = highlightInfo.type;
    if (type instanceof HighlightInfoType.HighlightInfoTypeSeverityByKey) {
      final HighlightDisplayKey severityKey = ((HighlightInfoType.HighlightInfoTypeSeverityByKey)type).getSeverityKey();
      final String id = severityKey.getID();
      if (id != null) {
        return "[" + id + "] " + description;
      }
    }
    return description;
  }


}
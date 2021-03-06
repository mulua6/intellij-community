/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.execution.ui;

import com.intellij.execution.*;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.layout.impl.DockableGridContainerFactory;
import com.intellij.ide.DataManager;
import com.intellij.ide.impl.ContentManagerWatcher;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerAdapter;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.ui.AppUIUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.LayeredIcon;
import com.intellij.ui.content.*;
import com.intellij.ui.docking.DockManager;
import com.intellij.util.SmartList;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.GraphicsUtil;
import com.intellij.util.ui.UIUtil;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.util.*;
import java.util.List;

public class RunContentManagerImpl implements RunContentManager, Disposable {
  public static final Key<Boolean> ALWAYS_USE_DEFAULT_STOPPING_BEHAVIOUR_KEY = Key.create("ALWAYS_USE_DEFAULT_STOPPING_BEHAVIOUR_KEY");
  private static final Logger LOG = Logger.getInstance(RunContentManagerImpl.class);
  private static final Key<RunContentDescriptor> DESCRIPTOR_KEY = Key.create("Descriptor");

  private final Project myProject;
  private final Map<String, ContentManager> myToolwindowIdToContentManagerMap = new THashMap<String, ContentManager>();
  private final Map<String, Icon> myToolwindowIdToBaseIconMap = new THashMap<String, Icon>();

  private final Map<RunContentListener, Disposable> myListeners = new THashMap<RunContentListener, Disposable>();
  private final LinkedList<String> myToolwindowIdZBuffer = new LinkedList<String>();

  public RunContentManagerImpl(@NotNull Project project, @NotNull DockManager dockManager) {
    myProject = project;
    DockableGridContainerFactory containerFactory = new DockableGridContainerFactory();
    dockManager.register(DockableGridContainerFactory.TYPE, containerFactory);
    Disposer.register(myProject, containerFactory);

    AppUIUtil.invokeOnEdt(new Runnable() {
      @Override
      public void run() {
        init();
      }
    }, myProject.getDisposed());
  }

  // must be called on EDT
  private void init() {
    ToolWindowManagerEx toolWindowManager = ToolWindowManagerEx.getInstanceEx(myProject);
    if (toolWindowManager == null) {
      return;
    }

    for (Executor executor : ExecutorRegistry.getInstance().getRegisteredExecutors()) {
      registerToolwindow(executor, toolWindowManager);
    }

    toolWindowManager.addToolWindowManagerListener(new ToolWindowManagerAdapter() {
      @Override
      public void stateChanged() {
        if (myProject.isDisposed()) {
          return;
        }

        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(myProject);
        Set<String> currentWindows = new THashSet<String>();
        ContainerUtil.addAll(currentWindows, toolWindowManager.getToolWindowIds());
        myToolwindowIdZBuffer.retainAll(currentWindows);

        final String activeToolWindowId = toolWindowManager.getActiveToolWindowId();
        if (activeToolWindowId != null) {
          if (myToolwindowIdZBuffer.remove(activeToolWindowId)) {
            myToolwindowIdZBuffer.addFirst(activeToolWindowId);
          }
        }
      }
    });
  }

  @Override
  public void dispose() {
  }

  private void registerToolwindow(@NotNull final Executor executor, @NotNull ToolWindowManagerEx toolWindowManager) {
    final String toolWindowId = executor.getToolWindowId();
    if (toolWindowManager.getToolWindow(toolWindowId) != null) {
      return;
    }

    final ToolWindow toolWindow = toolWindowManager.registerToolWindow(toolWindowId, true, ToolWindowAnchor.BOTTOM, this, true);
    final ContentManager contentManager = toolWindow.getContentManager();
    contentManager.addDataProvider(new DataProvider() {
      private int myInsideGetData = 0;

      @Override
      public Object getData(String dataId) {
        myInsideGetData++;
        try {
          if (PlatformDataKeys.HELP_ID.is(dataId)) {
            return executor.getHelpId();
          }
          else {
            return myInsideGetData == 1 ? DataManager.getInstance().getDataContext(contentManager.getComponent()).getData(dataId) : null;
          }
        }
        finally {
          myInsideGetData--;
        }
      }
    });

    toolWindow.setIcon(executor.getToolWindowIcon());
    myToolwindowIdToBaseIconMap.put(toolWindowId, executor.getToolWindowIcon());
    new ContentManagerWatcher(toolWindow, contentManager);
    contentManager.addContentManagerListener(new ContentManagerAdapter() {
      @Override
      public void selectionChanged(final ContentManagerEvent event) {
        Content content = event.getContent();
        getSyncPublisher().contentSelected(content == null ? null : getRunContentDescriptorByContent(content), executor);
      }
    });
    myToolwindowIdToContentManagerMap.put(toolWindowId, contentManager);
    Disposer.register(contentManager, new Disposable() {
      @Override
      public void dispose() {
        myToolwindowIdToContentManagerMap.remove(toolWindowId).removeAllContents(true);
        myToolwindowIdZBuffer.remove(toolWindowId);
        myToolwindowIdToBaseIconMap.remove(toolWindowId);
      }
    });
    myToolwindowIdZBuffer.addLast(toolWindowId);
  }

  private RunContentWithExecutorListener getSyncPublisher() {
    return myProject.getMessageBus().syncPublisher(TOPIC);
  }

  @Override
  public void toFrontRunContent(final Executor requestor, final ProcessHandler handler) {
    final RunContentDescriptor descriptor = getDescriptorBy(handler, requestor);
    if (descriptor == null) {
      return;
    }
    toFrontRunContent(requestor, descriptor);
  }

  @Override
  public void toFrontRunContent(final Executor requestor, final RunContentDescriptor descriptor) {
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        ContentManager contentManager = getContentManagerForRunner(requestor);
        Content content = getRunContentByDescriptor(contentManager, descriptor);
        if (content != null) {
          contentManager.setSelectedContent(content);
          ToolWindowManager.getInstance(myProject).getToolWindow(requestor.getToolWindowId()).show(null);
        }
      }
    }, myProject.getDisposed());
  }

  @Override
  public void hideRunContent(@NotNull final Executor executor, final RunContentDescriptor descriptor) {
    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        ToolWindow toolWindow = ToolWindowManager.getInstance(myProject).getToolWindow(executor.getToolWindowId());
        if (toolWindow != null) {
          toolWindow.hide(null);
        }
      }
    }, myProject.getDisposed());
  }

  @Override
  @Nullable
  public RunContentDescriptor getSelectedContent(final Executor executor) {
    final Content selectedContent = getContentManagerForRunner(executor).getSelectedContent();
    return selectedContent != null ? getRunContentDescriptorByContent(selectedContent) : null;
  }

  @Override
  @Nullable
  public RunContentDescriptor getSelectedContent() {
    for (String activeWindow : myToolwindowIdZBuffer) {
      final ContentManager contentManager = myToolwindowIdToContentManagerMap.get(activeWindow);
      if (contentManager == null) {
        continue;
      }

      final Content selectedContent = contentManager.getSelectedContent();
      if (selectedContent == null) {
        if (contentManager.getContentCount() == 0) {
          // continue to the next window if the content manager is empty
          continue;
        }
        else {
          // stop iteration over windows because there is some content in the window and the window is the last used one
          break;
        }
      }
      // here we have selected content
      return getRunContentDescriptorByContent(selectedContent);
    }

    return null;
  }

  @Override
  public boolean removeRunContent(@NotNull final Executor executor, final RunContentDescriptor descriptor) {
    final ContentManager contentManager = getContentManagerForRunner(executor);
    final Content content = getRunContentByDescriptor(contentManager, descriptor);
    return content != null && contentManager.removeContent(content, true);
  }

  @Override
  public void showRunContent(@NotNull Executor executor, @NotNull RunContentDescriptor descriptor) {
    showRunContent(executor, descriptor, descriptor.getExecutionId());
  }

  public void showRunContent(@NotNull final Executor executor, @NotNull final RunContentDescriptor descriptor, final long executionId) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return;
    }

    final ContentManager contentManager = getContentManagerForRunner(executor);
    RunContentDescriptor oldDescriptor = chooseReuseContentForDescriptor(contentManager, descriptor, executionId, descriptor.getDisplayName());
    final Content content;
    if (oldDescriptor == null) {
      content = createNewContent(contentManager, descriptor, executor);
      Icon icon = descriptor.getIcon();
      content.setIcon(icon == null ? executor.getToolWindowIcon() : icon);
    }
    else {
      content = oldDescriptor.getAttachedContent();
      LOG.assertTrue(content != null);
      getSyncPublisher().contentRemoved(oldDescriptor, executor);
      Disposer.dispose(oldDescriptor); // is of the same category, can be reused
    }

    content.setExecutionId(executionId);
    content.setComponent(descriptor.getComponent());
    content.setPreferredFocusedComponent(descriptor.getPreferredFocusComputable());
    content.putUserData(DESCRIPTOR_KEY, descriptor);
    final ToolWindow toolWindow = ToolWindowManager.getInstance(myProject).getToolWindow(executor.getToolWindowId());
    final ProcessHandler processHandler = descriptor.getProcessHandler();
    if (processHandler != null) {
      final ProcessAdapter processAdapter = new ProcessAdapter() {
        @Override
        public void startNotified(final ProcessEvent event) {
          UIUtil.invokeLaterIfNeeded(new Runnable() {
            @Override
            public void run() {
              toolWindow.setIcon(getLiveIndicator(myToolwindowIdToBaseIconMap.get(executor.getToolWindowId())));
            }
          });
        }

        @Override
        public void processTerminated(final ProcessEvent event) {
          ApplicationManager.getApplication().invokeLater(new Runnable() {
            @Override
            public void run() {
              final Icon icon = descriptor.getIcon();

              boolean alive = false;
              String toolWindowId = executor.getToolWindowId();
              ContentManager manager = myToolwindowIdToContentManagerMap.get(toolWindowId);
              for (Content content : manager.getContents()) {
                RunContentDescriptor descriptor = getRunContentDescriptorByContent(content);
                if (descriptor != null) {
                  ProcessHandler handler = descriptor.getProcessHandler();
                  if (handler != null && !handler.isProcessTerminated()) {
                    alive = true;
                    break;
                  }
                }
              }

              final boolean finalAlive = alive;
              UIUtil.invokeLaterIfNeeded(new Runnable() {
                @Override
                public void run() {
                  toolWindow.setIcon(finalAlive
                                     ? getLiveIndicator(myToolwindowIdToBaseIconMap.get(executor.getToolWindowId()))
                                     : myToolwindowIdToBaseIconMap.get(executor.getToolWindowId()));
                  content.setIcon(icon == null ? executor.getDisabledIcon() : IconLoader.getTransparentIcon(icon));
                }
              });
            }
          });
        }
      };
      processHandler.addProcessListener(processAdapter);
      final Disposable disposer = content.getDisposer();
      if (disposer != null) {
        Disposer.register(disposer, new Disposable() {
          @Override
          public void dispose() {
            processHandler.removeProcessListener(processAdapter);
          }
        });
      }
    }
    content.setDisplayName(descriptor.getDisplayName());
    descriptor.setAttachedContent(content);
    content.getManager().setSelectedContent(content);

    if (!descriptor.isActivateToolWindowWhenAdded()) {
      return;
    }

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      @Override
      public void run() {
        ToolWindow window = ToolWindowManager.getInstance(myProject).getToolWindow(executor.getToolWindowId());
        // let's activate tool window, but don't move focus
        //
        // window.show() isn't valid here, because it will not
        // mark the window as "last activated" windows and thus
        // some action like navigation up/down in stacktrace wont
        // work correctly
        descriptor.getPreferredFocusComputable();
        window.activate(descriptor.getActivationCallback(), descriptor.isAutoFocusContent(), descriptor.isAutoFocusContent());
      }
    }, myProject.getDisposed());
  }

  private final static int INDICATOR_SIZE = 4;
  private static Icon getLiveIndicator(final Icon base) {
    return new LayeredIcon(base, new Icon() {
      @Override
      public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2d = (Graphics2D)g.create();
        try {
          GraphicsUtil.setupAAPainting(g2d);
          g2d.setColor(Color.GREEN);
          Ellipse2D.Double shape =
            new Ellipse2D.Double(x + getIconWidth() - INDICATOR_SIZE, y + getIconHeight() - INDICATOR_SIZE, INDICATOR_SIZE, INDICATOR_SIZE);
          g2d.fill(shape);
          g2d.setColor(ColorUtil.withAlpha(Color.BLACK, .40));
          g2d.draw(shape);
        }
        finally {
          g2d.dispose();
        }
      }

      @Override
      public int getIconWidth() {
        return base != null ? base.getIconWidth() : 13;
      }

      @Override
      public int getIconHeight() {
        return base != null ? base.getIconHeight() : 13;
      }
    });
  }

  @Override
  @Nullable
  @Deprecated
  public RunContentDescriptor getReuseContent(final Executor requestor, DataContext dataContext) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return null;
    }
    //noinspection deprecation
    return getReuseContent(requestor, GenericProgramRunner.CONTENT_TO_REUSE_DATA_KEY.getData(dataContext));
  }

  @Override
  @Nullable
  @Deprecated
  public RunContentDescriptor getReuseContent(Executor requestor, @Nullable RunContentDescriptor contentToReuse) {
    if (ApplicationManager.getApplication().isUnitTestMode()) {
      return null;
    }
    if (contentToReuse != null) {
      return contentToReuse;
    }
    return chooseReuseContentForDescriptor(getContentManagerForRunner(requestor), null, 0L, null);
  }

  @Nullable
  @Override
  public RunContentDescriptor getReuseContent(Executor requestor, @NotNull ExecutionEnvironment executionEnvironment) {
    return getReuseContent(executionEnvironment);
  }

  @Nullable
  @Override
  public RunContentDescriptor getReuseContent(@NotNull ExecutionEnvironment executionEnvironment) {
    if (ApplicationManager.getApplication().isUnitTestMode()) return null;
    RunContentDescriptor contentToReuse = executionEnvironment.getContentToReuse();
    if (contentToReuse != null) {
      return contentToReuse;
    }

    final ContentManager contentManager = getContentManagerForRunner(executionEnvironment.getExecutor());
    return chooseReuseContentForDescriptor(contentManager, null, executionEnvironment.getExecutionId(),
                                           executionEnvironment.toString());
  }

  @Override
  public RunContentDescriptor findContentDescriptor(final Executor requestor, final ProcessHandler handler) {
    return getDescriptorBy(handler, requestor);
  }

  @Override
  public void showRunContent(@NotNull Executor info, @NotNull RunContentDescriptor descriptor, @Nullable RunContentDescriptor contentToReuse) {
    copyContentAndBehavior(descriptor, contentToReuse);
    showRunContent(info, descriptor, descriptor.getExecutionId());
  }

  public static void copyContentAndBehavior(@NotNull RunContentDescriptor descriptor, @Nullable RunContentDescriptor contentToReuse) {
    if (contentToReuse != null) {
      Content attachedContent = contentToReuse.getAttachedContent();
      if (attachedContent != null && attachedContent.isValid()) {
        descriptor.setAttachedContent(attachedContent);
      }
      if (contentToReuse.isReuseToolWindowActivation()) {
        descriptor.setActivateToolWindowWhenAdded(contentToReuse.isActivateToolWindowWhenAdded());
      }
    }
  }

  @Nullable
  private static RunContentDescriptor chooseReuseContentForDescriptor(@NotNull ContentManager contentManager,
                                                                      @Nullable RunContentDescriptor descriptor,
                                                                      long executionId,
                                                                      @Nullable String preferredName) {
    Content content = null;
    if (descriptor != null) {
      //Stage one: some specific descriptors (like AnalyzeStacktrace) cannot be reused at all
      if (descriptor.isContentReuseProhibited()) {
        return null;
      }
      //Stage two: try to get content from descriptor itself
      final Content attachedContent = descriptor.getAttachedContent();

      if (attachedContent != null
          && attachedContent.isValid()
          && contentManager.getIndexOfContent(attachedContent) != -1
          && (Comparing.equal(descriptor.getDisplayName(), attachedContent.getDisplayName()) || !attachedContent.isPinned())) {
        content = attachedContent;
      }
    }
    //Stage three: choose the content with name we prefer
    if (content == null) {
      content = getContentFromManager(contentManager, preferredName, executionId);
    }
    if (content == null || !isTerminated(content) || (content.getExecutionId() == executionId && executionId != 0)) {
      return null;
    }
    final RunContentDescriptor oldDescriptor = getRunContentDescriptorByContent(content);
    if (oldDescriptor != null && !oldDescriptor.isContentReuseProhibited() ) {
      //content.setExecutionId(executionId);
      return oldDescriptor;
    }

    return null;
  }

  @Nullable
  private static Content getContentFromManager(ContentManager contentManager, @Nullable String preferredName, long executionId) {
    ArrayList<Content> contents = new ArrayList<Content>(Arrays.asList(contentManager.getContents()));
    Content first = contentManager.getSelectedContent();
    if (first != null && contents.remove(first)) {//selected content should be checked first
      contents.add(0, first);
    }
    if (preferredName != null) {//try to match content with specified preferred name
      for (Content c : contents) {
        if (canReuseContent(c, executionId) && preferredName.equals(c.getDisplayName())) {
          return c;
        }
      }
    }
    for (Content c : contents) {//return first "good" content
      if (canReuseContent(c, executionId)) {
        return c;
      }
    }
    return null;
  }

  private static boolean canReuseContent(Content c, long executionId) {
    return c != null && !c.isPinned() && isTerminated(c) && !(c.getExecutionId() == executionId && executionId != 0);
  }

  @NotNull
  private ContentManager getContentManagerForRunner(final Executor executor) {
    final ContentManager contentManager = myToolwindowIdToContentManagerMap.get(executor.getToolWindowId());
    if (contentManager == null) {
      LOG.error("Runner " + executor.getId() + " is not registered");
    }
    //noinspection ConstantConditions
    return contentManager;
  }

  private Content createNewContent(final ContentManager contentManager, final RunContentDescriptor descriptor, Executor executor) {
    final String processDisplayName = descriptor.getDisplayName();
    final Content content = ContentFactory.SERVICE.getInstance().createContent(descriptor.getComponent(), processDisplayName, true);
    content.putUserData(DESCRIPTOR_KEY, descriptor);
    content.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
    contentManager.addContent(content);
    new CloseListener(content, executor);
    return content;
  }

  private static boolean isTerminated(@NotNull Content content) {
    RunContentDescriptor descriptor = getRunContentDescriptorByContent(content);
    ProcessHandler processHandler = descriptor == null ? null : descriptor.getProcessHandler();
    return processHandler == null || processHandler.isProcessTerminated();
  }

  @Nullable
  private static RunContentDescriptor getRunContentDescriptorByContent(@NotNull Content content) {
    return content.getUserData(DESCRIPTOR_KEY);
  }

  @Override
  @Nullable
  public ToolWindow getToolWindowByDescriptor(@NotNull RunContentDescriptor descriptor) {
    for (Map.Entry<String, ContentManager> entry : myToolwindowIdToContentManagerMap.entrySet()) {
      if (getRunContentByDescriptor(entry.getValue(), descriptor) != null) {
        return ToolWindowManager.getInstance(myProject).getToolWindow(entry.getKey());
      }
    }
    return null;
  }

  @Nullable
  private static Content getRunContentByDescriptor(@NotNull ContentManager contentManager, @NotNull RunContentDescriptor descriptor) {
    for (Content content : contentManager.getContents()) {
      if (descriptor.equals(content.getUserData(DESCRIPTOR_KEY))) {
        return content;
      }
    }
    return null;
  }

  @Override
  public void addRunContentListener(@NotNull final RunContentListener listener, final Executor executor) {
    final Disposable disposable = Disposer.newDisposable();
    myProject.getMessageBus().connect(disposable).subscribe(TOPIC, new RunContentWithExecutorListener() {
      @Override
      public void contentSelected(@Nullable RunContentDescriptor descriptor, @NotNull Executor executor2) {
        if (executor2.equals(executor)) {
          listener.contentSelected(descriptor);
        }
      }

      @Override
      public void contentRemoved(@Nullable RunContentDescriptor descriptor, @NotNull Executor executor2) {
        if (executor2.equals(executor)) {
          listener.contentRemoved(descriptor);
        }
      }
    });
    myListeners.put(listener, disposable);
  }

  @Override
  public void addRunContentListener(@NotNull final RunContentListener listener) {
    final Disposable disposable = Disposer.newDisposable();
    myProject.getMessageBus().connect(disposable).subscribe(TOPIC, new RunContentWithExecutorListener() {
      @Override
      public void contentSelected(@Nullable RunContentDescriptor descriptor, @NotNull Executor executor) {
        listener.contentSelected(descriptor);
      }

      @Override
      public void contentRemoved(@Nullable RunContentDescriptor descriptor, @NotNull Executor executor) {
        listener.contentRemoved(descriptor);
      }
    });
    myListeners.put(listener, disposable);
  }

  @Override
  @NotNull
  public List<RunContentDescriptor> getAllDescriptors() {
    if (myToolwindowIdToContentManagerMap.isEmpty()) {
      return Collections.emptyList();
    }

    List<RunContentDescriptor> descriptors = new SmartList<RunContentDescriptor>();
    for (String id : myToolwindowIdToContentManagerMap.keySet()) {
      for (Content content : myToolwindowIdToContentManagerMap.get(id).getContents()) {
        RunContentDescriptor descriptor = getRunContentDescriptorByContent(content);
        if (descriptor != null) {
          descriptors.add(descriptor);
        }
      }
    }
    return descriptors;
  }

  @Override
  public void removeRunContentListener(final RunContentListener listener) {
    Disposable disposable = myListeners.remove(listener);
    if (disposable != null) {
      Disposer.dispose(disposable);
    }
  }

  @Nullable
  private RunContentDescriptor getDescriptorBy(ProcessHandler handler, Executor runnerInfo) {
    for (Content content : getContentManagerForRunner(runnerInfo).getContents()) {
      RunContentDescriptor runContentDescriptor = getRunContentDescriptorByContent(content);
      assert runContentDescriptor != null;
      if (runContentDescriptor.getProcessHandler() == handler) {
        return runContentDescriptor;
      }
    }
    return null;
  }

  private class CloseListener extends ContentManagerAdapter implements ProjectManagerListener {
    private Content myContent;
    private final Executor myExecutor;

    private CloseListener(@NotNull final Content content, @NotNull Executor executor) {
      myContent = content;
      content.getManager().addContentManagerListener(this);
      ProjectManager.getInstance().addProjectManagerListener(this);
      myExecutor = executor;
    }

    @Override
    public void contentRemoved(final ContentManagerEvent event) {
      final Content content = event.getContent();
      if (content == myContent) {
        dispose();
      }
    }

    private void dispose() {
      if (myContent == null) return;

      final Content content = myContent;
      try {
        RunContentDescriptor descriptor = getRunContentDescriptorByContent(content);
        getSyncPublisher().contentRemoved(descriptor, myExecutor);
        if (descriptor != null) {
          Disposer.dispose(descriptor);
        }
      }
      finally {
        content.getManager().removeContentManagerListener(this);
        ProjectManager.getInstance().removeProjectManagerListener(this);
        content.release(); // don't invoke myContent.release() because myContent becomes null after destroyProcess()
        myContent = null;
      }
    }

    @Override
    public void contentRemoveQuery(final ContentManagerEvent event) {
      if (event.getContent() == myContent) {
        final boolean canClose = closeQuery(false);
        if (!canClose) {
          event.consume();
        }
      }
    }

    @Override
    public void projectOpened(final Project project) {
    }

    @Override
    public void projectClosed(final Project project) {
      if (myContent != null && project == myProject) {
        myContent.getManager().removeContent(myContent, true);
        dispose(); // Dispose content even if content manager refused to.
      }
    }

    @Override
    public boolean canCloseProject(final Project project) {
      if (project != myProject) return true;

      if (myContent == null) return true;

      final boolean canClose = closeQuery(true);
      if (canClose) {
        myContent.getManager().removeContent(myContent, true);
        myContent = null;
      }
      return canClose;
    }

    @Override
    public void projectClosing(final Project project) {
    }

    private boolean closeQuery(boolean modal) {
      final RunContentDescriptor descriptor = getRunContentDescriptorByContent(myContent);
      if (descriptor == null) {
        return true;
      }

      final ProcessHandler processHandler = descriptor.getProcessHandler();
      if (processHandler == null || processHandler.isProcessTerminated() || processHandler.isProcessTerminating()) {
        return true;
      }
      final boolean destroyProcess;
      //noinspection deprecation
      if (processHandler.isSilentlyDestroyOnClose() || Boolean.TRUE.equals(processHandler.getUserData(ProcessHandler.SILENTLY_DESTROY_ON_CLOSE))) {
        destroyProcess = true;
      }
      else {
        //todo[nik] this is a temporary solution for the following problem: some configurations should not allow user to choose between 'terminating' and 'detaching'
        final boolean useDefault = Boolean.TRUE.equals(processHandler.getUserData(ALWAYS_USE_DEFAULT_STOPPING_BEHAVIOUR_KEY));
        final TerminateRemoteProcessDialog.TerminateOption option = new TerminateRemoteProcessDialog.TerminateOption(processHandler.detachIsDefault(), useDefault);
        final int rc = TerminateRemoteProcessDialog.show(myProject, descriptor.getDisplayName(), option);
        if (rc != DialogWrapper.OK_EXIT_CODE) return false;
        destroyProcess = !option.isToBeShown();
      }
      if (destroyProcess) {
        processHandler.destroyProcess();
      }
      else {
        processHandler.detachProcess();
      }
      waitForProcess(descriptor, modal);
      return true;
    }
  }

  private void waitForProcess(final RunContentDescriptor descriptor, final boolean modal) {
    final ProcessHandler processHandler = descriptor.getProcessHandler();
    final boolean killable = !modal && (processHandler instanceof KillableProcess) && ((KillableProcess)processHandler).canKillProcess();

    String title = ExecutionBundle.message("terminating.process.progress.title", descriptor.getDisplayName());
    ProgressManager.getInstance().run(new Task.Backgroundable(myProject, title, true) {

      {
        if (killable) {
          String cancelText= ExecutionBundle.message("terminating.process.progress.kill");
          setCancelText(cancelText);
          setCancelTooltipText(cancelText);
        }
      }

      @Override
      public boolean isConditionalModal() {
        return modal;
      }

      @Override
      public boolean shouldStartInBackground() {
        return !modal;
      }

      @Override
      public void run(@NotNull final ProgressIndicator progressIndicator) {
        final Semaphore semaphore = new Semaphore();
        semaphore.down();

        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
          @Override
          public void run() {
            final ProcessHandler processHandler = descriptor.getProcessHandler();
            try {
              if (processHandler != null) {
                processHandler.waitFor();
              }
            }
            finally {
              semaphore.up();
            }
          }
        });

        progressIndicator.setText(ExecutionBundle.message("waiting.for.vm.detach.progress.text"));
        ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
          @Override
          public void run() {
            while (true) {
              if (progressIndicator.isCanceled() || !progressIndicator.isRunning()) {
                semaphore.up();
                break;
              }
              try {
                //noinspection SynchronizeOnThis
                synchronized (this) {
                  //noinspection SynchronizeOnThis
                  wait(2000L);
                }
              }
              catch (InterruptedException ignore) {
              }
            }
          }
        });

        semaphore.waitFor();
      }

      @Override
      public void onCancel() {
        if (killable && !processHandler.isProcessTerminated()) {
          ((KillableProcess)processHandler).killProcess();
        }
      }
    });
  }
}

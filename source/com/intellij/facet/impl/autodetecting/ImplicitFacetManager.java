/*
 * Copyright (c) 2000-2007 JetBrains s.r.o. All Rights Reserved.
 */

package com.intellij.facet.impl.autodetecting;

import com.intellij.facet.*;
import com.intellij.facet.impl.FacetUtil;
import com.intellij.facet.impl.autodetecting.facetsTree.ImplicitFacetsDialog;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ui.configuration.ModulesConfigurator;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.util.Alarm;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

/**
 * @author nik
 */
public class ImplicitFacetManager implements Disposable {
  public static final Icon FACET_DETECTED_ICON = IconLoader.getIcon("/ide/facetDetected.png");
  private static final int NOTIFICATION_DELAY = 200;
  private final JBPopupListener myNotificationPopupListener = new JBPopupListener() {
    public void onClosed(final JBPopup popup) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        public void run() {
          myNotificationPopup = null;
          firePendingNotifications();
        }
      });
    }
  };
  private List<Facet> myImplicitFacets = new ArrayList<Facet>();
  private Project myProject;
  private final FacetAutodetectingManagerImpl myAutodetectingManager;
  private final ProjectWideFacetListenersRegistry myProjectWideFacetListenersRegistry;
  private AttentionComponent myAttentionComponent;
  private StatusBar myStatusBar;
  private boolean myUIInitialized;
  private Set<Facet> myPendingNewFacets = new HashSet<Facet>();
  private JBPopup myNotificationPopup;
  private Alarm myNotificationAlarm = new Alarm();

  public ImplicitFacetManager(final Project project, final FacetAutodetectingManagerImpl autodetectingManager) {
    myProjectWideFacetListenersRegistry = ProjectWideFacetListenersRegistry.getInstance(project);
    myProject = project;
    myAutodetectingManager = autodetectingManager;
  }

  public <F extends Facet<C>, C extends FacetConfiguration> void registerListeners(final FacetType<F, C> type) {
    myProjectWideFacetListenersRegistry.registerListener(type.getId(), new MyProjectWideFacetListener<F, C>(), this);
  }

  public void onImplicitFacetChanged() {
    if (!myUIInitialized) return;

    final List<Facet> implicitFacets = getImplicitFacets();

    if (!Comparing.haveEqualElements(myImplicitFacets, implicitFacets)) {
      final Set<Facet> newFacets = new HashSet<Facet>(implicitFacets);
      newFacets.removeAll(myImplicitFacets);

      if (!ApplicationManager.getApplication().isHeadlessEnvironment()) {
        final boolean someFacetsDeleted = !implicitFacets.containsAll(myImplicitFacets);
        final boolean stopBlinking = !myImplicitFacets.isEmpty() && implicitFacets.isEmpty();
        final boolean startBlinking = myImplicitFacets.isEmpty() && !implicitFacets.isEmpty();

        Runnable runnable = new Runnable() {
          public void run() {
            if (someFacetsDeleted && myNotificationPopup != null) {
              myNotificationPopup.cancel();
            }

            myPendingNewFacets.addAll(newFacets);
            queueNotificationPopup();

            if (stopBlinking) {
              myAttentionComponent.stopBlinking();
            }
            if (startBlinking) {
              myAttentionComponent.startBlinking();
            }
          }
        };
        if (ApplicationManager.getApplication().isDispatchThread() && ApplicationManager.getApplication().getCurrentModalityState() == ModalityState.NON_MODAL) {
          runnable.run();
        }
        else {
          ApplicationManager.getApplication().invokeLater(runnable, ModalityState.NON_MODAL);
        }
      }
      myImplicitFacets = implicitFacets;
    }
  }

  private void queueNotificationPopup() {
    myNotificationAlarm.cancelAllRequests();
    myNotificationAlarm.addRequest(new Runnable() {
      public void run() {
        firePendingNotifications();
      }
    }, NOTIFICATION_DELAY);
  }

  public boolean isDisposed() {
    return myProject.isDisposed() || !myUIInitialized;
  }

  private void firePendingNotifications() {
    if (myPendingNewFacets.isEmpty()) return;

    List<Facet> newFacets = new ArrayList<Facet>();
    for (Facet newFacet : myPendingNewFacets) {
      if (myImplicitFacets.contains(newFacet)) {
        newFacets.add(newFacet);
      }
    }
    myPendingNewFacets.clear();
    fireNotification(newFacets);
  }

  private void fireNotification(final Collection<Facet> newFacets) {
    if (newFacets.isEmpty()) {
      return;
    }

    if (myNotificationPopup == null) {
      myNotificationPopup = createImplicitFacetsComponent(newFacets).fireNotificationPopup(myStatusBar, myNotificationPopupListener);
    }
    else {
      myPendingNewFacets.addAll(newFacets);
    }
  }

  private ImplicitFacetsComponent createImplicitFacetsComponent(final Collection<Facet> newFacets) {
    List<ImplicitFacetInfo> implicitFacets = getImplicitFacetInfos(newFacets);
    return new ImplicitFacetsComponent(this, implicitFacets);
  }

  private List<ImplicitFacetInfo> getImplicitFacetInfos(final Collection<Facet> newFacets) {
    List<ImplicitFacetInfo> implicitFacets = new ArrayList<ImplicitFacetInfo>();
    for (Facet newFacet : newFacets) {
      Set<String> urls = myAutodetectingManager.getFiles(newFacet);
      if (urls != null) {
        implicitFacets.add(new ImplicitFacetInfo(newFacet, urls));
      }
    }
    return implicitFacets;
  }

  private List<Facet> getImplicitFacets() {
    List<Facet> implicitFacets = new ArrayList<Facet>();
    Module[] modules = ModuleManager.getInstance(myProject).getModules();
    for (Module module : modules) {
      Facet[] facets = FacetManager.getInstance(module).getAllFacets();
      for (Facet facet : facets) {
        if (facet.isImplicit()) {
          implicitFacets.add(facet);
        }
      }
    }
    return implicitFacets;
  }

  public void dispose() {
  }

  public void initUI() {
    myAttentionComponent = new AttentionComponent(this);
    myStatusBar = WindowManager.getInstance().getStatusBar(myProject);
    if (myStatusBar == null) return;

    myStatusBar.addCustomIndicationComponent(myAttentionComponent);
    myUIInitialized = true;
    onImplicitFacetChanged();
  }

  public void disposeUI() {
    if (!myUIInitialized) return;

    myNotificationPopup = null;
    myNotificationAlarm.cancelAllRequests();
    myStatusBar.removeCustomIndicationComponent(myAttentionComponent);
    myAttentionComponent.disposeUI();
    myAttentionComponent = null;
  }

  public void configureFacet(final Facet facet) {
    if (!FacetUtil.isRegistered(facet)) {
      return;
    }

    facet.setImplicit(false);
    onImplicitFacetChanged();
    ModulesConfigurator.showFacetSettingsDialog(facet, null);
    onImplicitFacetChanged();
  }

  public void disableDetectionInFile(final ImplicitFacetInfo implicitFacet) {
    Facet facet = implicitFacet.getFacet();
    Collection<String> urls = implicitFacet.getUrls();
    myAutodetectingManager.disableAutodetectionInFiles(facet.getType(), facet.getModule(), urls.toArray(new String[urls.size()]));
    FacetUtil.deleteFacet(facet);
  }

  public void disableDetectionInModule(final Facet facet) {
    disableDetectionInModule(facet.getType(), facet.getModule());
  }

  public void disableDetectionInModule(final FacetType type, final Module module) {
    myAutodetectingManager.disableAutodetectionInModule(type, module);
    FacetUtil.deleteImplicitFacets(module, type.getId());
  }

  public void disableDetectionInProject(final Facet facet) {
    myAutodetectingManager.disableAutodetectionInProject(facet.getType());
    FacetUtil.deleteImplicitFacets(facet.getModule().getProject(), facet.getTypeId());
  }

  public void skipImplicitFacet(final Facet facet) {
    facet.setImplicit(false);
    onImplicitFacetChanged();
  }

  public void showImplicitFacetsDialog() {
    if (!myImplicitFacets.isEmpty()) {
      List<ImplicitFacetInfo> facets = getImplicitFacetInfos(myImplicitFacets);
      ImplicitFacetsDialog dialog = new ImplicitFacetsDialog(myProject, this, facets);
      dialog.show();
    }
  }

  private class MyProjectWideFacetListener<F extends Facet<C>, C extends FacetConfiguration> extends ProjectWideFacetAdapter<F> {
    public void facetConfigurationChanged(final F facet) {
      if (facet.isImplicit()) {
        facet.setImplicit(false);
        onImplicitFacetChanged();
      }
    }

    public void beforeFacetRemoved(final F facet) {
      Set<String> files = myAutodetectingManager.getFiles(facet);
      if (files != null) {
        myAutodetectingManager.disableAutodetectionInFiles(facet.getType(), facet.getModule(), files.toArray(new String[files.size()]));
      }
      myAutodetectingManager.removeFacetFromCache(facet);
    }

    public void facetRemoved(final F facet) {
      onImplicitFacetChanged();
    }
  }

  private static class AttentionComponent extends SimpleColoredComponent {
    private static final int BLINKING_DELAY = 300;
    private Alarm myBlinkingAlarm = new Alarm();
    private boolean myIconVisible;
    private boolean myActive;
    private ImplicitFacetManager myManager;

    public AttentionComponent(final ImplicitFacetManager manager) {
      myManager = manager;
      addMouseListener(new MouseAdapter() {
        public void mouseClicked(final MouseEvent e) {
          if (myActive && !e.isPopupTrigger() && myManager != null) {
            myManager.showImplicitFacetsDialog();
          }
        }
      });
    }

    public void startBlinking() {
      myBlinkingAlarm.cancelAllRequests();
      myIconVisible = true;
      myActive = true;
      showIcon();
      myBlinkingAlarm.addRequest(new Runnable() {
        public void run() {
          if (!myActive) return;

          if (myIconVisible) {
            hideIcon();
          }
          else {
            showIcon();
          }
          myIconVisible = !myIconVisible;
          myBlinkingAlarm.addRequest(this, BLINKING_DELAY);
        }
      }, BLINKING_DELAY);
    }

    public void stopBlinking() {
      myActive = false;
      myBlinkingAlarm.cancelAllRequests();
      hideIcon();
    }

    private void showIcon() {
      setIcon(FACET_DETECTED_ICON);
      repaint();
    }

    private void hideIcon() {
      clear();
      repaint();
    }

    public void disposeUI() {
      myManager = null;
      myBlinkingAlarm.cancelAllRequests();
    }

    public Dimension getPreferredSize() {
      return new Dimension(18, 18);
    }
  }

}

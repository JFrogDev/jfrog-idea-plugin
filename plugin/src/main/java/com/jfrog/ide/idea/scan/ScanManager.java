package com.jfrog.ide.idea.scan;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import com.jfrog.ide.common.log.ProgressIndicator;
import com.jfrog.ide.common.scan.ComponentPrefix;
import com.jfrog.ide.common.scan.ScanManagerBase;
import com.jfrog.ide.idea.Events;
import com.jfrog.ide.idea.configuration.GlobalSettings;
import com.jfrog.ide.idea.log.Logger;
import com.jfrog.ide.idea.log.ProgressIndicatorImpl;
import com.jfrog.ide.idea.ui.issues.IssuesTree;
import com.jfrog.ide.idea.ui.licenses.LicensesTree;
import com.jfrog.ide.idea.utils.ProjectsMap;
import com.jfrog.ide.idea.utils.Utils;
import com.jfrog.xray.client.services.summary.Components;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfrog.build.extractor.scan.DependenciesTree;
import org.jfrog.build.extractor.scan.License;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by romang on 4/26/17.
 */
public abstract class ScanManager extends ScanManagerBase {

    private static final Path HOME_PATH = Paths.get(System.getProperty("user.home"), ".jfrog-idea-plugin");
    Project project;

    // Lock to prevent multiple simultaneous scans
    private AtomicBoolean scanInProgress = new AtomicBoolean(false);

    public ScanManager(@NotNull Project project, ComponentPrefix prefix) throws IOException {
        super(HOME_PATH.resolve("cache"), project.getName(), Logger.getInstance(), GlobalSettings.getInstance().getXrayConfig(), prefix);
        this.project = project;
        Files.createDirectories(HOME_PATH);
        registerOnChangeHandlers();
    }

    /**
     * Refresh project dependencies.
     */
    protected abstract void refreshDependencies(ExternalProjectRefreshCallback cbk, @Nullable Collection<DataNode<LibraryDependencyData>> libraryDependencies);

    /**
     * Collect and return {@link Components} to be scanned by JFrog Xray.
     * Implementation should be project type specific.
     */
    protected abstract void buildTree(@Nullable DataNode<ProjectData> externalProject) throws IOException;

    /**
     * Scan and update dependency components.
     */
    private void scanAndUpdate(boolean quickScan, ProgressIndicator indicator, @Nullable Collection<DataNode<LibraryDependencyData>> libraryDependencies) {
        // Don't scan if Xray is not configured
        if (!GlobalSettings.getInstance().isCredentialsSet()) {
            getLog().error("Xray server is not configured.");
            return;
        }
        // Prevent multiple simultaneous scans
        if (!scanInProgress.compareAndSet(false, true)) {
            if (!quickScan) {
                getLog().info("Scan already in progress");
            }
            return;
        }
        try {
            // Refresh dependencies -> Collect -> Scan and store to cache -> Update view
            refreshDependencies(getRefreshDependenciesCbk(quickScan, indicator), libraryDependencies);
        } finally {
            scanInProgress.set(false);
        }
    }

    /**
     * Launch async dependency scan.
     */
    public void asyncScanAndUpdateResults(boolean quickScan, @Nullable Collection<DataNode<LibraryDependencyData>> libraryDependencies) {
        Task.Backgroundable scanAndUpdateTask = new Task.Backgroundable(project, "Xray: Scanning for Vulnerabilities...") {
            @Override
            public void run(@NotNull com.intellij.openapi.progress.ProgressIndicator indicator) {
                if (project.isDisposed()) {
                    return;
                }
                scanAndUpdate(quickScan, new ProgressIndicatorImpl(indicator), libraryDependencies);
                indicator.finishNonCancelableSection();
            }
        };
        // The progress manager is only good for foreground threads.
        if (SwingUtilities.isEventDispatchThread()) {
            ProgressManager.getInstance().run(scanAndUpdateTask);
        } else {
            // Run the scan task when the thread is in the foreground.
            SwingUtilities.invokeLater(() -> ProgressManager.getInstance().run(scanAndUpdateTask));
        }
    }

    /**
     * Returns all project modules locations as Paths.
     * Other scanners such as npm will use this paths in order to find modules.
     *
     * @return all project modules locations as Paths
     */
    public Set<Path> getProjectPaths() {
        Set<Path> paths = new HashSet<>();
        paths.add(Utils.getProjectBasePath(project));
        return paths;
    }

    /**
     * Launch async dependency scan.
     */
    public void asyncScanAndUpdateResults(boolean quickScan) {
        asyncScanAndUpdateResults(quickScan, null);
    }

    private ExternalProjectRefreshCallback getRefreshDependenciesCbk(boolean quickScan, ProgressIndicator indicator) {
        return new ExternalProjectRefreshCallback() {
            @Override
            public void onSuccess(@Nullable DataNode<ProjectData> externalProject) {
                try {
                    buildTree(externalProject);
                    scanAndCacheArtifacts(indicator, quickScan);
                    addXrayInfoToTree(getScanResults());
                    setScanResults();
                } catch (Exception e) {
                    getLog().error("", e);
                }
            }

            @Override
            public void onFailure(@NotNull String errorMessage, @Nullable String errorDetails) {
                getLog().error(StringUtils.defaultIfEmpty(errorDetails, errorMessage));
            }
        };
    }

    private void registerOnChangeHandlers() {
        MessageBusConnection busConnection = ApplicationManager.getApplication().getMessageBus().connect();
        busConnection.subscribe(Events.ON_SCAN_FILTER_CHANGE, () -> {
            MessageBus messageBus = ApplicationManager.getApplication().getMessageBus();
            messageBus.syncPublisher(Events.ON_SCAN_COMPONENTS_CHANGE).update();
            messageBus.syncPublisher(Events.ON_SCAN_ISSUES_CHANGE).update();
        });
        busConnection.subscribe(Events.ON_CONFIGURATION_DETAILS_CHANGE, () -> asyncScanAndUpdateResults(true));
    }

    /**
     * @return all licenses available from the current scan results.
     */
    public Set<License> getAllLicenses() {
        Set<License> allLicenses = new HashSet<>();
        if (getScanResults() == null) {
            return allLicenses;
        }
        DependenciesTree node = (DependenciesTree) getScanResults().getRoot();
        collectAllLicenses(node, allLicenses);
        return allLicenses;
    }

    private void collectAllLicenses(DependenciesTree node, Set<License> allLicenses) {
        allLicenses.addAll(node.getLicenses());
        node.getChildren().forEach(child -> collectAllLicenses(child, allLicenses));
    }

    /**
     * filter scan components tree model according to the user filters and sort the issues tree.
     */
    private void setScanResults() {
        DependenciesTree scanResults = getScanResults();
        if (scanResults == null) {
            return;
        }
        if (!scanResults.isLeaf()) {
            addFilterMangerLicenses();
        }
        ProjectsMap.ProjectKey projectKey = ProjectsMap.createKey(getProjectName(),
                scanResults.getGeneralInfo());

        IssuesTree issuesTree = IssuesTree.getInstance();
        issuesTree.addScanResults(getProjectName(), scanResults);
        issuesTree.applyFilters(projectKey);

        LicensesTree licensesTree = LicensesTree.getInstance();
        licensesTree.addScanResults(getProjectName(), scanResults);
        licensesTree.applyFilters(projectKey);
    }

    @Override
    protected void checkCanceled() {
        if (project.isOpen()) {
            // The project is closed if we are in test mode.
            // In tests we can't check if the user canceled the scan, since we don't have the ProgressManager service.
            ProgressManager.checkCanceled();
        }
    }

    boolean isScanInProgress() {
        return this.scanInProgress.get();
    }
}

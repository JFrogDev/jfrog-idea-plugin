package com.jfrog.ide.idea.scan;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.jfrog.ide.idea.log.Logger;
import com.jfrog.ide.idea.ui.issues.IssuesTree;
import com.jfrog.ide.idea.ui.licenses.LicensesTree;
import com.jfrog.ide.idea.utils.Utils;
import org.apache.commons.lang.ArrayUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import static com.jfrog.ide.common.utils.Utils.findPackageJsonDirs;

/**
 * Created by romang on 3/2/17.
 */
public class ScanManagersFactory {

    private Map<String, ScanManager> scanManagers = Maps.newHashMap();

    public static ScanManagersFactory getInstance() {
        return ServiceManager.getService(ScanManagersFactory.class);
    }

    private ScanManagersFactory() {
    }

    public static Set<ScanManager> getScanManagers() {
        ScanManagersFactory scanManagersFactory = ServiceManager.getService(ScanManagersFactory.class);
        return Sets.newHashSet(scanManagersFactory.scanManagers.values());
    }

    public void startScan(boolean quickScan, @Nullable Collection<DataNode<LibraryDependencyData>> libraryDependencies) {
        if (isScanInProgress()) {
            Logger.getInstance().info("Previous scan still running...");
            return;
        }
        try {
            refreshScanManagers();
        } catch (IOException e) {
            Logger.getInstance().error("", e);
        }
        IssuesTree issuesTree = IssuesTree.getInstance();
        LicensesTree licensesTree = LicensesTree.getInstance();
        if (issuesTree == null || licensesTree == null) {
            return;
        }
        resetViews(issuesTree, licensesTree);
        for (ScanManager scanManager : scanManagers.values()) {
            scanManager.asyncScanAndUpdateResults(quickScan, libraryDependencies);
        }
    }

    public void refreshScanManagers() throws IOException {
        Map<String, ScanManager> scanManagers = Maps.newHashMap();
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (ArrayUtils.isEmpty(projects)) {
            return;
        }
        final Set<Path> paths = Sets.newHashSet();
        for (Project project : projects) {
            String projectHash = project.getLocationHash();
            ScanManager scanManager = this.scanManagers.get(projectHash);
            if (scanManager != null) {
                scanManagers.put(projectHash, scanManager);
            } else {
                if (MavenScanManager.isApplicable(project)) {
                    scanManagers.put(projectHash, new MavenScanManager(project));
                }
                if (GradleScanManager.isApplicable(project)) {
                    scanManagers.put(projectHash, new GradleScanManager(project));
                }
            }
            paths.add(Utils.getProjectBasePath(project));
        }
        scanManagers.values().stream().map(ScanManager::getProjectPaths).flatMap(Collection::stream).forEach(paths::add);
        Set<String> packageJsonDirs = findPackageJsonDirs(paths);
        for (String dir : packageJsonDirs) {
            Project npmProject = ProjectManager.getInstance().createProject(dir, dir);
            scanManagers.put(npmProject.getLocationHash(), new NpmScanManager(npmProject));
        }
        this.scanManagers = scanManagers;
    }

    private boolean isScanInProgress() {
        return scanManagers.values().stream().anyMatch(ScanManager::isScanInProgress);
    }

    private void resetViews(IssuesTree issuesTree, LicensesTree licensesTree) {
        issuesTree.reset();
        licensesTree.reset();
    }
}
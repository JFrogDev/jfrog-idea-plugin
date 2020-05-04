package com.jfrog.ide.idea.ui.issues;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SideBorder;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.UIUtil;
import com.jfrog.ide.idea.configuration.GlobalSettings;
import com.jfrog.ide.idea.inspections.NavigationService;
import com.jfrog.ide.idea.scan.ScanManagersFactory;
import com.jfrog.ide.idea.ui.components.FilterButton;
import com.jfrog.ide.idea.ui.components.TitledPane;
import com.jfrog.ide.idea.ui.filters.FilterManagerService;
import com.jfrog.ide.idea.ui.filters.IssueFilterMenu;
import com.jfrog.ide.idea.ui.utils.ComponentUtils;
import org.jetbrains.annotations.NotNull;
import org.jfrog.build.extractor.scan.DependenciesTree;
import org.jfrog.build.extractor.scan.Issue;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;

import static com.jfrog.ide.idea.ui.JFrogToolWindow.*;

/**
 * @author yahavi
 */
public class IssuesTab {

    private Map<TreePath, JPanel> issuesCountPanels = Maps.newHashMap();
    private OnePixelSplitter issuesRightHorizontalSplit;
    private ComponentIssuesTable issuesTable;
    private JScrollPane issuesDetailsScroll;
    private JPanel issuesDetailsPanel;
    private JComponent issuesPanel;
    private IssuesTree issuesTree;
    private Project mainProject;
    private static final String POPUP_MENU_HEADLINE = "Show in project descriptor";
    private JBPopupMenu popupMenu = new JBPopupMenu();

    /**
     * @param mainProject - Currently opened IntelliJ project
     * @param supported   - True if the current opened project is supported by the plugin.
     *                    If not, show the "Unsupported project type" message.
     * @return the issues view panel
     */
    public JPanel createIssuesViewTab(@NotNull Project mainProject, boolean supported) {
        this.mainProject = mainProject;
        this.issuesTree = IssuesTree.getInstance(mainProject);
        addRightClickListener(this.issuesTree);
        IssueFilterMenu issueFilterMenu = new IssueFilterMenu(mainProject);
        JPanel issuesFilterButton = new FilterButton(issueFilterMenu, "Severity", "Select severities to show");
        JPanel toolbar = ComponentUtils.createActionToolbar("Severities toolbar", issuesFilterButton, issuesTree);

        issuesPanel = createComponentsIssueDetailView();
        issuesRightHorizontalSplit = new OnePixelSplitter(true, 0.55f);
        issuesRightHorizontalSplit.setFirstComponent(createComponentsDetailsView(supported));
        issuesRightHorizontalSplit.setSecondComponent(issuesPanel);

        OnePixelSplitter centralVerticalSplit = new OnePixelSplitter(false, 0.20f);
        centralVerticalSplit.setFirstComponent(createIssuesComponentsTreeView());
        centralVerticalSplit.setSecondComponent(issuesRightHorizontalSplit);

        SimpleToolWindowPanel issuesViewTab = new SimpleToolWindowPanel(true);
        issuesViewTab.setToolbar(toolbar);
        issuesViewTab.setContent(centralVerticalSplit);
        return issuesViewTab;
    }

    /**
     * Create the issues tree panel.
     *
     * @return the issues tree panel
     */
    private JComponent createIssuesComponentsTreeView() {
        JLabel issuesCount = new JBLabel("Issues (0) ");

        JPanel componentsTreePanel = new JBPanel(new BorderLayout()).withBackground(UIUtil.getTableBackground());
        JLabel componentsTreeTitle = new JBLabel(" Components Tree");
        componentsTreeTitle.setFont(componentsTreeTitle.getFont().deriveFont(TITLE_FONT_SIZE));
        componentsTreePanel.add(componentsTreeTitle, BorderLayout.LINE_START);
        componentsTreePanel.add(issuesCount, BorderLayout.LINE_END);

        JPanel issuesCountPanel = new JBPanel().withBackground(UIUtil.getTableBackground());
        issuesCountPanel.setLayout(new BoxLayout(issuesCountPanel, BoxLayout.Y_AXIS));
        issuesTree.createExpansionListener(issuesCountPanel, issuesCountPanels);
        issuesTree.setIssuesCountLabel(issuesCount);

        JBPanel treePanel = new JBPanel(new BorderLayout()).withBackground(UIUtil.getTableBackground());
        TreeSpeedSearch treeSpeedSearch = new TreeSpeedSearch(issuesTree, ComponentUtils::getPathSearchString, true);
        treePanel.add(treeSpeedSearch.getComponent(), BorderLayout.WEST);
        treePanel.add(issuesCountPanel, BorderLayout.CENTER);
        JScrollPane treeScrollPane = ScrollPaneFactory.createScrollPane(treePanel);
        treeScrollPane.getVerticalScrollBar().setUnitIncrement(SCROLL_BAR_SCROLLING_UNITS);
        return new TitledPane(JSplitPane.VERTICAL_SPLIT, TITLE_LABEL_SIZE, componentsTreePanel, treeScrollPane);
    }

    /**
     * Create the issues details panel. That is the bottom right issues table.
     *
     * @return the issues details panel
     */
    private JComponent createComponentsIssueDetailView() {
        issuesTable = new ComponentIssuesTable();
        JScrollPane tableScroll = ScrollPaneFactory.createScrollPane(issuesTable, SideBorder.ALL);
        tableScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        JLabel title = new JBLabel(" Component Issues Details");
        title.setFont(title.getFont().deriveFont(TITLE_FONT_SIZE));
        return new TitledPane(JSplitPane.VERTICAL_SPLIT, TITLE_LABEL_SIZE, title, tableScroll);
    }

    /**
     * Create the component details view. That is the top right details panel.
     *
     * @param supported - True if the current opened project is supported by the plugin.
     *                  If now, show the "Unsupported project type" message.
     * @return the component details view
     */
    private JComponent createComponentsDetailsView(boolean supported) {
        if (!GlobalSettings.getInstance().areCredentialsSet()) {
            return ComponentUtils.createNoCredentialsView();
        }
        JLabel title = new JBLabel(" Component Details");
        title.setFont(title.getFont().deriveFont(TITLE_FONT_SIZE));

        issuesDetailsPanel = new JBPanel(new BorderLayout()).withBackground(UIUtil.getTableBackground());
        String panelText = supported ? ComponentUtils.SELECT_COMPONENT_TEXT : ComponentUtils.UNSUPPORTED_TEXT;
        issuesDetailsPanel.add(ComponentUtils.createDisabledTextLabel(panelText), BorderLayout.CENTER);
        issuesDetailsScroll = ScrollPaneFactory.createScrollPane(issuesDetailsPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return new TitledPane(JSplitPane.VERTICAL_SPLIT, TITLE_LABEL_SIZE, title, issuesDetailsScroll);
    }

    /**
     * Update the issues table according to the user choice in the dependencies tree.
     */
    public void updateIssuesTable() {
        List<DependenciesTree> selectedNodes = getSelectedNodes();
        Set<Issue> issueSet = ScanManagersFactory.getScanManagers(mainProject)
                .stream()
                .map(scanManager -> scanManager.getFilteredScanIssues(FilterManagerService.getInstance(mainProject), selectedNodes))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        Set<String> selectedNodeNames = selectedNodes.stream().map(DefaultMutableTreeNode::toString).collect(Collectors.toSet());
        issuesTable.updateIssuesTable(issueSet, selectedNodeNames);
    }

    /**
     * Return the selected nodes in the dependencies tree.
     *
     * @return the selected nodes in the dependencies tree
     */
    private List<DependenciesTree> getSelectedNodes() {
        if (issuesTree.getModel() == null) {
            return Lists.newArrayList();
        }
        // If no node selected - Return the root
        if (issuesTree.getSelectionPaths() == null) {
            return Lists.newArrayList((DependenciesTree) issuesTree.getModel().getRoot());
        }
        return Arrays.stream(issuesTree.getSelectionPaths())
                .map(TreePath::getLastPathComponent)
                .map(obj -> (DependenciesTree) obj)
                .collect(Collectors.toList());
    }

    /**
     * Called after a change in the credentials.
     */
    public void onConfigurationChange() {
        issuesRightHorizontalSplit.setFirstComponent(createComponentsDetailsView(true));
        issuesPanel.validate();
        issuesPanel.repaint();
    }

    /**
     * Register the issues tree listeners.
     */
    public void registerListeners() {
        issuesTree.addTreeExpansionListener();

        // Issues component selection listener
        issuesTree.addTreeSelectionListener(e -> {
            updateIssuesTable();
            if (e == null || e.getNewLeadSelectionPath() == null) {
                return;
            }
            // Color the issues count panel
            for (TreePath path : e.getPaths()) {
                JPanel issueCountPanel = issuesCountPanels.get(path);
                if (issueCountPanel != null) {
                    issueCountPanel.setBackground(e.isAddedPath(path) ? UIUtil.getTreeSelectionBackground(true) : UIUtil.getTableBackground());
                }
            }
            ComponentIssueDetails.createIssuesDetailsView(issuesDetailsPanel, (DependenciesTree) e.getNewLeadSelectionPath().getLastPathComponent());
            // Scroll back to the beginning of the scrollable panel
            ApplicationManager.getApplication().invokeLater(() -> issuesDetailsScroll.getViewport().setViewPosition(new Point()));
        });

        issuesTree.addOnProjectChangeListener(mainProject.getMessageBus().connect());
    }

    private void addRightClickListener(IssuesTree tree) {
        MouseListener mouseListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleContextMenu(tree, e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleContextMenu(tree, e);
            }
        };
        tree.addMouseListener(mouseListener);
    }

    private void handleContextMenu(IssuesTree tree, MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }

        // Event is right-click.
        TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
        if (selPath == null) {
            return;
        }
        tree.setSelectionPath(selPath);
        DependenciesTree selectedNode = (DependenciesTree)selPath.getLastPathComponent();
        JBPopupMenu popupMenu = getNodePopupMenu(selectedNode);
        if (popupMenu != null) {
            popupMenu.show(tree, e.getX(), e.getY());
        }
    }

    private JBPopupMenu getNodePopupMenu(DependenciesTree selectedNode) {
        popupMenu.removeAll();
        NavigationService navigationService = NavigationService.getInstance(mainProject);
        Set<PsiElement> navigationCandidates = navigationService.getNavigation(selectedNode);
        if (navigationCandidates == null) {
            // Find parent for navigation.
            selectedNode = navigationService.getNavigableParent(selectedNode);
            if (selectedNode == null) {
                return null;
            }
            navigationCandidates = navigationService.getNavigation(selectedNode);
            if (navigationCandidates == null) {
                return null;
            }
        }
        PsiElement navigationTarget = navigationCandidates.iterator().next();
        JMenuItem jumpToElement = new JMenuItem(new AbstractAction(POPUP_MENU_HEADLINE) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (navigationTarget != null && navigationTarget instanceof Navigatable && ((Navigatable) navigationTarget).canNavigate()) {
                    ((Navigatable) navigationTarget).navigate(true);
                }
            }
        });
        popupMenu.add(jumpToElement);
        return popupMenu;
    }
}

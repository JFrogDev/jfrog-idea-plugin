package com.jfrog.ide.idea.ui.issues;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SideBorder;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.UIUtil;
import com.jfrog.ide.idea.Events;
import com.jfrog.ide.idea.configuration.GlobalSettings;
import com.jfrog.ide.idea.scan.ScanManagersFactory;
import com.jfrog.ide.idea.ui.DetailsViewFactory;
import com.jfrog.ide.idea.ui.components.FilterButton;
import com.jfrog.ide.idea.ui.components.IssuesTable;
import com.jfrog.ide.idea.ui.components.TitledPane;
import com.jfrog.ide.idea.ui.filters.IssueFilterMenu;
import com.jfrog.ide.idea.ui.models.IssuesTableModel;
import com.jfrog.ide.idea.ui.utils.ComponentUtils;
import org.jfrog.build.extractor.scan.DependenciesTree;
import org.jfrog.build.extractor.scan.Issue;

import javax.swing.*;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jfrog.ide.idea.ui.XrayToolWindow.*;

/**
 * @author yahavi
 */
public class IssuesTab {
    private final Project project;

    private Map<TreePath, JPanel> issuesCountPanels = Maps.newHashMap();
    private OnePixelSplitter issuesRightHorizontalSplit;
    private IssuesTree issuesTree = IssuesTree.getInstance();
    private JScrollPane issuesDetailsScroll;
    private JPanel issuesDetailsPanel;
    private JPanel issuesCountPanel;
    private JComponent issuesPanel;
    private JBTable issuesTable;
    private JLabel issuesCount;

    public IssuesTab(Project project) {
        this.project = project;
    }

    public JPanel createIssuesViewTab(boolean supported) {
        ActionToolbar toolbar = ComponentUtils.createActionToolbar(issuesTree);
        IssueFilterMenu issueFilterMenu = new IssueFilterMenu(project);
        JPanel filterButton = new FilterButton(issueFilterMenu, "Severity", "Select severities to show");
        SimpleToolWindowPanel filterPanel = new SimpleToolWindowPanel(false);
        filterPanel.setToolbar(toolbar.getComponent());
        filterPanel.setContent(filterButton);

        issuesPanel = createComponentsIssueDetailView();
        issuesRightHorizontalSplit = new OnePixelSplitter(true, 0.55f);
        issuesRightHorizontalSplit.setFirstComponent(createComponentsDetailsView(supported));
        issuesRightHorizontalSplit.setSecondComponent(issuesPanel);

        OnePixelSplitter centralVerticalSplit = new OnePixelSplitter(false, 0.33f);
        centralVerticalSplit.setFirstComponent(createIssuesComponentsTreeView());
        centralVerticalSplit.setSecondComponent(issuesRightHorizontalSplit);
        OnePixelSplitter issuesViewTab = new OnePixelSplitter(true, 0f);
        issuesViewTab.setResizeEnabled(false);
        issuesViewTab.setFirstComponent(filterPanel);
        issuesViewTab.setSecondComponent(centralVerticalSplit);
        return issuesViewTab;
    }

    private JComponent createIssuesComponentsTreeView() {
        issuesCount = new JBLabel("Issues (0) ");

        JPanel componentsTreePanel = new JBPanel(new BorderLayout()).withBackground(UIUtil.getTableBackground());
        JLabel componentsTreeTitle = new JBLabel(" Components Tree");
        componentsTreeTitle.setFont(componentsTreeTitle.getFont().deriveFont(TITLE_FONT_SIZE));
        componentsTreePanel.add(componentsTreeTitle, BorderLayout.LINE_START);
        componentsTreePanel.add(issuesCount, BorderLayout.LINE_END);

        issuesCountPanel = new JBPanel().withBackground(UIUtil.getTableBackground());
        issuesCountPanel.setLayout(new BoxLayout(issuesCountPanel, BoxLayout.Y_AXIS));
        issuesTree.createExpansionListener(issuesCountPanel, issuesCountPanels);

        JBPanel treePanel = new JBPanel(new BorderLayout()).withBackground(UIUtil.getTableBackground());
        TreeSpeedSearch treeSpeedSearch = new TreeSpeedSearch(issuesTree, ComponentUtils::getPathSearchString, true);
        treePanel.add(treeSpeedSearch.getComponent(), BorderLayout.WEST);
        treePanel.add(issuesCountPanel, BorderLayout.CENTER);
        JScrollPane treeScrollPane = ScrollPaneFactory.createScrollPane(treePanel);
        treeScrollPane.getVerticalScrollBar().setUnitIncrement(SCROLL_BAR_SCROLLING_UNITS);
        return new TitledPane(JSplitPane.VERTICAL_SPLIT, TITLE_LABEL_SIZE, componentsTreePanel, treeScrollPane);
    }

    private JComponent createComponentsIssueDetailView() {
        issuesTable = new IssuesTable();
        JScrollPane tableScroll = ScrollPaneFactory.createScrollPane(issuesTable, SideBorder.ALL);
        tableScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        JLabel title = new JBLabel(" Component Issues Details");
        title.setFont(title.getFont().deriveFont(TITLE_FONT_SIZE));
        return new TitledPane(JSplitPane.VERTICAL_SPLIT, TITLE_LABEL_SIZE, title, tableScroll);
    }

    private JComponent createComponentsDetailsView(boolean supported) {
        if (!GlobalSettings.getInstance().isCredentialsSet()) {
            return ComponentUtils.createNoCredentialsView(project);
        }
        if (!supported) {
            return ComponentUtils.createUnsupportedView();
        }
        JLabel title = new JBLabel(" Component Details");
        title.setFont(title.getFont().deriveFont(TITLE_FONT_SIZE));

        issuesDetailsPanel = new JBPanel(new BorderLayout()).withBackground(UIUtil.getTableBackground());
        issuesDetailsPanel.add(ComponentUtils.createDisabledTextLabel("Select component or issue for more details"), BorderLayout.CENTER);
        issuesDetailsScroll = ScrollPaneFactory.createScrollPane(issuesDetailsPanel, ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return new TitledPane(JSplitPane.VERTICAL_SPLIT, TITLE_LABEL_SIZE, title, issuesDetailsScroll);
    }

    public void updateIssuesTable() {
        java.util.List<DependenciesTree> selectedNodes = Lists.newArrayList((DependenciesTree) issuesTree.getModel().getRoot());
        if (issuesTree.getSelectionPaths() != null) {
            selectedNodes.clear();
            TreePath[] selectedTreeNodes = issuesTree.getSelectionPaths();
            for (TreePath treePath : selectedTreeNodes) {
                selectedNodes.add((DependenciesTree) treePath.getLastPathComponent());
            }
        }

        Set<Issue> issueSet = Sets.newHashSet();
        ScanManagersFactory.getScanManagers().forEach(scanManager ->
                issueSet.addAll(scanManager.getFilteredScanIssues(selectedNodes)));
        TableModel model = new IssuesTableModel(issueSet);
        TableRowSorter<TableModel> sorter = new TableRowSorter<>(model);
        issuesTable.setRowSorter(sorter);
        issuesTable.setModel(model);

        List<RowSorter.SortKey> sortKeys = new ArrayList<>();
        sortKeys.add(new RowSorter.SortKey(0, SortOrder.ASCENDING));
        sorter.setSortKeys(sortKeys);
        sorter.sort();

        resizeTableColumns();
        issuesTable.validate();
        issuesTable.repaint();
    }

    private void resizeTableColumns() {
        int tableWidth = issuesTable.getParent().getWidth();
        tableWidth -= (issuesTable.getColumnModel().getColumn(IssuesTableModel.IssueColumn.SEVERITY.ordinal()).getWidth());
        tableWidth -= (issuesTable.getColumnModel().getColumn(IssuesTableModel.IssueColumn.ISSUE_TYPE.ordinal()).getWidth());
        issuesTable.getColumnModel().getColumn(IssuesTableModel.IssueColumn.SUMMARY.ordinal()).setPreferredWidth((int) (tableWidth * 0.6));
        issuesTable.getColumnModel().getColumn(IssuesTableModel.IssueColumn.COMPONENT.ordinal()).setPreferredWidth((int) (tableWidth * 0.4));
    }

    public void populateTree(TreeModel issuesTreeModel) {
        DependenciesTree root = (DependenciesTree) issuesTreeModel.getRoot();
        issuesCount.setText("Issues (" + root.getIssueCount() + ") ");
        issuesTree.populateTree(issuesTreeModel);
    }

    public void onConfigurationChange() {
        issuesRightHorizontalSplit.setFirstComponent(createComponentsDetailsView(true));
        issuesPanel.validate();
        issuesPanel.repaint();
    }

    public void registerListeners(MessageBusConnection busConnection) {
        issuesTree.addTreeExpansionListener();

        // Issues component selection listener
        issuesTree.addTreeSelectionListener(e -> {
            updateIssuesTable();
            if (e == null || e.getNewLeadSelectionPath() == null) {
                return;
            }
            // Color the issues count panel
//            for (TreePath path : e.getPaths()) {
//                JPanel issueCountPanel = issuesCountPanels.get(path);
//                issueCountPanel.setBackground(e.isAddedPath(path) ? UIUtil.getTreeSelectionBackground() : UIUtil.getTableBackground());
//            }
            DetailsViewFactory.createIssuesDetailsView(issuesDetailsPanel, (DependenciesTree) e.getNewLeadSelectionPath().getLastPathComponent());
            // Scroll back to the beginning of the scrollable panel
            SwingUtilities.invokeLater(() -> issuesDetailsScroll.getViewport().setViewPosition(new Point()));
        });

        // Issues table listener
        busConnection.subscribe(Events.ON_SCAN_ISSUES_CHANGE, () -> ApplicationManager.getApplication().invokeLater(this::updateIssuesTable));

    }
}

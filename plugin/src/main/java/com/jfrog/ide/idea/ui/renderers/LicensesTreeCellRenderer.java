package com.jfrog.ide.idea.ui.renderers;

import com.intellij.ui.JBDefaultTreeCellRenderer;
import com.intellij.ui.table.JBTable;

import javax.swing.*;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;

import static com.intellij.util.ui.tree.WideSelectionTreeUI.TREE_TABLE_TREE_KEY;

/**
 * Created by Yahav Itzhak on 6 Dec 2017.
 */
public class LicensesTreeCellRenderer extends JBDefaultTreeCellRenderer {
    private static Font regularFont, moduleFont;
    private static JBTable emptyTable = new JBTable();

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
        DefaultTreeCellRenderer cellRenderer = (JBDefaultTreeCellRenderer) super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
//        DependenciesTree scanTreeNode = (DependenciesTree) value;
        if (regularFont == null) {
            setFonts(cellRenderer);
        }
        tree.putClientProperty(TREE_TABLE_TREE_KEY, emptyTable); // Avoid setting TreeUnfocusedSelectionBackground

        // Set icon
        cellRenderer.setIcon(null);

        // Set font
//        cellRenderer.setFont(scanTreeNode.isModule() ? moduleFont : regularFont);
        return cellRenderer;
    }

    private void setFonts(DefaultTreeCellRenderer cellRenderer) {
        Font font = cellRenderer.getFont();
        regularFont = new Font(font.getName(), Font.PLAIN, font.getSize());
        moduleFont = new Font(font.getName(), Font.BOLD, font.getSize() + 1);
    }
}
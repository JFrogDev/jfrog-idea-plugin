package com.jfrog.ide.idea.ui.filters;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBoxMenuItem;
import com.intellij.util.messages.MessageBus;
import org.jetbrains.annotations.NotNull;
import com.jfrog.ide.idea.Events;

import java.awt.event.ItemListener;
import java.util.List;
import java.util.Map;

/**
 * Created by Yahav Itzhak on 22 Nov 2017.
 */
class SelectAllCheckbox<FilterType> extends MenuCheckbox {
    SelectAllCheckbox() {
        setText("All");
        setSelected(true);
    }

    void setListeners(@NotNull Map<FilterType, Boolean> selectionMap, @NotNull List<SelectionCheckbox> checkBoxMenuItems) {
        removeListeners();
        addItemListener(e -> {
            selectionMap.entrySet().forEach(booleanEntry -> booleanEntry.setValue(isSelected()));

            for (JBCheckBoxMenuItem i : checkBoxMenuItems) {
                if (i.isSelected() != isSelected()) {
                    i.doClick(0);
                }
            }
            MessageBus messageBus = ApplicationManager.getApplication().getMessageBus();
            messageBus.syncPublisher(Events.ON_SCAN_FILTER_CHANGE).update();
        });
    }

    private void removeListeners() {
        for (ItemListener itemListener : getItemListeners()) {
            removeItemListener(itemListener);
        }
    }
}
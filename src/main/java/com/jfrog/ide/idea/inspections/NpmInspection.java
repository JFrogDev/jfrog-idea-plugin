package com.jfrog.ide.idea.inspections;

import com.google.common.collect.Sets;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.json.psi.JsonElementVisitor;
import com.intellij.json.psi.JsonProperty;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.jfrog.ide.idea.scan.ScanManager;
import com.jfrog.ide.idea.scan.ScanManagersFactory;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jfrog.build.extractor.scan.DependenciesTree;
import org.jfrog.build.extractor.scan.GeneralInfo;

import java.util.Set;

/**
 * @author yahavi
 */
@SuppressWarnings("InspectionDescriptionNotFoundInspection")
public class NpmInspection extends AbstractInspection {

    public NpmInspection() {
        super("package.json");
    }

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
        return new JsonElementVisitor() {
            @Override
            public void visitProperty(@NotNull JsonProperty element) {
                super.visitProperty(element);
                NpmInspection.this.visitElement(holder, element);
            }
        };
    }

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof JsonProperty) {
            NpmInspection.this.visitElement(holder, element);
        }
    }

    @Override
    PsiElement[] getTargetElements(PsiElement element) {
        return new PsiElement[]{element};
    }

    @Override
    boolean isDependency(PsiElement element) {
        PsiElement parentElement = element.getParent().getParent();
        return parentElement != null && StringUtils.equalsAny(parentElement.getFirstChild().getText(), "\"dependencies\"", "\"devDependencies\"");
    }

    @Override
    ScanManager getScanManager(Project project, String path) {
        return ScanManagersFactory.getScanManagers(project).stream()
                .filter(manager -> StringUtils.equals(manager.getProjectPath(), path))
                .findAny()
                .orElse(null);
    }

    @Override
    Set<DependenciesTree> getModules(PsiElement element, GeneralInfo generalInfo) {
        DependenciesTree root = getRootDependenciesTree(element);
        if (root == null) {
            return null;
        }

        // Single project, single module
        if (root.getGeneralInfo() != null) {
            return Sets.newHashSet(root);
        }

        // Multi project
        String path = element.getContainingFile().getVirtualFile().getParent().getPath();
        for (DependenciesTree child : root.getChildren()) {
            if (isContainingPath(child, path)) {
                return Sets.newHashSet(child);
            }
        }
        return null;
    }

    @Override
    GeneralInfo createGeneralInfo(PsiElement element) {
        String artifactId = StringUtils.unwrap(element.getFirstChild().getText(), "\"");
        return new GeneralInfo().artifactId(artifactId).groupId(artifactId);
    }

    /**
     * Return true if and only if the dependencies tree node containing the input path.
     *
     * @param node - The dependencies tree node
     * @param path - The path to check
     * @return true if and only if the dependencies tree node containing the input path
     */
    private boolean isContainingPath(DependenciesTree node, String path) {
        GeneralInfo childGeneralInfo = node.getGeneralInfo();
        return StringUtils.equals(childGeneralInfo.getPath(), path);
    }
}
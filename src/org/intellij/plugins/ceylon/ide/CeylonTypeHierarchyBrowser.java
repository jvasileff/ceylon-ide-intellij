package org.intellij.plugins.ceylon.ide;

import com.intellij.ide.hierarchy.HierarchyBrowserManager;
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor;
import com.intellij.ide.hierarchy.HierarchyTreeStructure;
import com.intellij.ide.hierarchy.TypeHierarchyBrowserBase;
import com.intellij.ide.util.treeView.AlphaComparator;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.ide.util.treeView.SourceComparator;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ui.util.CompositeAppearance;
import com.intellij.openapi.util.Comparing;
import com.intellij.psi.PsiElement;
import com.redhat.ceylon.ide.common.model.CeylonProject;
import com.redhat.ceylon.model.typechecker.model.*;
import com.redhat.ceylon.model.typechecker.model.Package;
import org.intellij.plugins.ceylon.ide.ceylonCode.highlighting.highlighter_;
import org.intellij.plugins.ceylon.ide.ceylonCode.model.IdeaCeylonProjects;
import org.intellij.plugins.ceylon.ide.ceylonCode.psi.CeylonPsi;
import org.intellij.plugins.ceylon.ide.ceylonCode.psi.ceylonDeclarationDescriptionProvider_;
import org.intellij.plugins.ceylon.ide.ceylonCode.resolve.CeylonReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

public class CeylonTypeHierarchyBrowser extends TypeHierarchyBrowserBase {

    ceylonDeclarationDescriptionProvider_ provider =
            ceylonDeclarationDescriptionProvider_.get_();
    private Project project;

    CeylonTypeHierarchyBrowser(Project project, PsiElement element) {
        super(project, element);
        this.project = project;
    }

    @Nullable
    @Override
    protected HierarchyTreeStructure createHierarchyTreeStructure(@NotNull String typeName, @NotNull PsiElement psiElement) {
        final CeylonPsi.ClassOrInterfacePsi element =
                (CeylonPsi.ClassOrInterfacePsi) psiElement;
        if (SUPERTYPES_HIERARCHY_TYPE.equals(typeName)) {
            return new SubtypeHierarchyTreeStructure(element);
        }
        else if (SUBTYPES_HIERARCHY_TYPE.equals(typeName)) {
            return new SupertypeHierarchyTreeStructure(element);
        }
        else if (TYPE_HIERARCHY_TYPE.equals(typeName)) {
            return null;
        }
        else {
            return null;
        }
    }

    @Override
    protected void createTrees(@NotNull Map<String, JTree> trees) {
        createTreeAndSetupCommonActions(trees, IdeActions.GROUP_TYPE_HIERARCHY_POPUP);
    }

    @Nullable
    @Override
    protected PsiElement getElementFromDescriptor(
            @NotNull HierarchyNodeDescriptor hierarchyNodeDescriptor) {
        return hierarchyNodeDescriptor.getPsiElement();
    }

    @Override
    protected boolean isInterface(PsiElement psiElement) {
        return psiElement instanceof CeylonPsi.AnyInterfacePsi;
    }

    @Override
    protected boolean canBeDeleted(PsiElement psiElement) {
        return false;
    }

    @Override
    protected String getQualifiedName(PsiElement psiElement) {
        return ((CeylonPsi.DeclarationPsi) psiElement).getCeylonNode()
                .getDeclarationModel()
                .getQualifiedNameString();
    }

    @Nullable
    @Override
    protected JPanel createLegendPanel() {
        //OK!
        return null;
    }

    @Override
    protected boolean isApplicableElement(@NotNull PsiElement psiElement) {
        return psiElement instanceof CeylonPsi.ClassOrInterfacePsi;
    }

    @Nullable
    @Override
    protected Comparator<NodeDescriptor> getComparator() {
        if (HierarchyBrowserManager.getInstance(project)
                .getState().SORT_ALPHABETICALLY) {
            return AlphaComparator.INSTANCE;
        }
        else {
            //TODO: probably does not work!
            return SourceComparator.INSTANCE;
        }
    }

    private Set<Module> collectModules() {
        Set<Module> result = new HashSet<>();
        IdeaCeylonProjects ceylonProjects = project.getComponent(IdeaCeylonProjects.class);
        for (com.intellij.openapi.module.Module mod: ModuleManager.getInstance(project).getModules()) {
            CeylonProject.Modules modules = ceylonProjects.getProject(mod).getModules();
            result.addAll(modules.getTypecheckerModules().getListOfModules());
        }
        return result;
    }

    private class TypeHierarchyNodeDescriptor extends HierarchyNodeDescriptor {
        private CeylonPsi.TypeDeclarationPsi element;

        private TypeHierarchyNodeDescriptor(@NotNull CeylonPsi.TypeDeclarationPsi element) {
            super(project, null, element, true);
            this.element = element;
            myName = element.getCeylonNode().getIdentifier().getText();
        }
        private TypeHierarchyNodeDescriptor(@NotNull NodeDescriptor parentDescriptor,
                                           @NotNull CeylonPsi.TypeDeclarationPsi element) {
            super(project, parentDescriptor, element, false);
            this.element = element;
            myName = element.getCeylonNode().getIdentifier().getText();
        }

        @Override
        public boolean update() {
            boolean changes = super.update();
            final CompositeAppearance oldText = myHighlightedText;
            myHighlightedText = new CompositeAppearance();
            String description =
                    "'" + provider.getDescription(element, false) + "'";
            highlighter_.get_()
                    .highlightCompositeAppearance(myHighlightedText, description, project);
            Unit unit = element.getCeylonNode().getUnit();
            if (unit!=null) {
                String qualifiedNameString =
                        unit.getPackage()
                            .getQualifiedNameString();
                myHighlightedText.getEnding()
                        .addText(" (" + qualifiedNameString + ")",
                            getPackageNameAttributes());
            }
            if (!Comparing.equal(myHighlightedText, oldText)) {
                changes = true;
            }
            return changes;
        }
    }

    private class SubtypeHierarchyTreeStructure extends HierarchyTreeStructure {
        public SubtypeHierarchyTreeStructure(CeylonPsi.ClassOrInterfacePsi element) {
            super(CeylonTypeHierarchyBrowser.this.project, new TypeHierarchyNodeDescriptor(element));
        }

        @NotNull
        @Override
        protected Object[] buildChildren(@NotNull HierarchyNodeDescriptor parent) {
            TypeHierarchyNodeDescriptor descriptor = (TypeHierarchyNodeDescriptor) parent;
            List<HierarchyNodeDescriptor> result = new ArrayList<HierarchyNodeDescriptor>();
            TypeDeclaration model = descriptor.element.getCeylonNode().getDeclarationModel();
            if (model!=null) {
                Type cl = model.getExtendedType();
                if (cl != null) {
                    PsiElement psiElement
                            = CeylonReference.resolveDeclaration(cl.getDeclaration(), project);
                    //TODO: what about Java types in the hierarchy!!!!
                    if (psiElement instanceof CeylonPsi.TypeDeclarationPsi) {
                        result.add(new TypeHierarchyNodeDescriptor(parent,
                                (CeylonPsi.TypeDeclarationPsi) psiElement));
                    }
                }
                for (Type type : model.getSatisfiedTypes()) {
                    PsiElement psiElement
                            = CeylonReference.resolveDeclaration(type.getDeclaration(), project);
                    //TODO: what about Java types in the hierarchy!!!!
                    if (psiElement instanceof CeylonPsi.TypeDeclarationPsi) {
                        result.add(new TypeHierarchyNodeDescriptor(parent,
                                (CeylonPsi.TypeDeclarationPsi) psiElement));
                    }
                }
            }
            return result.toArray(new HierarchyNodeDescriptor[0]);
        }
    }

    private class SupertypeHierarchyTreeStructure extends HierarchyTreeStructure {
        private final Set<Module> modules;

        private SupertypeHierarchyTreeStructure(CeylonPsi.ClassOrInterfacePsi element) {
            super(CeylonTypeHierarchyBrowser.this.project, new TypeHierarchyNodeDescriptor(element));
            modules = collectModules();
        }

        @NotNull
        @Override
        protected Object[] buildChildren(@NotNull HierarchyNodeDescriptor parent) {
            TypeHierarchyNodeDescriptor descriptor = (TypeHierarchyNodeDescriptor) parent;
            List<HierarchyNodeDescriptor> result = new ArrayList<HierarchyNodeDescriptor>();
            TypeDeclaration model = descriptor.element.getCeylonNode().getDeclarationModel();
            if (model!=null) {
                for (Module module : modules) {
                    for (Package pack : new ArrayList<Package>(module.getPackages())) {
                        for (Unit unit : pack.getUnits()) {
                            for (Declaration declaration : unit.getDeclarations()) {
                                if (declaration instanceof ClassOrInterface) {
                                    ClassOrInterface ci = (ClassOrInterface) declaration;
                                    Type extendedType = ci.getExtendedType();
                                    if (extendedType != null) {
                                        if (extendedType.getDeclaration().equals(model)) {
                                            PsiElement psiElement
                                                    = CeylonReference.resolveDeclaration(ci, project);
                                            if (psiElement instanceof CeylonPsi.TypeDeclarationPsi) {
                                                result.add(new TypeHierarchyNodeDescriptor(descriptor,
                                                        (CeylonPsi.TypeDeclarationPsi) psiElement));
                                            }
                                        }
                                    }
                                    for (Type satisfiedType : ci.getSatisfiedTypes()) {
                                        if (satisfiedType.getDeclaration().equals(model)) {
                                            PsiElement psiElement
                                                    = CeylonReference.resolveDeclaration(ci, project);
                                            if (psiElement instanceof CeylonPsi.TypeDeclarationPsi) {
                                                result.add(new TypeHierarchyNodeDescriptor(descriptor,
                                                        (CeylonPsi.TypeDeclarationPsi) psiElement));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return result.toArray(new HierarchyNodeDescriptor[0]);
        }
    }

}
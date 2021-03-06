package org.intellij.plugins.ceylon.ide.structureView;

import org.intellij.plugins.ceylon.ide.ceylonCode.psi.CeylonPsi;
import org.intellij.plugins.ceylon.ide.ceylonCode.util.icons_;

import javax.swing.*;

class CeylonVariableTreeElement extends CeylonDeclarationTreeElement<CeylonPsi.VariablePsi> {

    CeylonVariableTreeElement(CeylonPsi.VariablePsi declaration, boolean isInherited) {
        super(declaration, isInherited);
    }

}

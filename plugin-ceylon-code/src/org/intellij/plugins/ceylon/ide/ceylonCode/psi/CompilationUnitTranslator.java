package org.intellij.plugins.ceylon.ide.ceylonCode.psi;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.impl.source.tree.PsiWhiteSpaceImpl;
import com.intellij.psi.impl.source.tree.TreeElement;
import com.intellij.psi.tree.IElementType;
import com.redhat.ceylon.compiler.typechecker.parser.CeylonLexer;
import com.redhat.ceylon.compiler.typechecker.tree.Node;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * This class avoids calling a custom parser. Instead, we transform the Ceylon AST generated by
 * the official parser into an ASTNode tree. Comments and whitespaces are not present in the Ceylon AST,
 * but we can retrieve them by synchronizing our transformation with a lexer.
 */
public class CompilationUnitTranslator extends Visitor {
    private CompositeElement parent;
    private PsiFile file;
    private Queue<CommonToken> customizedTokens;
    private boolean verbose;
    private int index = 0;

    private static Logger LOGGER = Logger.getInstance(IdeaCeylonParser.class);

    public CompilationUnitTranslator(PsiFile file, boolean verbose) {
        this.file = file;
        this.verbose = verbose;
    }


    // For debugging purposes only
    private void dump(ASTNode root, String indent) {
        System.out.println(indent + root.getElementType() + (root instanceof LeafPsiElement ? " (" + root.getText() + ")" : ""));
        for (ASTNode child : root.getChildren(null)) {
            dump(child, indent + "  ");
        }
    }

    public ASTNode translateToAstNode(Tree.CompilationUnit cu, List<CommonToken> originalTokens) {
        customizedTokens = new LinkedList<>();
        Ref<CommonToken> lastToken = new Ref<>();

        for (CommonToken token : originalTokens) {
            if (token != null) {
                int lastStopIndex = -1;
                boolean lastWasEof = false;
                if (!lastToken.isNull()) {
                    lastStopIndex = lastToken.get().getStopIndex();
                    lastWasEof = lastToken.get().getType() == CeylonLexer.EOF;
                }
                if (!lastWasEof && lastStopIndex != token.getStartIndex() - 1) {
                    CommonToken badToken = new CommonToken(token.getInputStream(), -2, 0,
                            lastStopIndex + 1,
                            token.getStartIndex() - 1);
                    customizedTokens.add(badToken);
                }
                customizedTokens.add(token);
                lastToken.set(token);
            }
        }

        if (lastToken.get().getStopIndex() < file.getTextLength()) {
            CommonToken badToken = new CommonToken(lastToken.get().getInputStream(), -2, 0,
                    lastToken.get().getStopIndex() + 1,
                    file.getTextLength() - 1);
            customizedTokens.add(badToken);
        }

        visit(cu);

        ASTNode root = parent;
        if (verbose) {
            dump(root, "");
        }
        return root;
    }

    @Override
    public void visit(Tree.CompilationUnit that) {
        super.visit(that);

        while (!customizedTokens.isEmpty()) {
            Token token = customizedTokens.remove();

            if (token.getType() != CeylonLexer.EOF) {
                parent.rawAddChildrenWithoutNotifications(buildLeaf(null, getElementType(token.getType()), token));
            }

            if (verbose && !parserConstants_.get_().get_NODES_ALLOWED_AT_EOF().contains(token.getType())) {
                LOGGER.error("Unexpected token " + token + " in " + file.getName());
            }
        }

        final int[] parentAndFileTextLength = new int[2];
        ApplicationManager.getApplication().runReadAction(new Runnable() {
            @Override
            public void run() {
                parentAndFileTextLength[0] = parent.getTextLength();
                parentAndFileTextLength[1] = file.getTextLength();
            }
        });
        int parentTextLength = parentAndFileTextLength[0];
        int fileTextLength = parentAndFileTextLength[1];
        if (parentTextLength < fileTextLength) {
            String notParsed = file.getText().substring(parentTextLength);
            parent.rawAddChildrenWithoutNotifications(new LeafPsiElement(TokenType.BAD_CHARACTER, notParsed));
        }
    }

    @Override
    public void visitAny(Node that) {
        IElementType type = NodeToIElementTypeMap.get(that);
        boolean parentForced = false;

        if (type == null) {
            LOGGER.error("Unknown IElementType for " + that + " in " + that.getUnit().getFullPath());
            return;
        }
        if (parent == null) {
            parentForced = true;
            parent = new CompositeElement(type);
        }

        if (that instanceof Tree.DocLink) {
            return;
        }
        index = consumeTokens(that, index, true);

        Token token = that.getMainToken();

        OrderedChildrenVisitor visitor = new OrderedChildrenVisitor();
        try {
            that.visitChildren(visitor);
        } catch (Exception e) {
            that.handleException(e, visitor);
        }

        if (that.getToken() != null && visitor.children.isEmpty()) {
            Token peek = customizedTokens.peek();
            if (getTokenLength(peek) == that.getEndIndex() - that.getStartIndex()) {
                Token toRemove = customizedTokens.remove();
                parent.rawAddChildrenWithoutNotifications(buildLeaf(that, type, toRemove));
                if (verbose) {
                    System.out.println("t \"" + toRemove.getText() + "\"");
                }
                index += getTokenLength(toRemove);
            } else {
                CompositeElement comp = new CompositeElement(type);

                while (index < that.getEndIndex()) {
                    Token toRemove = customizedTokens.remove();
                    comp.rawAddChildrenWithoutNotifications(buildLeaf(null, getElementType(token.getType()), toRemove));
                    if (verbose) {
                        System.out.println("t \"" + toRemove.getText() + "\"");
                    }
                    index += getTokenLength(toRemove);
                }

                parent.rawAddChildrenWithoutNotifications(comp);
            }

            // TODO should be == but sometimes the tree includes a node that was already included before
            // (see `exists` constructs for example)
            assert index >= that.getEndIndex();
        } else {
            CompositeElement oldParent = parent;
            if (!parentForced) {
                parent = new CompositeElement(type);
                oldParent.rawAddChildrenWithoutNotifications(parent);
            }
            parent.putUserData(parserConstants_.get_().get_CEYLON_NODE_KEY(), that);

            for (Node child : visitor.getChildren()) {
                visitAny(child);
            }

            index = consumeTokens(that, index, false);

            parent = oldParent;
        }
    }

    private IElementType getElementType(int idx) {
        if (idx == -2) {
            return TokenType.BAD_CHARACTER;
        }
        return TokenTypes.fromInt(idx);
    }

    @NotNull
    private TreeElement buildLeaf(Node ceylonNode, IElementType type, Token token) {
        String tokenText = token.getText();
        if (tokenText.length() != getTokenLength(token)) {
            switch (token.getType()) {
                case CeylonLexer.PIDENTIFIER:
                case CeylonLexer.AIDENTIFIER:
                case CeylonLexer.LIDENTIFIER:
                    tokenText = "\\i" + tokenText;
                    break;
                case CeylonLexer.UIDENTIFIER:
                    tokenText = "\\I" + tokenText;
                    break;
                default:
                    throw new UnsupportedOperationException("Unsupported token type " + token);
            }
        }
        if (parserConstants_.get_().get_LEAVES_TO_WRAP().contains(type)) {
            CompositeElement comp = new CompositeElement(type);
            LeafPsiElement leaf = new LeafPsiElement(TokenTypes.fromInt(token.getType()), tokenText);
            comp.rawAddChildrenWithoutNotifications(leaf);
            comp.putUserData(parserConstants_.get_().get_CEYLON_NODE_KEY(), ceylonNode);
            return comp;
        } else if (type == TokenType.WHITE_SPACE || token.getType() == CeylonLexer.WS) {
            return new PsiWhiteSpaceImpl(tokenText);
        } else {
            return new LeafPsiElement(getElementType(token.getType()), tokenText);
        }
    }

    private int consumeTokens(Node that, int index, boolean before) {
        Integer targetIndex = before ? that.getStartIndex() : that.getEndIndex();

        if (targetIndex == null) {
            return index;
        }

        if (index > targetIndex) {
            if (verbose) {
                System.out.println(String.format("WARN : index (%d) > targetIndex (%d)", index, targetIndex));
            }
            return index;
        }

        while (index < targetIndex) {
            Token token = customizedTokens.remove();
            String text = token.getText();
            if (token.getType() == CeylonLexer.LINE_COMMENT && text.endsWith("\n")) {
                parent.rawAddChildrenWithoutNotifications(new LeafPsiElement(getElementType(token.getType()),
                        text.substring(0, text.length() - 1)));
                parent.rawAddChildrenWithoutNotifications(new PsiWhiteSpaceImpl("\n"));
            } else {
                parent.rawAddChildrenWithoutNotifications(buildLeaf(null, getElementType(token.getType()), token));
            }
            index += getTokenLength(token);
            if (verbose) {
                System.out.println("c \"" + text + "\"");
            }
        }

        assert index == targetIndex;

        return index;
    }

    private int getTokenLength(Token token) {
        if (token instanceof CommonToken) {
            CommonToken commonToken = (CommonToken) token;
            return commonToken.getStopIndex() - commonToken.getStartIndex() + 1;
        } else {
            return token.getText().length();
        }
    }

    private static class OrderedChildrenVisitor extends Visitor {
        List<Node> children = new ArrayList<>();

        @Override
        public void visitAny(Node that) {
            children.add(that);
        }

        public List<Node> getChildren() {
            Collections.sort(children, new Comparator<Node>() {
                @Override
                public int compare(@NotNull Node o1, @NotNull Node o2) {
                    Integer idx1 = o1.getStartIndex();
                    Integer idx2 = o2.getStartIndex();

                    if (idx1 == null) {
                        return idx2 == null ? 0 : 1;
                    }
                    if (idx2 == null) {
                        return -1;
                    }

                    return idx1.compareTo(idx2);
                }
            });

            return children;
        }
    }
}
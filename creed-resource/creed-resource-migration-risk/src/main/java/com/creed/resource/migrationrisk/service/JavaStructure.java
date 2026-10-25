package com.creed.resource.migrationrisk.service;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a Java file declares, at the granularity a sync decision needs: types, their methods and their
 * fields, each with a line range and a <em>normalized</em> text.
 *
 * <p>RefactoringMiner only reports refactorings — a method whose body changed without being renamed or
 * moved does not appear in its output at all. So "which methods did repo1 modify" is answered here,
 * by comparing the two versions' normalized declarations.
 *
 * <p>Normalized = JDT's {@code toString()} of the node with its Javadoc dropped: canonical formatting, no
 * comments. A reformat or a comment edit therefore is not a change, and "repo2 still has this method
 * unchanged" survives repo2 re-indenting the file.
 */
public final class JavaStructure {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** A method; {@code key} is {@code name(Type1,Type2)} with constructors named {@code <init>}. */
    public record MethodInfo(String key, String name, int startLine, int endLine, String normalized) {
    }

    /** {@code fields} holds each field declaration's normalized text, in declaration order. */
    public record TypeInfo(String qualifiedName, String simpleName, boolean topLevel, int startLine, int endLine,
                           Map<String, MethodInfo> methods, Set<String> fields) {

        public Optional<MethodInfo> method(String key) {
            return Optional.ofNullable(methods.get(key));
        }

        public boolean hasMethodNamed(String name) {
            return methods.values().stream().anyMatch(m -> m.name().equals(name));
        }
    }

    private final Map<String, TypeInfo> types;

    private JavaStructure(Map<String, TypeInfo> types) {
        this.types = types;
    }

    public static JavaStructure parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        String pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName() + ".";
        Map<String, TypeInfo> types = new LinkedHashMap<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                add(node);
                return true;
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                add(node);
                return true;
            }

            @Override
            public boolean visit(RecordDeclaration node) {
                add(node);
                return true;
            }

            @Override
            public boolean visit(AnnotationTypeDeclaration node) {
                add(node);
                return true;
            }

            private void add(AbstractTypeDeclaration type) {
                String qualified = pkg + enclosingNames(type);
                types.put(qualified, typeInfo(unit, type, qualified));
            }
        });
        return new JavaStructure(types);
    }

    /** Nested types are named {@code Outer.Inner}; local and anonymous classes are not types here. */
    private static String enclosingNames(AbstractTypeDeclaration type) {
        StringBuilder name = new StringBuilder(type.getName().getIdentifier());
        for (ASTNode parent = type.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof AbstractTypeDeclaration outer) {
                name.insert(0, outer.getName().getIdentifier() + ".");
            }
        }
        return name.toString();
    }

    private static TypeInfo typeInfo(CompilationUnit unit, AbstractTypeDeclaration type, String qualified) {
        Map<String, MethodInfo> methods = new LinkedHashMap<>();
        Set<String> fields = new LinkedHashSet<>();
        for (Object declaration : type.bodyDeclarations()) {
            if (declaration instanceof MethodDeclaration method) {
                MethodInfo info = methodInfo(unit, method);
                methods.put(info.key(), info);
            } else if (declaration instanceof FieldDeclaration field) {
                fields.add(normalize(field));
            }
        }
        return new TypeInfo(qualified, type.getName().getIdentifier(), type.getParent() instanceof CompilationUnit,
                line(unit, type.getStartPosition()), endLine(unit, type), methods, fields);
    }

    private static MethodInfo methodInfo(CompilationUnit unit, MethodDeclaration method) {
        String name = method.isConstructor() ? "<init>" : method.getName().getIdentifier();
        List<String> parameterTypes = new ArrayList<>();
        for (Object p : method.parameters()) {
            SingleVariableDeclaration parameter = (SingleVariableDeclaration) p;
            parameterTypes.add(parameter.getType() + "[]".repeat(parameter.extraDimensions().size())
                    + (parameter.isVarargs() ? "..." : ""));
        }
        return new MethodInfo(name + "(" + String.join(",", parameterTypes) + ")", name,
                line(unit, method.getStartPosition()), endLine(unit, method), normalize(method));
    }

    private static String normalize(BodyDeclaration node) {
        BodyDeclaration copy = (BodyDeclaration) ASTNode.copySubtree(node.getAST(), node);
        copy.setJavadoc(null);
        return WHITESPACE.matcher(copy.toString()).replaceAll(" ").trim();
    }

    private static int line(CompilationUnit unit, int position) {
        return unit.getLineNumber(position);
    }

    private static int endLine(CompilationUnit unit, ASTNode node) {
        return unit.getLineNumber(node.getStartPosition() + Math.max(0, node.getLength() - 1));
    }

    public Map<String, TypeInfo> types() {
        return types;
    }

    public Optional<TypeInfo> type(String qualifiedName) {
        return Optional.ofNullable(types.get(qualifiedName));
    }

    /** Top-level types only — what a file "is" when pairing a moved file with its new version. */
    public List<TypeInfo> topLevelTypes() {
        return types.values().stream().filter(TypeInfo::topLevel).toList();
    }
}

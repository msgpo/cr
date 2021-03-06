/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.internal.jextract.impl;

import jdk.incubator.foreign.*;
import jdk.incubator.jextract.Declaration;
import jdk.incubator.jextract.Type;
import jdk.incubator.jextract.Type.Primitive;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.constant.ClassDesc;
import java.lang.invoke.MethodType;
import java.net.URI;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * Scan a header file and generate Java source items for entities defined in that header
 * file. Tree visitor visit methods return true/false depending on whether a
 * particular Tree is processed or skipped.
 */
public class OutputFactory implements Declaration.Visitor<Void, Declaration> {
    private final Set<String> constants = new HashSet<>();
    // To detect duplicate Variable and Function declarations.
    private final Set<Declaration.Variable> variables = new HashSet<>();
    private final Set<Declaration.Function> functions = new HashSet<>();

    protected final HeaderBuilder toplevelBuilder;
    protected JavaSourceBuilder currentBuilder;
    protected final ConstantHelper constantHelper;
    protected final TypeTranslator typeTranslator = new TypeTranslator();
    private final String pkgName;
    private final Map<Declaration, String> structClassNames = new HashMap<>();
    private final Set<Declaration.Typedef> unresolvedStructTypedefs = new HashSet<>();

    private String addStructDefinition(Declaration decl, String name) {
        return structClassNames.put(decl, name);
    }

    private boolean structDefinitionSeen(Declaration decl) {
        return structClassNames.containsKey(decl);
    }

    private String structDefinitionName(Declaration decl) {
        return structClassNames.get(decl);
    }

    // have we seen this Variable earlier?
    protected boolean variableSeen(Declaration.Variable tree) {
        return !variables.add(tree);
    }

    // have we seen this Function earlier?
    protected boolean functionSeen(Declaration.Function tree) {
        return !functions.add(tree);
    }

    public static JavaFileObject[] generateWrapped(Declaration.Scoped decl, String headerName, String pkgName, List<String> libraryNames) {
        String clsName = Utils.javaSafeIdentifier(headerName.replace(".h", "_h"), true);
        String qualName = pkgName.isEmpty() ? clsName : pkgName + "." + clsName;
        ConstantHelper constantHelper = new ConstantHelper(qualName,
                ClassDesc.of(pkgName, "RuntimeHelper"), ClassDesc.of("jdk.incubator.foreign", "CSupport"),
                libraryNames.toArray(String[]::new));
        return new OutputFactory(pkgName,
                new HeaderBuilder(clsName, pkgName, constantHelper), constantHelper).generate(decl);
    }

    private OutputFactory(String pkgName, HeaderBuilder toplevelBuilder, ConstantHelper constantHelper) {
        this.pkgName = pkgName;
        this.toplevelBuilder = toplevelBuilder;
        this.currentBuilder = toplevelBuilder;
        this.constantHelper = constantHelper;
    }

    private static String getCLangConstantsHolder() {
        String prefix = "jdk.incubator.foreign.CSupport.";
        String abi = CSupport.getSystemLinker().name();
        switch (abi) {
            case CSupport.SysV.NAME:
                return prefix + "SysV";
            case CSupport.Win64.NAME:
                return prefix + "Win64";
            case CSupport.AArch64.NAME:
                return prefix + "AArch64";
            default:
                throw new UnsupportedOperationException("Unsupported ABI: " + abi);
        }
    }

    static final String C_LANG_CONSTANTS_HOLDER = getCLangConstantsHolder();

    JavaFileObject[] generate(Declaration.Scoped decl) {
        toplevelBuilder.classBegin();
        //generate all decls
        decl.members().forEach(this::generateDecl);
        // check if unresolved typedefs can be resolved now!
        for (Declaration.Typedef td : unresolvedStructTypedefs) {
            Declaration.Scoped structDef = ((Type.Declared)td.type()).tree();
            if (structDefinitionSeen(structDef)) {
                toplevelBuilder.emitTypedef(td.name(), structDefinitionName(structDef));
            }
        }
        toplevelBuilder.classEnd();
        try {
            List<JavaFileObject> files = new ArrayList<>();
            files.add(toplevelBuilder.build());
            files.addAll(constantHelper.getClasses());
            files.add(fileFromString(pkgName,"RuntimeHelper", getRuntimeHelperSource()));
            return files.toArray(new JavaFileObject[0]);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (URISyntaxException ex2) {
            throw new RuntimeException(ex2);
        }
    }

    private String getRuntimeHelperSource() throws URISyntaxException, IOException {
        URL runtimeHelper = OutputFactory.class.getResource("resources/RuntimeHelper.java.template");
        return (pkgName.isEmpty()? "" : "package " + pkgName + ";\n") +
                        String.join("\n", Files.readAllLines(Paths.get(runtimeHelper.toURI())))
                                .replace("${C_LANG}", C_LANG_CONSTANTS_HOLDER);
    }

    private void generateDecl(Declaration tree) {
        try {
            tree.accept(this, null);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static Class<?> classForType(Primitive.Kind type, MemoryLayout layout) {
        boolean isFloat = switch(type) {
            case Float, Double, LongDouble -> true;
            default-> false;
        };
        return TypeTranslator.layoutToClass(isFloat, layout);
    }

    private JavaFileObject fileFromString(String pkgName, String clsName, String contents) {
        String pkgPrefix = pkgName.isEmpty() ? "" : pkgName.replaceAll("\\.", "/") + "/";
        return new SimpleJavaFileObject(URI.create(pkgPrefix + clsName + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return contents;
            }
        };
    }

    @Override
    public Void visitConstant(Declaration.Constant constant, Declaration parent) {
        if (!constants.add(constant.name())) {
            //skip
            return null;
        }

        toplevelBuilder.addConstantGetter(Utils.javaSafeIdentifier(constant.name()),
                constant.value() instanceof String ? MemorySegment.class :
                typeTranslator.getJavaType(constant.type()), constant.value());
        return null;
    }

    @Override
    public Void visitScoped(Declaration.Scoped d, Declaration parent) {
        if (d.layout().isEmpty()) {
            //skip decl-only
            return null;
        }
        boolean structClass = false;
        if (!d.name().isEmpty() || !isRecord(parent)) {
            //only add explicit struct layout if the struct is not to be flattened inside another struct
            switch (d.kind()) {
                case STRUCT:
                case UNION: {
                    structClass = true;
                    String className = d.name().isEmpty() ? parent.name() : d.name();
                    currentBuilder = new StructBuilder(currentBuilder, className, pkgName, constantHelper);
                    addStructDefinition(d, currentBuilder.className);
                    currentBuilder.incrAlign();
                    currentBuilder.classBegin();
                    currentBuilder.addLayoutGetter(className, d.layout().get());
                    break;
                }
            }
        }
        d.members().forEach(fieldTree -> fieldTree.accept(this, d));
        if (structClass) {
            currentBuilder = currentBuilder.classEnd();
            currentBuilder.decrAlign();
        }
        return null;
    }

    @Override
    public Void visitFunction(Declaration.Function funcTree, Declaration parent) {
        if (functionSeen(funcTree)) {
            return null;
        }

        MethodType mtype = typeTranslator.getMethodType(funcTree.type());
        FunctionDescriptor descriptor = Type.descriptorFor(funcTree.type()).orElse(null);
        if (descriptor == null) {
            //abort
            return null;
        }
        String mhName = Utils.javaSafeIdentifier(funcTree.name());
        toplevelBuilder.addMethodHandleGetter(mhName, funcTree.name(), mtype, descriptor, funcTree.type().varargs());
        //generate static wrapper for function
        List<String> paramNames = funcTree.parameters()
                                          .stream()
                                          .map(Declaration.Variable::name)
                                          .map(p -> !p.isEmpty() ? Utils.javaSafeIdentifier(p) : p)
                                          .collect(Collectors.toList());
        toplevelBuilder.addStaticFunctionWrapper(Utils.javaSafeIdentifier(funcTree.name()), funcTree.name(), mtype,
                Type.descriptorFor(funcTree.type()).orElseThrow(), funcTree.type().varargs(), paramNames);
        int i = 0;
        for (Declaration.Variable param : funcTree.parameters()) {
            Type.Function f = getAsFunctionPointer(param.type());
            if (f != null) {
                String name = funcTree.name() + "$" + (param.name().isEmpty() ? "x" + i : param.name());
                name = Utils.javaSafeIdentifier(name);
                //generate functional interface
                if (f.varargs()) {
                    System.err.println("WARNING: varargs in callbacks is not supported");
                }
                MethodType fitype = typeTranslator.getMethodType(f, false);
                toplevelBuilder.addFunctionalInterface(name, fitype, Type.descriptorFor(f).orElseThrow());
                i++;
            }
        }
        return null;
    }

    Type.Function getAsFunctionPointer(Type type) {
        if (type instanceof Type.Delegated) {
            switch (((Type.Delegated) type).kind()) {
                case POINTER: {
                    Type pointee = ((Type.Delegated) type).type();
                    return (pointee instanceof Type.Function) ?
                        (Type.Function)pointee : null;
                }
                default:
                    return getAsFunctionPointer(((Type.Delegated) type).type());
            }
        } else {
            return null;
        }
    }

    @Override
    public Void visitTypedef(Declaration.Typedef tree, Declaration parent) {
        Type type = tree.type();
        if (type instanceof Type.Declared) {
            Declaration.Scoped s = ((Type.Declared) type).tree();
            if (!s.name().equals(tree.name())) {
                switch (s.kind()) {
                    case STRUCT:
                    case UNION: {
                        if (s.name().isEmpty()) {
                            visitScoped(s, tree);
                        } else {
                            /*
                             * If typedef is seen after the struct/union definition, we can generate subclass
                             * right away. If not, we've to save it and revisit after all the declarations are
                             * seen. This is to support forward declaration of typedefs.
                             *
                             * typedef struct Foo Bar;
                             *
                             * struct Foo {
                             *     int x, y;
                             * };
                             */
                            if (structDefinitionSeen(s)) {
                                toplevelBuilder.emitTypedef(tree.name(), structDefinitionName(s));
                            } else {
                                /*
                                 * Definition of typedef'ed struct/union not seen yet. May be the definition comes later.
                                 * Save it to visit at the end of all declarations.
                                 */
                                unresolvedStructTypedefs.add(tree);
                            }
                        }
                    }
                    break;
                    default:
                        visitScoped(s, tree);
                }
            }
        } else if (type instanceof Type.Primitive) {
             toplevelBuilder.emitPrimitiveTypedef((Type.Primitive)type, tree.name());
        }
        return null;
    }

    @Override
    public Void visitVariable(Declaration.Variable tree, Declaration parent) {
        if (parent == null && variableSeen(tree)) {
            return null;
        }

        String fieldName = tree.name();
        String symbol = tree.name();
        assert !symbol.isEmpty();
        assert !fieldName.isEmpty();
        fieldName = Utils.javaSafeIdentifier(fieldName);

        Type type = tree.type();
        if (type instanceof Type.Declared && ((Type.Declared) type).tree().name().isEmpty()) {
            // anon type - let's generate something
            ((Type.Declared) type).tree().accept(this, tree);
        }
        MemoryLayout layout = tree.layout().orElse(Type.layoutFor(type).orElse(null));
        if (layout == null) {
            //no layout - abort
            return null;
        }
        Class<?> clazz = typeTranslator.getJavaType(type);
        if (tree.kind() == Declaration.Variable.Kind.BITFIELD ||
                (layout instanceof ValueLayout && layout.byteSize() > 8)) {
            //skip
            return null;
        }

        boolean isSegment = clazz == MemorySegment.class;
        MemoryLayout treeLayout = tree.layout().orElseThrow();
        if (parent != null) { //struct field
            MemoryLayout parentLayout = parentLayout(parent);
            if (isSegment) {
                currentBuilder.addSegmentGetter(fieldName, tree.name(), treeLayout, parentLayout);
            } else {
                currentBuilder.addVarHandleGetter(fieldName, tree.name(), treeLayout, clazz, parentLayout);
                currentBuilder.addGetter(fieldName, tree.name(), treeLayout, clazz, parentLayout);
                currentBuilder.addSetter(fieldName, tree.name(), treeLayout, clazz, parentLayout);
            }
        } else {
            if (isSegment) {
                toplevelBuilder.addSegmentGetter(fieldName, tree.name(), treeLayout, null);
            } else {
                toplevelBuilder.addLayoutGetter(fieldName, layout);
                toplevelBuilder.addVarHandleGetter(fieldName, tree.name(), treeLayout, clazz,null);
                toplevelBuilder.addSegmentGetter(fieldName, tree.name(), treeLayout, null);
                toplevelBuilder.addGetter(fieldName, tree.name(), treeLayout, clazz, null);
                toplevelBuilder.addSetter(fieldName, tree.name(), treeLayout, clazz, null);
            }
        }

        return null;
    }

    private boolean isRecord(Declaration declaration) {
        if (declaration == null) {
            return false;
        } else if (!(declaration instanceof Declaration.Scoped)) {
            return false;
        } else {
            Declaration.Scoped scope = (Declaration.Scoped)declaration;
            return scope.kind() == Declaration.Scoped.Kind.CLASS ||
                    scope.kind() == Declaration.Scoped.Kind.STRUCT ||
                    scope.kind() == Declaration.Scoped.Kind.UNION;
        }
    }

    protected static MemoryLayout parentLayout(Declaration parent) {
        if (parent instanceof Declaration.Typedef) {
            Declaration.Typedef alias = (Declaration.Typedef) parent;
            return Type.layoutFor(alias.type()).orElseThrow();
        } else if (parent instanceof Declaration.Scoped) {
            return ((Declaration.Scoped) parent).layout().orElseThrow();
        } else {
            throw new IllegalArgumentException("Unexpected parent declaration");
        }
        // case like `typedef struct { ... } Foo`
    }
}

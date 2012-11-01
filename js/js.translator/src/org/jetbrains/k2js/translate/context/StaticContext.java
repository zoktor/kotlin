/*
 * Copyright 2010-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.k2js.translate.context;

import com.google.dart.compiler.backend.js.ast.*;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import gnu.trove.THashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.AnnotationDescriptor;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingContextUtils;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.k2js.config.EcmaVersion;
import org.jetbrains.k2js.config.LibrarySourcesConfig;
import org.jetbrains.k2js.translate.context.generator.Generator;
import org.jetbrains.k2js.translate.context.generator.Rule;
import org.jetbrains.k2js.translate.declaration.ClassDeclarationTranslator;
import org.jetbrains.k2js.translate.expression.LiteralFunctionTranslator;
import org.jetbrains.k2js.translate.intrinsic.Intrinsics;
import org.jetbrains.k2js.translate.utils.AnnotationsUtils;
import org.jetbrains.k2js.translate.utils.JsAstUtils;
import org.jetbrains.k2js.translate.utils.PredefinedAnnotation;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;

import static org.jetbrains.k2js.translate.utils.AnnotationsUtils.*;
import static org.jetbrains.k2js.translate.utils.BindingUtils.isObjectDeclaration;
import static org.jetbrains.k2js.translate.utils.JsDescriptorUtils.*;

/**
 * @author Pavel Talanov
 *         <p/>
 *         Aggregates all the static parts of the context.
 */
public final class StaticContext {

    public static StaticContext generateStaticContext(@NotNull BindingContext bindingContext, @NotNull EcmaVersion ecmaVersion) {
        JsProgram program = new JsProgram("main");
        Namer namer = Namer.newInstance(program.getRootScope());
        Intrinsics intrinsics = new Intrinsics();
        StandardClasses standardClasses = StandardClasses.bindImplementations(namer.getKotlinScope());
        return new StaticContext(program, bindingContext, namer, intrinsics, standardClasses, program.getRootScope(), ecmaVersion);
    }

    @NotNull
    private final JsProgram program;

    @NotNull
    private final BindingContext bindingContext;
    @NotNull
    private final Namer namer;

    @NotNull
    private final Intrinsics intrinsics;

    @NotNull
    private final StandardClasses standardClasses;

    @NotNull
    private final JsScope rootScope;

    @NotNull
    private final Generator<JsName> names = new NameGenerator();
    @NotNull
    private final Generator<JsNameRef> qualifiers = new QualifierGenerator();

    @NotNull
    private final EcmaVersion ecmaVersion;

    @NotNull
    private LiteralFunctionTranslator literalFunctionTranslator;
    @NotNull
    private ClassDeclarationTranslator classDeclarationTranslator;

    //TODO: too many parameters in constructor
    private StaticContext(@NotNull JsProgram program, @NotNull BindingContext bindingContext,
            @NotNull Namer namer, @NotNull Intrinsics intrinsics,
            @NotNull StandardClasses standardClasses, @NotNull JsScope rootScope, @NotNull EcmaVersion ecmaVersion) {
        this.program = program;
        this.bindingContext = bindingContext;
        this.namer = namer;
        this.intrinsics = intrinsics;
        this.rootScope = rootScope;
        this.standardClasses = standardClasses;
        this.ecmaVersion = ecmaVersion;
    }

    public void initTranslators(TranslationContext programContext) {
        literalFunctionTranslator = new LiteralFunctionTranslator(programContext);
        classDeclarationTranslator = new ClassDeclarationTranslator(programContext);
    }

    @NotNull
    public LiteralFunctionTranslator getLiteralFunctionTranslator() {
        return literalFunctionTranslator;
    }

    @NotNull
    public ClassDeclarationTranslator getClassDeclarationTranslator() {
        return classDeclarationTranslator;
    }

    public boolean isEcma5() {
        return ecmaVersion == EcmaVersion.v5;
    }

    @NotNull
    public JsProgram getProgram() {
        return program;
    }

    @NotNull
    public BindingContext getBindingContext() {
        return bindingContext;
    }

    @NotNull
    public Intrinsics getIntrinsics() {
        return intrinsics;
    }

    @NotNull
    public Namer getNamer() {
        return namer;
    }

    @NotNull
    public JsScope getRootScope() {
        return rootScope;
    }

    @NotNull
    public JsNameRef getQualifiedReference(@NotNull DeclarationDescriptor descriptor, @NotNull TranslationContext context) {
        ClassDescriptor classDescriptor;
        if (descriptor instanceof ConstructorDescriptor) {
            classDescriptor = ((ConstructorDescriptor) descriptor).getContainingDeclaration();
        }
        else if (descriptor instanceof ClassDescriptor) {
            classDescriptor = (ClassDescriptor) descriptor;
        }
        else {
            classDescriptor = null;
        }

        if (classDescriptor != null) {
            JsNameRef reference = classDeclarationTranslator.getQualifiedReference((classDescriptor));
            if (reference != null) {
                return reference;
            }
        }
        return new JsNameRef(getNameForDescriptor(descriptor, context), getQualifierForDescriptor(descriptor));
    }

    @NotNull
    public JsName getNameForDescriptor(@NotNull DeclarationDescriptor descriptor, @Nullable TranslationContext context) {
        JsName name = names.get(descriptor.getOriginal(), context);
        assert name != null : "Must have name for descriptor";
        return name;
    }

    private final Map<PropertyAccessorDescriptor, String> extensionPropertyNameMap = new THashMap<PropertyAccessorDescriptor, String>();

    @NotNull
    public JsNameRef getNameRefForDescriptor(@NotNull DeclarationDescriptor descriptor, @NotNull TranslationContext context) {
        // property cannot be overloaded, so, name collision is not possible, we don't need create extra JsName and keep generated ref
        if (descriptor instanceof PropertyAccessorDescriptor) {
            PropertyAccessorDescriptor accessorDescriptor = (PropertyAccessorDescriptor) descriptor;
            if (accessorDescriptor.getReceiverParameter().exists()) {
                String name = extensionPropertyNameMap.get(accessorDescriptor);
                if (name != null) {
                    return new JsNameRef(name);
                }
            }

            String propertyName = accessorDescriptor.getCorrespondingProperty().getName().getName();
            if (!isObjectDeclaration(bindingContext, accessorDescriptor.getCorrespondingProperty()) &&
                (accessorDescriptor.getReceiverParameter().exists() || !isEcma5())) {
                propertyName = Namer.getNameForAccessor(propertyName, descriptor instanceof PropertyGetterDescriptor);
            }

            if (accessorDescriptor.getReceiverParameter().exists()) {
                JetScope memberScope = getMemberScope(accessorDescriptor.getCorrespondingProperty());
                if (memberScope != null) {
                    Collection<VariableDescriptor> properties =
                            memberScope.getProperties(accessorDescriptor.getCorrespondingProperty().getName());
                    if (properties.size() > 1) {
                        int counter = -1;
                        String name = propertyName;
                        for (VariableDescriptor property : properties) {
                            if (!(property instanceof PropertyDescriptor) || !property.getReceiverParameter().exists()) {
                                continue;
                            }

                            PropertyDescriptor propertyDescriptor = (PropertyDescriptor) property;
                            PropertyAccessorDescriptor currentAccessor = accessorDescriptor instanceof PropertyGetterDescriptor
                                                                         ? propertyDescriptor.getGetter()
                                                                         : propertyDescriptor.getSetter();
                            String currentName = counter == -1 ? name : name + '_' + counter;
                            if (currentAccessor == accessorDescriptor) {
                                propertyName = currentName;
                            }
                            extensionPropertyNameMap.put(currentAccessor, currentName);
                            counter++;
                        }
                    }
                }
            }
            return new JsNameRef(propertyName);
        }
        return getNameForDescriptor(descriptor, context).makeRef();
    }

    private final class NameGenerator extends Generator<JsName> {
        private JsName declareName(String name, TranslationContext context) {
            JsScope scope = context.scope();
            // ecma 5 property name never declares as obfuscatable:
            // 1) property cannot be overloaded, so, name collision is not possible
            // 2) main reason: if property doesn't have any custom accessor, value holder will have the same name as accessor, so, the same name will be declared more than once
            return isEcma5() ? scope.declareName(name) : scope.declareFreshName(name);
        }

        public NameGenerator() {
            Rule<JsName> namesForStandardClasses = new Rule<JsName>() {
                @Override
                @Nullable
                public JsName apply(@NotNull DeclarationDescriptor data) {
                    if (!standardClasses.isStandardObject(data)) {
                        return null;
                    }
                    return standardClasses.getStandardObjectName(data);
                }
            };
            Rule<JsName> namespacesShouldBeDefinedInRootScope = new Rule<JsName>() {
                @Override
                @Nullable
                public JsName apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!(descriptor instanceof NamespaceDescriptor)) {
                        return null;
                    }

                    String name = Namer.generateNamespaceName(descriptor);
                    return getRootScope().declareName(name);
                }
            };
            Rule<JsName> memberDeclarationsInsideParentsScope = new Rule<JsName>() {
                @Override
                @Nullable
                public JsName apply(@NotNull DeclarationDescriptor descriptor, TranslationContext context) {
                    JsScope scope = context.scope();
                    JetScope memberScope = descriptor instanceof FunctionDescriptor ? getMemberScope(descriptor) : null;
                    if (memberScope == null) {
                        return scope.declareFreshName(descriptor.getName().getName());
                    }

                    Collection<FunctionDescriptor> functions = memberScope.getFunctions(descriptor.getName());
                    String name = descriptor.getName().getName();
                    if (functions.size() <= 1) {
                        return scope.declareName(name);
                    }

                    // see testOverloadedFun
                    FunctionDescriptor[] sorted = functions.toArray(new FunctionDescriptor[functions.size()]);
                    Arrays.sort(sorted, new Comparator<FunctionDescriptor>() {
                        @Override
                        public int compare(FunctionDescriptor a, FunctionDescriptor b) {
                            Integer result = Visibilities.compare(b.getVisibility(), a.getVisibility());
                            if (result == null) {
                                return 0;
                            }
                            else if (result == 0) {
                                // open fun > not open fun
                                int aWeight = a.getModality().isOverridable() ? 1 : 0;
                                int bWeight = b.getModality().isOverridable() ? 1 : 0;
                                return bWeight - aWeight;
                            }

                            return result;
                        }
                    });
                    JsName result = null;
                    int counter = -1;
                    for (FunctionDescriptor function : sorted) {
                        if (function != descriptor && values.containsKey(function)) {
                            // it is native fun
                            continue;
                        }

                        JsName funName = scope.declareName(counter == -1 ? name : name + '_' + counter);
                        if (function == descriptor) {
                            result = funName;
                        }
                        else {
                            values.put(function, funName);
                        }
                        counter++;
                    }
                    return result;
                }
            };
            Rule<JsName> constructorHasTheSameNameAsTheClass = new Rule<JsName>() {
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor, TranslationContext context) {
                    if (!(descriptor instanceof ConstructorDescriptor)) {
                        return null;
                    }
                    ClassDescriptor containingClass = getContainingClass(descriptor);
                    assert containingClass != null : "Can't have constructor without a class";
                    return getNameForDescriptor(containingClass, context);
                }
            };
            Rule<JsName> predefinedObjectsHasUnobfuscatableNames = new Rule<JsName>() {
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor, TranslationContext context) {
                    for (PredefinedAnnotation annotation : PredefinedAnnotation.values()) {
                        AnnotationDescriptor annotationDescriptor = getAnnotationOrInsideAnnotatedClass(descriptor, annotation.getFQName());
                        if (annotationDescriptor == null) {
                            continue;
                        }
                        String name = getAnnotationStringParameter(annotationDescriptor);
                        if (name == null) {
                            name = descriptor.getName().getName();
                        }
                        return context.scope().declareName(name);
                    }
                    return null;
                }
            };
            Rule<JsName> propertiesCorrespondToSpeciallyTreatedBackingFieldNames = new Rule<JsName>() {
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor, TranslationContext context) {
                    if (!(descriptor instanceof PropertyDescriptor)) {
                        return null;
                    }
                    return declareName(JsAstUtils.createNameForProperty((PropertyDescriptor) descriptor, context), context);
                }
            };
            Rule<JsName> overridingDescriptorsReferToOriginalName = new Rule<JsName>() {
                @Override
                public JsName apply(@NotNull DeclarationDescriptor descriptor, TranslationContext context) {
                    //TODO: refactor
                    if (!(descriptor instanceof FunctionDescriptor)) {
                        return null;
                    }
                    FunctionDescriptor overriddenDescriptor = getOverriddenDescriptor((FunctionDescriptor) descriptor);
                    if (overriddenDescriptor == null) {
                        return null;
                    }

                    JsScope scope = context.scope();
                    JsName result = getNameForDescriptor(overriddenDescriptor, context);
                    scope.declareName(result.getIdent());
                    return result;
                }
            };
            addRule(namesForStandardClasses);
            addRule(constructorHasTheSameNameAsTheClass);
            addRule(predefinedObjectsHasUnobfuscatableNames);
            addRule(propertiesCorrespondToSpeciallyTreatedBackingFieldNames);
            addRule(namespacesShouldBeDefinedInRootScope);
            addRule(overridingDescriptorsReferToOriginalName);
            addRule(memberDeclarationsInsideParentsScope);
        }
    }

    @Nullable
    private static JetScope getMemberScope(@NotNull DeclarationDescriptor descriptor) {
        JetScope memberScope;
        DeclarationDescriptor containing = descriptor.getContainingDeclaration();
        if (containing instanceof ClassDescriptor) {
            memberScope = ((ClassDescriptor) containing).getDefaultType().getMemberScope();
        }
        else if (containing instanceof NamespaceDescriptor) {
            memberScope = ((NamespaceDescriptor) containing).getMemberScope();
        }
        else {
            memberScope = null;
        }
        return memberScope;
    }

    @Nullable
    public JsNameRef getQualifierForDescriptor(@NotNull DeclarationDescriptor descriptor) {
        if (descriptor instanceof PropertyDescriptor ||
            (descriptor instanceof NamespaceDescriptor && DescriptorUtils.isRootNamespace((NamespaceDescriptor) descriptor)) ||
            AnnotationsUtils.isNativeObject(descriptor)) {
            return null;
        }
        return qualifiers.get(descriptor.getOriginal(), null);
    }

    private final class QualifierGenerator extends Generator<JsNameRef> {
        public QualifierGenerator() {
            Rule<JsNameRef> standardObjectsHaveKotlinQualifier = new Rule<JsNameRef>() {
                @Override
                public JsNameRef apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!standardClasses.isStandardObject(descriptor)) {
                        return null;
                    }
                    return namer.kotlinObject();
                }
            };
            //TODO: review and refactor
            Rule<JsNameRef> namespaceLevelDeclarationsHaveEnclosingNamespacesNamesAsQualifier = new Rule<JsNameRef>() {
                @Override
                public JsNameRef apply(@NotNull DeclarationDescriptor descriptor, TranslationContext context) {
                    DeclarationDescriptor containingDescriptor = getContainingDeclaration(descriptor);
                    if (!(containingDescriptor instanceof NamespaceDescriptor)) {
                        return null;
                    }

                    final JsNameRef result = new JsNameRef(getNameForDescriptor(containingDescriptor, context));
                    if (DescriptorUtils.isRootNamespace((NamespaceDescriptor) containingDescriptor)) {
                        return result;
                    }

                    JsNameRef qualifier = result;
                    while ((containingDescriptor = getContainingDeclaration(containingDescriptor)) instanceof NamespaceDescriptor &&
                           !DescriptorUtils.isRootNamespace((NamespaceDescriptor) containingDescriptor)) {
                        JsNameRef ref = getNameForDescriptor(containingDescriptor, context).makeRef();
                        qualifier.setQualifier(ref);
                        qualifier = ref;
                    }

                    PsiElement element = BindingContextUtils.descriptorToDeclaration(bindingContext, descriptor);
                    if (element == null && descriptor instanceof PropertyAccessorDescriptor) {
                        element = BindingContextUtils.descriptorToDeclaration(bindingContext, ((PropertyAccessorDescriptor) descriptor)
                                .getCorrespondingProperty());
                    }

                    if (element != null) {
                        PsiFile file = element.getContainingFile();
                        String moduleName = file.getUserData(LibrarySourcesConfig.EXTERNAL_MODULE_NAME);
                        if (LibrarySourcesConfig.UNKNOWN_EXTERNAL_MODULE_NAME.equals(moduleName)) {
                            return null;
                        }
                        else if (moduleName != null) {
                            qualifier.setQualifier(new JsArrayAccess(namer.kotlin("modules"), program.getStringLiteral(moduleName)));
                        }
                    }

                    if (qualifier.getQualifier() == null) {
                        qualifier.setQualifier(new JsNameRef(Namer.getRootNamespaceName()));
                    }

                    return result;
                }
            };
            Rule<JsNameRef> constructorHaveTheSameQualifierAsTheClass = new Rule<JsNameRef>() {
                @Override
                public JsNameRef apply(@NotNull DeclarationDescriptor descriptor) {
                    if (!(descriptor instanceof ConstructorDescriptor)) {
                        return null;
                    }
                    ClassDescriptor containingClass = getContainingClass(descriptor);
                    assert containingClass != null : "Can't have constructor without a class";
                    return getQualifierForDescriptor(containingClass);
                }
            };
            Rule<JsNameRef> libraryObjectsHaveKotlinQualifier = new Rule<JsNameRef>() {
                @Override
                public JsNameRef apply(@NotNull DeclarationDescriptor descriptor) {
                    if (isLibraryObject(descriptor)) {
                        return namer.kotlinObject();
                    }
                    return null;
                }
            };
            addRule(libraryObjectsHaveKotlinQualifier);
            addRule(constructorHaveTheSameQualifierAsTheClass);
            addRule(standardObjectsHaveKotlinQualifier);
            addRule(namespaceLevelDeclarationsHaveEnclosingNamespacesNamesAsQualifier);
        }
    }
}

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

import com.google.dart.compiler.backend.js.ast.JsArrayAccess;
import com.google.dart.compiler.backend.js.ast.JsName;
import com.google.dart.compiler.backend.js.ast.JsNameRef;
import com.google.dart.compiler.backend.js.ast.JsProgram;
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
import org.jetbrains.k2js.config.EcmaVersion;
import org.jetbrains.k2js.config.LibrarySourcesConfig;
import org.jetbrains.k2js.translate.declaration.ClassDeclarationTranslator;
import org.jetbrains.k2js.translate.expression.LiteralFunctionTranslator;
import org.jetbrains.k2js.translate.intrinsic.Intrinsics;
import org.jetbrains.k2js.translate.utils.AnnotationsUtils;
import org.jetbrains.k2js.translate.utils.JsAstUtils;
import org.jetbrains.k2js.translate.utils.PredefinedAnnotation;

import java.util.Map;

import static org.jetbrains.k2js.translate.utils.AnnotationsUtils.*;
import static org.jetbrains.k2js.translate.utils.BindingUtils.isObjectDeclaration;

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
        return new StaticContext(program, bindingContext, namer, intrinsics, standardClasses, ecmaVersion);
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
    private final EcmaVersion ecmaVersion;

    @NotNull
    private LiteralFunctionTranslator literalFunctionTranslator;
    @NotNull
    private ClassDeclarationTranslator classDeclarationTranslator;

    private final OverloadedMemberNameGenerator overloadedMemberNameGenerator = new OverloadedMemberNameGenerator();
    private final Map<DeclarationDescriptor, JsName> nameMap = new THashMap<DeclarationDescriptor, JsName>();
    private final Map<DeclarationDescriptor, JsNameRef> qualifierMap = new THashMap<DeclarationDescriptor, JsNameRef>();

    //TODO: too many parameters in constructor
    private StaticContext(
            @NotNull JsProgram program, @NotNull BindingContext bindingContext,
            @NotNull Namer namer, @NotNull Intrinsics intrinsics,
            @NotNull StandardClasses standardClasses, @NotNull EcmaVersion ecmaVersion
    ) {
        this.program = program;
        this.bindingContext = bindingContext;
        this.namer = namer;
        this.intrinsics = intrinsics;
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
        JsNameRef ref = getNameRefForDescriptor(descriptor, context);
        ref.setQualifier(getQualifierForDescriptor(descriptor));
        return ref;
    }

    @NotNull
    public JsName getNameForDescriptor(@NotNull DeclarationDescriptor descriptor, @Nullable TranslationContext context) {
        assert descriptor instanceof LocalVariableDescriptor || descriptor instanceof ValueParameterDescriptor;
        JsName name = nameMap.get(descriptor);
        if (name == null) {
            assert context != null;
            name = context.scope().declareFreshName(descriptor.getName().getName());
            nameMap.put(descriptor, name);
        }
        return name;
    }

    @NotNull
    public JsNameRef getNameRefForDescriptor(@NotNull DeclarationDescriptor descriptor, @Nullable TranslationContext context) {
        if (descriptor instanceof ConstructorDescriptor) {
            return getNameRefForDescriptor(((ConstructorDescriptor) descriptor).getContainingDeclaration(), context);
        }

        if (standardClasses.isStandardObject(descriptor)) {
            return standardClasses.getStandardObjectName(descriptor).makeRef();
        }

        for (PredefinedAnnotation annotation : PredefinedAnnotation.values()) {
            AnnotationDescriptor annotationDescriptor = getAnnotationOrInsideAnnotatedClass(descriptor, annotation.getFQName());
            if (annotationDescriptor == null) {
                continue;
            }
            String name = getAnnotationStringParameter(annotationDescriptor);
            if (name == null) {
                name = descriptor.getName().getName();
            }
            return new JsNameRef(name);
        }

        // property cannot be overloaded, so, name collision is not possible, we don't need create extra JsName and keep generated ref
        if (descriptor instanceof PropertyAccessorDescriptor) {
            PropertyAccessorDescriptor accessorDescriptor = (PropertyAccessorDescriptor) descriptor;
            if (accessorDescriptor.getReceiverParameter() != null) {
                return new JsNameRef(overloadedMemberNameGenerator.forExtensionProperty(accessorDescriptor));
            }

            String name = accessorDescriptor.getCorrespondingProperty().getName().getName();
            if (!isEcma5() && !isObjectDeclaration(bindingContext, accessorDescriptor.getCorrespondingProperty())) {
                name = Namer.getNameForAccessor(name, descriptor instanceof PropertyGetterDescriptor);
            }
            return new JsNameRef(name);
        }
        else if (descriptor instanceof SimpleFunctionDescriptor) {
            String name = overloadedMemberNameGenerator.forClassOrNamespaceFunction((SimpleFunctionDescriptor) descriptor);
            if (name != null) {
                return new JsNameRef(name);
            }
        }
        else if (descriptor instanceof PropertyDescriptor) {
            return new JsNameRef(JsAstUtils.createNameForProperty((PropertyDescriptor) descriptor, isEcma5()));
        }
        else if (descriptor instanceof ClassDescriptor) {
            return new JsNameRef(descriptor.getName().getName());
        }
        else if (descriptor instanceof NamespaceDescriptor) {
            return new JsNameRef(Namer.generateNamespaceName(descriptor));
        }

        assert context != null;
        return getNameForDescriptor(descriptor, context).makeRef();
    }

    @Nullable
    public JsNameRef getQualifierForDescriptor(@NotNull DeclarationDescriptor descriptor) {
        if (descriptor instanceof PropertyDescriptor ||
            (descriptor instanceof NamespaceDescriptor && DescriptorUtils.isRootNamespace((NamespaceDescriptor) descriptor)) ||
            AnnotationsUtils.isNativeObject(descriptor)) {
            return null;
        }
        else if (descriptor instanceof ConstructorDescriptor) {
            return getQualifierForDescriptor(((ConstructorDescriptor) descriptor).getContainingDeclaration());
        }

        if (isLibraryObject(descriptor) || standardClasses.isStandardObject(descriptor)) {
            return Namer.KOTLIN_OBJECT_NAME_REF;
        }

        JsNameRef qualifier = qualifierMap.get(descriptor);
        if (qualifier == null) {
            qualifier = resolveQualifier(descriptor);
            qualifierMap.put(descriptor, qualifier);
        }
        return qualifier;
    }

    @Nullable
    private JsNameRef resolveQualifier(DeclarationDescriptor requestor) {
        DeclarationDescriptor namespace = requestor.getContainingDeclaration();
        if (!(namespace instanceof NamespaceDescriptor)) {
            return null;
        }

        JsNameRef result = new JsNameRef(Namer.generateNamespaceName(namespace));
        if (DescriptorUtils.isRootNamespace((NamespaceDescriptor) namespace)) {
            return result;
        }

        JsNameRef qualifier = result;
        while ((namespace = namespace.getContainingDeclaration()) instanceof NamespaceDescriptor &&
               !DescriptorUtils.isRootNamespace((NamespaceDescriptor) namespace)) {
            JsNameRef ref = new JsNameRef(namespace.getName().getName());
            qualifier.setQualifier(ref);
            qualifier = ref;
        }

        PsiElement element = BindingContextUtils.descriptorToDeclaration(bindingContext, requestor);
        if (element == null && requestor instanceof PropertyAccessorDescriptor) {
            element = BindingContextUtils.descriptorToDeclaration(bindingContext,
                                                                  ((PropertyAccessorDescriptor) requestor).getCorrespondingProperty());
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
}

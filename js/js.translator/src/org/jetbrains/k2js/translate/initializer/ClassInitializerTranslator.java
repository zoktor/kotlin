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

package org.jetbrains.k2js.translate.initializer;

import com.google.dart.compiler.backend.js.ast.*;
import com.intellij.util.SmartList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.psi.JetClassOrObject;
import org.jetbrains.jet.lang.psi.JetDelegationSpecifier;
import org.jetbrains.jet.lang.psi.JetDelegatorToSuperCall;
import org.jetbrains.jet.lang.psi.JetParameter;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.k2js.translate.context.Namer;
import org.jetbrains.k2js.translate.context.TranslationContext;
import org.jetbrains.k2js.translate.general.AbstractTranslator;

import java.util.List;

import static org.jetbrains.k2js.translate.utils.BindingUtils.getDescriptorForElement;
import static org.jetbrains.k2js.translate.utils.BindingUtils.getPropertyDescriptorForConstructorParameter;
import static org.jetbrains.k2js.translate.utils.PsiUtils.getPrimaryConstructorParameters;
import static org.jetbrains.k2js.translate.utils.TranslationUtils.translateArgumentList;

/**
 * @author Pavel Talanov
 */
public final class ClassInitializerTranslator extends AbstractTranslator {
    @NotNull
    private final JetClassOrObject declaration;
    private final ClassDescriptorFromSource descriptor;
    @NotNull
    private final List<JsStatement> initializerStatements = new SmartList<JsStatement>();

    private final JsFunction initializerFunction;

    public static JsFunction translateInitializerFunction(
            @NotNull JetClassOrObject declaration,
            @NotNull ClassDescriptorFromSource descriptor,
            @NotNull TranslationContext classContext
    ) {
        JsFunction fun = new JsFunction(classContext.scope(), new JsBlock());
        TranslationContext context = classContext.newFunctionBody(fun, null, null);
        return new ClassInitializerTranslator(declaration, descriptor, fun, context).generateInitializeMethod();
    }

    private ClassInitializerTranslator(
            @NotNull JetClassOrObject declaration,
            @NotNull ClassDescriptorFromSource descriptor,
            @NotNull JsFunction initializerFunction,
            @NotNull TranslationContext context
    ) {
        super(context);
        this.initializerFunction = initializerFunction;
        this.declaration = declaration;
        this.descriptor = descriptor;
    }

    @NotNull
    public JsFunction generateInitializeMethod() {
        //NOTE: while we translate constructor parameters we also add property initializer statements
        // for properties declared as constructor parameters
        translatePrimaryConstructorParameters(initializerFunction.getParameters());
        mayBeAddCallToSuperMethod(initializerFunction);
        new InitializerVisitor(initializerStatements).traverseContainer(declaration, context());

        List<JsStatement> funStatements = initializerFunction.getBody().getStatements();
        for (JsStatement statement : initializerStatements) {
            if (statement instanceof JsBlock) {
                funStatements.addAll(((JsBlock) statement).getStatements());
            }
            else {
                funStatements.add(statement);
            }
        }

        return initializerFunction;
    }

    private void mayBeAddCallToSuperMethod(JsFunction initializer) {
        for (JetType type : descriptor.getTypeConstructor().getSupertypes()) {
            ClassDescriptor superClassDescriptor = DescriptorUtils.getClassDescriptorForType(type);
            if (superClassDescriptor.getKind() == ClassKind.CLASS) {
                JetDelegatorToSuperCall superCall = getSuperCall();
                if (superCall != null) {
                    addCallToSuperMethod(superCall, initializer);
                }
                return;
            }
        }
    }

    private void addCallToSuperMethod(@NotNull JetDelegatorToSuperCall superCall, JsFunction initializer) {
        JsInvocation call;
        if (context().isEcma5()) {
            JsName ref = context().scope().declareName(Namer.CALLEE_NAME);
            initializer.setName(ref);
            call = new JsInvocation(new JsNameRef("call", new JsNameRef("baseInitializer", ref.makeRef())));
            call.getArguments().add(JsLiteral.THIS);
        }
        else {
            JsName superMethodName = context().scope().declareName(Namer.superMethodName());
            call = new JsInvocation(new JsNameRef(superMethodName, JsLiteral.THIS));
        }
        translateArgumentList(context(), superCall.getValueArguments(), call.getArguments());
        initializerStatements.add(call.makeStmt());
    }

    @Nullable
    private JetDelegatorToSuperCall getSuperCall() {
        for (JetDelegationSpecifier specifier : declaration.getDelegationSpecifiers()) {
            if (specifier instanceof JetDelegatorToSuperCall) {
                return (JetDelegatorToSuperCall) specifier;
            }
        }
        return null;
    }

    private void translatePrimaryConstructorParameters(List<JsParameter> result) {
        List<JetParameter> parameterList = getPrimaryConstructorParameters(declaration);
        if (!parameterList.isEmpty()) {
            for (JetParameter jetParameter : parameterList) {
                result.add(translateParameter(jetParameter));
            }
        }
    }

    @NotNull
    private JsParameter translateParameter(@NotNull JetParameter jetParameter) {
        DeclarationDescriptor parameterDescriptor =
                getDescriptorForElement(bindingContext(), jetParameter);
        JsName parameterName = context().getNameForDescriptor(parameterDescriptor);
        JsParameter jsParameter = new JsParameter(parameterName);
        mayBeAddInitializerStatementForProperty(jsParameter, jetParameter);
        return jsParameter;
    }

    private void mayBeAddInitializerStatementForProperty(@NotNull JsParameter jsParameter,
            @NotNull JetParameter jetParameter) {
        PropertyDescriptor propertyDescriptor =
                getPropertyDescriptorForConstructorParameter(bindingContext(), jetParameter);
        if (propertyDescriptor == null) {
            return;
        }
        JsNameRef initialValueForProperty = jsParameter.getName().makeRef();
        addInitializerOrPropertyDefinition(initialValueForProperty, propertyDescriptor);
    }

    private void addInitializerOrPropertyDefinition(@NotNull JsNameRef initialValue, @NotNull PropertyDescriptor propertyDescriptor) {
        initializerStatements.add(InitializerUtils.generateInitializerForProperty(context(), propertyDescriptor, initialValue));
    }
}

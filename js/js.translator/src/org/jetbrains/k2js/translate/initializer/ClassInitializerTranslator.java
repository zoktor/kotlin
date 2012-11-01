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
    private final JetClassOrObject classDeclaration;
    private final ClassDescriptorFromSource descriptor;
    @NotNull
    private final List<JsStatement> initializerStatements = new SmartList<JsStatement>();

    public ClassInitializerTranslator(
            @NotNull JetClassOrObject classDeclaration,
            @NotNull ClassDescriptorFromSource descriptor,
            @NotNull TranslationContext context
    ) {
        // Note: it's important we use scope for class descriptor because anonymous function used in property initializers
        // belong to the properties themselves
        super(context.newDeclaration(descriptor.getUnsubstitutedPrimaryConstructor()));
        this.classDeclaration = classDeclaration;
        this.descriptor = descriptor;
    }

    @NotNull
    public JsFunction generateInitializeMethod() {
        //TODO: it's inconsistent that we have scope for class and function for constructor, currently have problems implementing better way
        ConstructorDescriptor primaryConstructor = descriptor.getUnsubstitutedPrimaryConstructor();
        assert primaryConstructor != null;
        JsFunction result = context().getFunctionObject(primaryConstructor);
        //NOTE: while we translate constructor parameters we also add property initializer statements
        // for properties declared as constructor parameters
        translatePrimaryConstructorParameters(result.getParameters());
        mayBeAddCallToSuperMethod(result);
        new InitializerVisitor(initializerStatements).traverseContainer(classDeclaration, context());

        for (JsStatement statement : initializerStatements) {
            if (statement instanceof JsBlock) {
                result.getBody().getStatements().addAll(((JsBlock) statement).getStatements());
            }
            else {
                result.getBody().getStatements().add(statement);
            }
        }

        return result;
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
        for (JetDelegationSpecifier specifier : classDeclaration.getDelegationSpecifiers()) {
            if (specifier instanceof JetDelegatorToSuperCall) {
                return (JetDelegatorToSuperCall) specifier;
            }
        }
        return null;
    }

    private void translatePrimaryConstructorParameters(List<JsParameter> result) {
        List<JetParameter> parameterList = getPrimaryConstructorParameters(classDeclaration);
        if (parameterList.isEmpty()) {
            return;
        }

        for (JetParameter jetParameter : parameterList) {
            result.add(translateParameter(jetParameter));
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

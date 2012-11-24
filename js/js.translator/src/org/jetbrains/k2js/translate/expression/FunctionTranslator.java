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

package org.jetbrains.k2js.translate.expression;

import com.google.dart.compiler.backend.js.ast.*;
import com.intellij.util.SmartList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor;
import org.jetbrains.jet.lang.descriptors.ReceiverParameterDescriptor;
import org.jetbrains.jet.lang.descriptors.ValueParameterDescriptor;
import org.jetbrains.jet.lang.psi.JetDeclarationWithBody;
import org.jetbrains.jet.lang.psi.JetExpression;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;
import org.jetbrains.k2js.translate.context.AliasingContext;
import org.jetbrains.k2js.translate.context.Namer;
import org.jetbrains.k2js.translate.context.TranslationContext;
import org.jetbrains.k2js.translate.general.Translation;
import org.jetbrains.k2js.translate.utils.JsAstUtils;
import org.jetbrains.k2js.translate.utils.TranslationUtils;
import org.jetbrains.k2js.translate.utils.mutator.Mutator;

import java.util.Collections;
import java.util.List;

import static org.jetbrains.k2js.translate.utils.JsDescriptorUtils.getDeclarationDescriptorForReceiver;
import static org.jetbrains.k2js.translate.utils.mutator.LastExpressionMutator.mutateLastExpression;

/**
 * @author Pavel Talanov
 */
public final class FunctionTranslator {
    private FunctionTranslator() {
    }

    private static JsFunction translate(@NotNull JetDeclarationWithBody expression, @NotNull FunctionDescriptor descriptor,  @NotNull TranslationContext context) {
        JsFunction function = new JsFunction(context.scope(), new JsBlock());
        AliasingContext aliasingContext;
        ReceiverParameterDescriptor receiverParameter = descriptor.getReceiverParameter();
        JsName extensionFunctionReceiverName;
        if (receiverParameter == null) {
            aliasingContext = null;
            extensionFunctionReceiverName = null;
        }
        else {
            DeclarationDescriptor expectedReceiverDescriptor = getDeclarationDescriptorForReceiver(receiverParameter.getValue());
            extensionFunctionReceiverName = function.getScope().declareName(Namer.getReceiverParameterName());
            //noinspection ConstantConditions
            aliasingContext = context.aliasingContext().inner(expectedReceiverDescriptor, extensionFunctionReceiverName.makeRef());
        }

        TranslationContext bodyContext = context.newFunctionBody(function, aliasingContext, null);
        function.setParameters(translateParameters(descriptor, extensionFunctionReceiverName, bodyContext));
        addBodyResult(function, translateBody(descriptor, expression, bodyContext));
        return function;
    }

    @NotNull
    public static JsPropertyInitializer translate(
            boolean asEs5PropertyDescriptor,
            @NotNull JetDeclarationWithBody expression,
            @NotNull FunctionDescriptor descriptor,
            @NotNull TranslationContext context
    ) {
        JsFunction function = translate(expression, descriptor, context);
        if (asEs5PropertyDescriptor) {
            return TranslationUtils.translateFunctionAsEcma5PropertyDescriptor(function, descriptor, context);
        }
        else {
            return new JsPropertyInitializer(context.getNameRefForDescriptor(descriptor), function);
        }
    }

    @NotNull
    public static JsPropertyInitializer translateAsMethod(@NotNull JetDeclarationWithBody expression, @NotNull FunctionDescriptor descriptor,  @NotNull TranslationContext context) {
        return translate(false, expression, descriptor, context);
    }

    static void addBodyResult(JsFunction function, JsNode node) {
        if (node instanceof JsBlock) {
            function.getBody().getStatements().addAll(((JsBlock) node).getStatements());
        }
        else {
            function.getBody().getStatements().add(JsAstUtils.convertToStatement(node));
        }
    }

    @NotNull
    private static List<JsParameter> translateParameters(
            FunctionDescriptor descriptor,
            JsName extensionFunctionReceiverName,
            TranslationContext functionBodyContext
    ) {
        if (extensionFunctionReceiverName == null && descriptor.getValueParameters().isEmpty()) {
            return Collections.emptyList();
        }

        List<JsParameter> jsParameters = new SmartList<JsParameter>();
        addRegularParameters(descriptor, jsParameters, functionBodyContext, extensionFunctionReceiverName);
        return jsParameters;
    }

    public static void addRegularParameters(
            @NotNull FunctionDescriptor descriptor,
            @NotNull List<JsParameter> parameters,
            @NotNull TranslationContext funContext,
            @Nullable JsName receiverName
    ) {
        if (receiverName != null) {
            parameters.add(new JsParameter(receiverName));
        }
        for (ValueParameterDescriptor valueParameter : descriptor.getValueParameters()) {
            parameters.add(new JsParameter(funContext.getNameForDescriptor(valueParameter)));
        }
    }

    @NotNull
    public static JsNode translateBody(
            @NotNull FunctionDescriptor descriptor,
            @NotNull JetDeclarationWithBody declaration,
            @NotNull TranslationContext context
    ) {
        JetExpression jetBodyExpression = declaration.getBodyExpression();
        assert jetBodyExpression != null : "Cannot translate a body of an abstract function.";
        JsNode body = Translation.translateExpression(jetBodyExpression, context);
        JetType functionReturnType = descriptor.getReturnType();
        assert functionReturnType != null : "Function return typed type must be resolved.";
        if (declaration.hasBlockBody() || KotlinBuiltIns.getInstance().isUnit(functionReturnType)) {
            return body;
        }
        return mutateLastExpression(body, new Mutator() {
            @Override
            @NotNull
            public JsNode mutate(@NotNull JsNode node) {
                if (!(node instanceof JsExpression)) {
                    return node;
                }
                return new JsReturn((JsExpression) node);
            }
        });
    }
}
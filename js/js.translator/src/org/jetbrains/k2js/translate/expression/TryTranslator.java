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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.ClassDescriptor;
import org.jetbrains.jet.lang.descriptors.ClassifierDescriptor;
import org.jetbrains.jet.lang.descriptors.ConstructorDescriptor;
import org.jetbrains.jet.lang.descriptors.VariableDescriptor;
import org.jetbrains.jet.lang.psi.*;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingContextUtils;
import org.jetbrains.k2js.translate.context.TranslationContext;
import org.jetbrains.k2js.translate.utils.JsAstUtils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.k2js.translate.general.Translation.translateAsStatement;
import static org.jetbrains.k2js.translate.utils.JsAstUtils.convertToBlock;

/**
 * @author Pavel Talanov
 */
public final class TryTranslator {
    private TryTranslator() {
    }

    @NotNull
    public static JsTry translate(@NotNull JetTryExpression expression, @NotNull TranslationContext context) {
        return new JsTry(translateTryBlock(expression, context),
                         translateCatches(expression, context),
                         translateFinallyBlock(expression, context));
    }


    @Nullable
    private static JsBlock translateFinallyBlock(JetTryExpression expression, TranslationContext context) {
        JetFinallySection finallyBlock = expression.getFinallyBlock();
        return finallyBlock != null ? convertToBlock(translateAsStatement(finallyBlock.getFinalExpression(), context)) : null;
    }

    @NotNull
    private static JsBlock translateTryBlock(JetTryExpression expression, TranslationContext context) {
        return convertToBlock(translateAsStatement(expression.getTryBlock(), context));
    }

    @NotNull
    private static List<JsCatch> translateCatches(JetTryExpression expression, TranslationContext context) {
        JsCatch jsCatch = new JsCatch(context.scope(), "e");
        List<JetCatchClause> clauses = expression.getCatchClauses();
        if (clauses.size() == 1) {
            JetExpression catchBody = clauses.get(0).getCatchBody();
            assert catchBody != null;
            jsCatch.setBody(new JsBlock(translateAsStatement(catchBody, context)));
        }
        else if (clauses.size() > 1) {
            JsIf prevIf = null;
            for (JetCatchClause clause : clauses) {
                JetExpression catchBody = clause.getCatchBody();
                JetParameter catchParameter = clause.getCatchParameter();
                assert catchParameter != null && catchBody != null;

                VariableDescriptor descriptor =
                        BindingContextUtils.getNotNull(context.bindingContext(), BindingContext.VALUE_PARAMETER, catchParameter);
                ClassifierDescriptor classDescriptor = descriptor.getType().getConstructor().getDeclarationDescriptor();
                String errorName = null;
                if (classDescriptor instanceof ClassDescriptor) {
                    Collection<ConstructorDescriptor> constructors = ((ClassDescriptor) classDescriptor).getConstructors();
                    for (ConstructorDescriptor constructor : constructors) {
                        if (constructor.isPrimary() && context.intrinsics().getFunctionIntrinsics().getIntrinsic(constructor).exists()) {
                            errorName = classDescriptor.getName().getName();
                            break;
                        }
                    }
                }

                JsIf ifStatement = new JsIf();
                if (errorName != null) {
                    ifStatement.setIfExpression(JsAstUtils.equality(new JsNameRef("name", jsCatch.getParameter().getName().makeRef()),
                                                                    context.program().getStringLiteral(errorName)));
                }
                else {
                    // todo is check
                    throw new UnsupportedOperationException("catch clause translator");
                }

                ifStatement.setThenStatement(translateAsStatement(catchBody, context));
                if (prevIf == null) {
                    prevIf = ifStatement;
                    jsCatch.setBody(new JsBlock(prevIf));
                }
                else {
                    prevIf.setElseStatement(ifStatement);
                    prevIf = ifStatement;
                }
            }
        }
        return Collections.singletonList(jsCatch);
    }
}

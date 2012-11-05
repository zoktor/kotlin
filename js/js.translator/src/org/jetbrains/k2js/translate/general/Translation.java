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

package org.jetbrains.k2js.translate.general;

import com.google.dart.compiler.backend.js.ast.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor;
import org.jetbrains.jet.lang.psi.JetExpression;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.psi.JetNamedFunction;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.k2js.config.Config;
import org.jetbrains.k2js.facade.MainCallParameters;
import org.jetbrains.k2js.facade.exceptions.MainFunctionNotFoundException;
import org.jetbrains.k2js.facade.exceptions.TranslationException;
import org.jetbrains.k2js.facade.exceptions.TranslationInternalException;
import org.jetbrains.k2js.facade.exceptions.UnsupportedFeatureException;
import org.jetbrains.k2js.translate.context.Namer;
import org.jetbrains.k2js.translate.context.StaticContext;
import org.jetbrains.k2js.translate.context.TranslationContext;
import org.jetbrains.k2js.translate.declaration.NamespaceDeclarationTranslator;
import org.jetbrains.k2js.translate.expression.ExpressionVisitor;
import org.jetbrains.k2js.translate.expression.PatternTranslator;
import org.jetbrains.k2js.translate.reference.CallBuilder;
import org.jetbrains.k2js.translate.test.JSTestGenerator;
import org.jetbrains.k2js.translate.test.JSTester;
import org.jetbrains.k2js.translate.utils.dangerous.DangerousData;
import org.jetbrains.k2js.translate.utils.dangerous.DangerousTranslator;

import java.util.Collection;
import java.util.List;

import static org.jetbrains.jet.plugin.JetMainDetector.getMainFunction;
import static org.jetbrains.k2js.translate.utils.BindingUtils.getFunctionDescriptor;
import static org.jetbrains.k2js.translate.utils.JsAstUtils.*;
import static org.jetbrains.k2js.translate.utils.dangerous.DangerousData.collect;

/**
 * @author Pavel Talanov
 *         <p/>
 *         This class provides a interface which all translators use to interact with each other.
 *         Goal is to simplify interaction between translators.
 */
public final class Translation {

    private Translation() {
    }

    @NotNull
    public static PatternTranslator patternTranslator(@NotNull TranslationContext context) {
        return PatternTranslator.newInstance(context);
    }

    @NotNull
    public static JsNode translateExpression(@NotNull JetExpression expression, @NotNull TranslationContext context) {
        JsName aliasForExpression = context.aliasingContext().getAliasForExpression(expression);
        if (aliasForExpression != null) {
            return aliasForExpression.makeRef();
        }
        DangerousData data = collect(expression, context);
        if (data.shouldBeTranslated()) {
            return DangerousTranslator.translate(data, context);
        }
        return doTranslateExpression(expression, context);
    }

    //NOTE: use with care
    @NotNull
    public static JsNode doTranslateExpression(JetExpression expression, TranslationContext context) {
        return expression.accept(new ExpressionVisitor(), context);
    }

    @NotNull
    public static JsExpression translateAsExpression(@NotNull JetExpression expression,
            @NotNull TranslationContext context) {
        return convertToExpression(translateExpression(expression, context));
    }

    @NotNull
    public static JsStatement translateAsStatement(@NotNull JetExpression expression,
            @NotNull TranslationContext context) {
        return convertToStatement(translateExpression(expression, context));
    }

    @NotNull
    public static JsProgram generateAst(@NotNull BindingContext bindingContext,
            @NotNull Collection<JetFile> files, @NotNull MainCallParameters mainCallParameters,
            @NotNull Config config)
            throws TranslationException {
        try {
            return doGenerateAst(bindingContext, files, mainCallParameters, config);
        }
        catch (UnsupportedOperationException e) {
            throw new UnsupportedFeatureException("Unsupported feature used.", e);
        }
        catch (Throwable e) {
            throw new TranslationInternalException(e);
        }
    }

    @NotNull
    private static JsProgram doGenerateAst(
            @NotNull BindingContext bindingContext, @NotNull Collection<JetFile> files,
            @NotNull MainCallParameters mainCallParameters,
            @NotNull Config config
    ) throws MainFunctionNotFoundException {
        StaticContext staticContext = StaticContext.generateStaticContext(bindingContext, config.getTarget());
        JsFunction definitionFunction = generateDefinitionFunction(staticContext, files, config, mainCallParameters);
        JsProgram program = staticContext.getProgram();
        program.getGlobalBlock().getStatements().add(new JsInvocation(new JsNameRef("defineModule", Namer.KOTLIN_OBJECT_NAME_REF),
                                                                      program.getStringLiteral(config.getModuleId()),
                                                                      definitionFunction).makeStmt());
        return program;
    }

    private static JsFunction generateDefinitionFunction(
            StaticContext staticContext,
            Collection<JetFile> files,
            Config config,
            MainCallParameters mainCallParameters
    ) throws MainFunctionNotFoundException {
        JsFunction definitionFunction = new JsFunction(staticContext.getProgram().getScope(), new JsBlock());
        List<JsStatement> statements = definitionFunction.getBody().getStatements();
        statements.add(staticContext.getProgram().getStringLiteral("use strict").makeStmt());

        TranslationContext context = TranslationContext.rootContext(staticContext, definitionFunction);
        staticContext.initTranslators(context);
        new NamespaceDeclarationTranslator(files, context).translate(statements);

        if (mainCallParameters.shouldBeGenerated()) {
            JsStatement statement = generateCallToMain(context, files, mainCallParameters.arguments());
            if (statement != null) {
                statements.add(statement);
            }
        }
        mayBeGenerateTests(files, config, definitionFunction.getBody(), context);
        statements.add(new JsReturn(new JsNameRef(Namer.getRootNamespaceName())));
        return definitionFunction;
    }

    private static void mayBeGenerateTests(@NotNull Collection<JetFile> files, @NotNull Config config,
            @NotNull JsBlock rootBlock, @NotNull TranslationContext context) {
        JSTester tester = config.getTester();
        if (tester != null) {
            tester.initialize(context, rootBlock);
            JSTestGenerator.generateTestCalls(context, files, tester);
            tester.deinitialize();
        }
    }

    //TODO: determine whether should throw exception
    @Nullable
    private static JsStatement generateCallToMain(@NotNull TranslationContext context, @NotNull Collection<JetFile> files,
            @NotNull List<String> arguments) throws MainFunctionNotFoundException {
        JetNamedFunction mainFunction = getMainFunction(files);
        if (mainFunction == null) {
            return null;
        }
        FunctionDescriptor functionDescriptor = getFunctionDescriptor(context.bindingContext(), mainFunction);
        return CallBuilder.build(context).args(new JsArrayLiteral(toStringLiteralList(arguments, context.program()))).
                descriptor(functionDescriptor).translate().makeStmt();
    }
}

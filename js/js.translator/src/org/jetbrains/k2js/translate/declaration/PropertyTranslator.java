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

package org.jetbrains.k2js.translate.declaration;

import com.google.dart.compiler.backend.js.ast.*;
import com.intellij.util.SmartList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.PropertyAccessorDescriptor;
import org.jetbrains.jet.lang.descriptors.PropertyDescriptor;
import org.jetbrains.jet.lang.psi.JetProperty;
import org.jetbrains.jet.lang.psi.JetPropertyAccessor;
import org.jetbrains.k2js.translate.context.TranslationContext;
import org.jetbrains.k2js.translate.expression.FunctionTranslator;
import org.jetbrains.k2js.translate.general.AbstractTranslator;
import org.jetbrains.k2js.translate.utils.JsDescriptorUtils;
import org.jetbrains.k2js.translate.utils.TranslationUtils;

import java.util.Collections;
import java.util.List;

import static org.jetbrains.k2js.translate.utils.TranslationUtils.assignmentToBackingField;
import static org.jetbrains.k2js.translate.utils.TranslationUtils.backingFieldReference;

/**
 * @author Pavel Talanov
 *         <p/>
 *         Translates single property /w accessors.
 */
public final class PropertyTranslator extends AbstractTranslator {
    @NotNull
    private final PropertyDescriptor descriptor;
    @Nullable
    private final JetProperty declaration;

    public static void translateAccessors(@NotNull PropertyDescriptor descriptor, @NotNull List<JsPropertyInitializer> result, @NotNull TranslationContext context) {
        translateAccessors(descriptor, null, result, context);
    }

    public static void translateAccessors(@NotNull PropertyDescriptor descriptor,
            @Nullable JetProperty declaration,
            @NotNull List<JsPropertyInitializer> result,
            @NotNull TranslationContext context) {
        if (context.isEcma5() && !JsDescriptorUtils.isAsPrivate(descriptor)) {
            return;
        }

        new PropertyTranslator(descriptor, declaration, context).translate(result);
    }

    private PropertyTranslator(@NotNull PropertyDescriptor descriptor, @Nullable JetProperty declaration, @NotNull TranslationContext context) {
        super(context);

        this.descriptor = descriptor;
        this.declaration = declaration;
    }

    private void translate(@NotNull List<JsPropertyInitializer> result) {
        List<JsPropertyInitializer> to;
        if (context().isEcma5() && !JsDescriptorUtils.isExtension(descriptor)) {
            to = new SmartList<JsPropertyInitializer>();
            result.add(new JsPropertyInitializer(context().nameToLiteral(descriptor), new JsObjectLiteral(to, true)));
        }
        else {
            to = result;
        }

        to.add(generateAccessor(true));
        if (descriptor.isVar()) {
            to.add(generateAccessor(false));
        }
    }

    private JsPropertyInitializer generateAccessor(boolean isGetter) {
        JetPropertyAccessor accessorDeclaration;
        PropertyAccessorDescriptor accessorDescriptor;
        if (isGetter) {
            accessorDeclaration = declaration == null ? null : declaration.getGetter();
            accessorDescriptor = descriptor.getGetter();
        }
        else {
            accessorDeclaration = declaration == null ? null : declaration.getSetter();
            accessorDescriptor = descriptor.getSetter();
        }
        assert accessorDescriptor != null;
        if (accessorDeclaration != null && accessorDeclaration.getBodyExpression() != null) {
            FunctionTranslator translator = new FunctionTranslator(accessorDeclaration, accessorDescriptor, context());
            return context().isEcma5() ? translator.translateAsEcma5PropertyDescriptor() : translator.translateAsMethod();
        }
        else {
            return generateDefaultAccessor(accessorDescriptor,
                                           isGetter ? generateDefaultGetterFunction() : generateDefaultSetterFunction());
        }
    }

    @NotNull
    private JsFunction generateDefaultGetterFunction() {
        return new JsFunction(context().scope(), new JsBlock(new JsReturn(backingFieldReference(context(), descriptor))));
    }

    @NotNull
    private JsFunction generateDefaultSetterFunction() {
        JsFunction fun = new JsFunction(context().scope(), new JsBlock());
        JsName defaultParameterName = fun.getScope().declareName("value");
        fun.setParameters(Collections.singletonList(new JsParameter(defaultParameterName)));
        fun.setBody(new JsBlock(assignmentToBackingField(context(), descriptor, defaultParameterName.makeRef()).makeStmt()));
        return fun;
    }

    @NotNull
    private JsPropertyInitializer generateDefaultAccessor(@NotNull PropertyAccessorDescriptor accessorDescriptor,
            @NotNull JsFunction function) {
        if (context().isEcma5()) {
            return TranslationUtils.translateFunctionAsEcma5PropertyDescriptor(function, accessorDescriptor, context());
        }
        else {
            return new JsPropertyInitializer(context().getNameRefForDescriptor(accessorDescriptor), function);
        }
    }
}

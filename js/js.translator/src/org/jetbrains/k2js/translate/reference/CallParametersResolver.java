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

package org.jetbrains.k2js.translate.reference;

import com.google.dart.compiler.backend.js.ast.JsExpression;
import com.google.dart.compiler.backend.js.ast.JsLiteral;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.CallableDescriptor;
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor;
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCall;
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCallWithTrace;
import org.jetbrains.jet.lang.resolve.calls.model.VariableAsFunctionResolvedCall;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ClassReceiver;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ExtensionReceiver;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ReceiverValue;
import org.jetbrains.jet.lang.resolve.scopes.receivers.ThisReceiver;
import org.jetbrains.k2js.translate.context.TranslationContext;

import static org.jetbrains.k2js.translate.utils.JsDescriptorUtils.getDeclarationDescriptorForReceiver;

/**
 * @author Pavel Talanov
 */
public final class CallParametersResolver implements CallParameters {
    private JsExpression functionReference;
    private final JsExpression thisObject;
    private final JsExpression receiver;

    public static CallParameters resolveCallParameters(@Nullable JsExpression qualifier,
            @Nullable JsExpression callee,
            @NotNull CallableDescriptor descriptor,
            @NotNull ResolvedCall<? extends CallableDescriptor> call,
            @NotNull TranslationContext context) {
        return new CallParametersResolver(qualifier, callee, descriptor, call, context);
    }

    @NotNull
    private final CallableDescriptor descriptor;
    @NotNull
    private final TranslationContext context;
    @NotNull
    private final ResolvedCall<? extends CallableDescriptor> resolvedCall;

    private CallParametersResolver(@Nullable JsExpression qualifier,
            @Nullable JsExpression callee,
            @NotNull CallableDescriptor descriptor,
            @NotNull ResolvedCall<? extends CallableDescriptor> call,
            @NotNull TranslationContext context) {
        this.descriptor = descriptor;
        this.context = context;
        this.resolvedCall = call;

        if (callee != null) {
            functionReference = callee;
        }

        boolean extensionCall = resolvedCall.getReceiverArgument().exists();
        if (qualifier != null && !extensionCall) {
            thisObject = qualifier;
        }
        else {
            thisObject = resolveThisObject();
        }

        if (extensionCall) {
            receiver = qualifier != null
                       ? qualifier
                       : context.getThisObject(((ThisReceiver) resolvedCall.getReceiverArgument()).getDeclarationDescriptor());
        }
        else {
            receiver = null;
        }
    }

    @Nullable
    @Override
    public JsExpression getReceiver() {
        return receiver;
    }

    @Override
    @Nullable
    public JsExpression getThisObject() {
        return thisObject;
    }

    @Override
    @NotNull
    public JsExpression getFunctionReference() {
        if (functionReference == null) {
            if (resolvedCall instanceof VariableAsFunctionResolvedCall) {
                ResolvedCallWithTrace<FunctionDescriptor> call = ((VariableAsFunctionResolvedCall) resolvedCall).getFunctionCall();
                functionReference = CallBuilder.build(context).resolvedCall(call).translate();
            }
            else {
                functionReference = ReferenceTranslator.translateAsLocalNameReference(descriptor, context);
            }
        }
        return functionReference;
    }

    @Nullable
    private JsExpression resolveThisObject() {
        ReceiverValue thisObject = resolvedCall.getThisObject();
        if (!thisObject.exists()) {
            return null;
        }

        if (thisObject instanceof ClassReceiver) {
            JsExpression ref = context.getAliasForDescriptor(((ClassReceiver) thisObject).getDeclarationDescriptor());
            return ref == null ? JsLiteral.THIS : ref;
        }
        else if (thisObject instanceof ExtensionReceiver) {
            return context.getAliasForDescriptor(getDeclarationDescriptorForReceiver(thisObject));
        }

        return resolvedCall.getReceiverArgument().exists() && resolvedCall.getExplicitReceiverKind().isThisObject() ? JsLiteral.THIS : null;
    }

    @Override
    @Nullable
    public JsExpression getThisOrReceiverOrNull() {
        if (thisObject == null) {
            return receiver;
        }
        assert receiver == null;
        return thisObject;
    }
}

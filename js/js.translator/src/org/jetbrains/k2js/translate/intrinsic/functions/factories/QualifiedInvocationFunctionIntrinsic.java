package org.jetbrains.k2js.translate.intrinsic.functions.factories;

import com.google.dart.compiler.backend.js.ast.JsExpression;
import com.google.dart.compiler.backend.js.ast.JsInvocation;
import com.google.dart.compiler.backend.js.ast.JsNameRef;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.k2js.translate.context.TranslationContext;
import org.jetbrains.k2js.translate.intrinsic.functions.basic.FunctionIntrinsic;
import org.jetbrains.k2js.translate.utils.TranslationUtils;

import java.util.List;

public final class QualifiedInvocationFunctionIntrinsic extends FunctionIntrinsic {
    private final JsNameRef nameRef;

    public QualifiedInvocationFunctionIntrinsic(@NotNull String functionName, @NotNull JsExpression qualifier) {
        // don't worry about source map — source expression must be set for JsInvocation
        nameRef = new JsNameRef(functionName, qualifier);
    }

    @NotNull
    @Override
    public JsExpression apply(
            @Nullable JsExpression receiver,
            @NotNull List<JsExpression> arguments,
            @NotNull TranslationContext context
    ) {
        return new JsInvocation(nameRef, receiver == null ? arguments : TranslationUtils.generateInvocationArguments(receiver, arguments));
    }
}

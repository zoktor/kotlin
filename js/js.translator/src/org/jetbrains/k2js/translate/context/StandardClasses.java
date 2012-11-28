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

import com.google.common.collect.Maps;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.name.FqNameUnsafe;
import org.jetbrains.jet.lang.resolve.name.Name;

import java.util.Map;

/**
 * @author Pavel Talanov
 *         <p/>
 *         Provides a mechanism to bind some of the kotlin/java declations with library implementations.
 *         Makes sense only for those declaration that cannot be annotated. (Use library annotation in this case)
 */
public final class StandardClasses {

    private final class Builder {

        @Nullable
        private /*var*/ FqNameUnsafe currentFQName = null;
        @Nullable
        private /*var*/ String currentObjectName = null;

        @NotNull
        public Builder forFQ(@NotNull String classFQName) {
            currentFQName = new FqNameUnsafe(classFQName);
            return this;
        }

        @NotNull
        public Builder kotlinClass(@NotNull String kotlinName) {
            kotlinTopLevelObject(kotlinName);
            constructor();
            return this;
        }

        private void kotlinTopLevelObject(@NotNull String kotlinName) {
            assert currentFQName != null;
            currentObjectName = kotlinName;
            declareKotlinObject(currentFQName, kotlinName);
        }

        @NotNull
        public Builder kotlinFunction(@NotNull String kotlinName) {
            kotlinTopLevelObject(kotlinName);
            return this;
        }

        @NotNull
        private Builder constructor() {
            assert currentFQName != null;
            assert currentObjectName != null;
            declareInner(currentFQName, "<init>", currentObjectName);
            return this;
        }

        @NotNull
        public Builder methods(@NotNull String... methodNames) {
            assert currentFQName != null;
            declareMethods(currentFQName, methodNames);
            return this;
        }

        @NotNull
        public Builder properties(@NotNull String... propertyNames) {
            assert currentFQName != null;
            declareReadonlyProperties(currentFQName, propertyNames);
            return this;
        }
    }

    @NotNull
    public static StandardClasses bindImplementations() {
        StandardClasses standardClasses = new StandardClasses();
        standardClasses.declare().forFQ("jet.Iterator").kotlinClass("Iterator");

        standardClasses.declare().forFQ("jet.IntRange").kotlinClass("NumberRange")
                .methods("iterator", "contains").properties("start", "size", "end", "reversed");

        standardClasses.declare().forFQ("java.util.Collections.<no name provided>.max").kotlinFunction("collectionsMax");
        return standardClasses;
    }

    @NotNull
    private final Map<FqNameUnsafe, String> standardObjects = Maps.newHashMap();

    private void declareKotlinObject(@NotNull FqNameUnsafe fullQualifiedName, @NotNull String kotlinLibName) {
        standardObjects.put(fullQualifiedName, kotlinLibName);
    }

    private void declareInner(@NotNull FqNameUnsafe fullQualifiedClassName,
                              @NotNull String shortMethodName,
                              @NotNull String javascriptName) {
        standardObjects.put(fullQualifiedClassName.child(Name.guess(shortMethodName)), javascriptName);
    }

    private void declareMethods(@NotNull FqNameUnsafe classFQName,
                                @NotNull String... methodNames) {
        for (String methodName : methodNames) {
            declareInner(classFQName, methodName, methodName);
        }
    }

    private void declareReadonlyProperties(@NotNull FqNameUnsafe classFQName,
                                           @NotNull String... propertyNames) {
        for (String propertyName : propertyNames) {
            declareInner(classFQName, propertyName, propertyName);
        }
    }

    public boolean isStandardObject(@NotNull DeclarationDescriptor descriptor) {
        return standardObjects.containsKey(DescriptorUtils.getFQName(descriptor));
    }

    @NotNull
    public String getStandardObjectName(@NotNull DeclarationDescriptor descriptor) {
        return standardObjects.get(DescriptorUtils.getFQName(descriptor));
    }

    @NotNull
    private Builder declare() {
        return new Builder();
    }
}

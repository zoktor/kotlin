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

package org.jetbrains.jet.lang.types;

import org.jetbrains.annotations.NotNull;

/**
 * @author abreslav
 */
public class TypeProjection {
    @NotNull
    private final Variance projection;

    @NotNull
    private final JetType type;

    public TypeProjection(@NotNull Variance projection, @NotNull JetType type) {
        this.projection = projection;
        this.type = type;
    }

    public TypeProjection(@NotNull JetType type) {
        this(Variance.INVARIANT, type);
    }

    @NotNull
    public Variance getProjectionKind() {
        return projection;
    }

    @NotNull
    public JetType getType() {
        return type;
    }

    @Override
    public String toString() {
        return projection == Variance.INVARIANT ? type.toString() : projection + " " + type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TypeProjection that = (TypeProjection) o;

        return that.projection == projection && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return 31 * projection.hashCode() + type.hashCode();
    }
}

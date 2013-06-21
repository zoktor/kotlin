/*
 * Copyright 2010-2013 JetBrains s.r.o.
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

package org.jetbrains.jet.plugin.project;

import com.intellij.openapi.util.Key;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.containers.SLRUCache;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.analyzer.AnalyzeExhaust;
import org.jetbrains.jet.lang.psi.JetExpression;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.lazy.ResolveSession;
import org.jetbrains.jet.lang.resolve.lazy.ResolveSessionUtils;

public final class WholeProjectAnalyzerFacade {

    private WholeProjectAnalyzerFacade() {
    }

    @NotNull
    public static AnalyzeExhaust analyzeProjectWithCacheOnAFile(@NotNull JetFile file) {
        return AnalyzerFacadeWithCache.analyzeFileWithCache(file);
    }

    @NotNull
    public static ResolveSession getLazyResolveSessionForFile(@NotNull JetFile file) {
        return CachedValuesManager.getManager(file.getProject()).getCachedValue(
                file.getProject(),
                LAZY_SESSION,
                new CachedValueProvider<SLRUCache<JetFile, ResolveSession>>() {
                    @Nullable
                    @Override
                    public Result<SLRUCache<JetFile, ResolveSession>> compute() {
                        SLRUCache<JetFile, ResolveSession> cache = new SLRUCache<JetFile, ResolveSession>(3, 8) {
                            @NotNull
                            @Override
                            public ResolveSession createValue(JetFile file) {
                                System.out.println("Recreate");
                                return AnalyzerFacadeWithCache.getLazyResolveSession(file);
                            }
                        };
                        return Result.create(cache, PsiModificationTracker.OUT_OF_CODE_BLOCK_MODIFICATION_COUNT);
                    }
                },
                false
        ).get(file);

    }

    private final static Key<CachedValue<SLRUCache<JetFile, ResolveSession>>> LAZY_SESSION = Key.create("LAZY_SESSION");

    public static BindingContext getContextForExpression(@NotNull JetExpression jetExpression) {
        ResolveSession resolveSession = getLazyResolveSessionForFile((JetFile) jetExpression.getContainingFile());
        return ResolveSessionUtils.resolveToElement(resolveSession, jetExpression);
    }
}

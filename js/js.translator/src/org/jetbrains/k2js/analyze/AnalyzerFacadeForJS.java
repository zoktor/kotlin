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

package org.jetbrains.k2js.analyze;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.analyzer.AnalyzeExhaust;
import org.jetbrains.jet.analyzer.AnalyzerFacadeForEverything;
import org.jetbrains.jet.di.InjectorForTopDownAnalyzerForJs;
import org.jetbrains.jet.lang.ModuleConfiguration;
import org.jetbrains.jet.lang.descriptors.ModuleDescriptor;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.resolve.*;
import org.jetbrains.jet.lang.resolve.lazy.FileBasedDeclarationProviderFactory;
import org.jetbrains.jet.lang.resolve.lazy.ResolveSession;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.k2js.config.Config;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Pavel Talanov
 */
public final class AnalyzerFacadeForJS {

    private AnalyzerFacadeForJS() {
    }

    @NotNull
    public static BindingContext analyzeFilesAndCheckErrors(@NotNull List<JetFile> files,
            @NotNull Config config) {
        BindingContext bindingContext = analyzeFiles(files, config);
        checkForErrors(Config.withJsLibAdded(files, config), bindingContext);
        return bindingContext;
    }

    //NOTE: web demo related method
    @SuppressWarnings("UnusedDeclaration")
    @NotNull
    public static BindingContext analyzeFiles(@NotNull Collection<JetFile> files, @NotNull Config config) {
        return analyzeFiles(files, Predicates.<PsiFile>alwaysTrue(), config).getBindingContext();
    }

    @NotNull
    public static AnalyzeExhaust analyzeFiles(
            @NotNull Collection<JetFile> files,
            @NotNull Predicate<PsiFile> filesToAnalyzeCompletely, @NotNull Config config) {
        return analyzeFiles(files, filesToAnalyzeCompletely, config, false);
    }

    @NotNull
    public static AnalyzeExhaust analyzeFiles(
            @NotNull Collection<JetFile> files,
            @NotNull Predicate<PsiFile> filesToAnalyzeCompletely,
            @NotNull Config config,
            boolean storeContextForBodiesResolve
    ) {
        Project project = config.getProject();
        ModuleDescriptor owner = new ModuleDescriptor(Name.special("<module>"));
        Predicate<PsiFile> completely = Predicates.and(notLibFiles(config.getLibFiles()), filesToAnalyzeCompletely);
        TopDownAnalysisParameters topDownAnalysisParameters =
                new TopDownAnalysisParameters(completely, false, false, Collections.<AnalyzerScriptParameter>emptyList());
        BindingContext libraryBindingContext = config.getLibraryBindingContext();
        BindingTrace trace = libraryBindingContext == null ?
                             new ObservableBindingTrace(new BindingTraceContext()) :
                             new DelegatingBindingTrace(libraryBindingContext, "trace for analyzing library in js");
        InjectorForTopDownAnalyzerForJs injector = new InjectorForTopDownAnalyzerForJs(project, topDownAnalysisParameters, trace, owner,
                                                                                       new JsConfiguration(project, libraryBindingContext));
        try {
            Collection<JetFile> allFiles = libraryBindingContext != null ? files : Config.withJsLibAdded(files, config);
            injector.getTopDownAnalyzer().analyzeFiles(allFiles, Collections.<AnalyzerScriptParameter>emptyList());
            BodiesResolveContext bodiesResolveContext = storeContextForBodiesResolve ?
                                                        new CachedBodiesResolveContext(injector.getTopDownAnalysisContext()) :
                                                        null;
            return AnalyzeExhaust.success(trace.getBindingContext(), bodiesResolveContext, injector.getModuleConfiguration());
        }
        finally {
            injector.destroy();
        }
    }

    @NotNull
    public static AnalyzeExhaust analyzeFiles(
            @NotNull Collection<JetFile> files,
            boolean analyzeCompletely,
            @NotNull Config config,
            BindingContext parentBindingContext,
            boolean storeContextForBodiesResolve
    ) {
        Project project = config.getProject();
        ModuleDescriptor owner = new ModuleDescriptor(Name.special("<module>"));
        TopDownAnalysisParameters topDownAnalysisParameters =
                new TopDownAnalysisParameters(analyzeCompletely ? Predicates.<PsiFile>alwaysTrue() : Predicates.<PsiFile>alwaysFalse(), false, false, Collections.<AnalyzerScriptParameter>emptyList());
        BindingTrace trace = parentBindingContext == null ?
                             new ObservableBindingTrace(new BindingTraceContext()) :
                             new DelegatingBindingTrace(parentBindingContext, "trace for analyzing library in js");
        InjectorForTopDownAnalyzerForJs injector = new InjectorForTopDownAnalyzerForJs(project, topDownAnalysisParameters, trace, owner,
                                                                                       new JsModuleConfiguration(project, parentBindingContext));
        try {
            injector.getTopDownAnalyzer().analyzeFiles(files, Collections.<AnalyzerScriptParameter>emptyList());
            BodiesResolveContext bodiesResolveContext = storeContextForBodiesResolve ?
                                                        new CachedBodiesResolveContext(injector.getTopDownAnalysisContext()) :
                                                        null;
            return AnalyzeExhaust.success(trace.getBindingContext(), bodiesResolveContext, injector.getModuleConfiguration());
        }
        finally {
            injector.destroy();
        }
    }

    @NotNull
    public static AnalyzeExhaust analyzeBodiesInFiles(
            @NotNull Predicate<PsiFile> filesToAnalyzeCompletely,
            @NotNull Config config,
            @NotNull BindingTrace traceContext,
            @NotNull BodiesResolveContext bodiesResolveContext,
            @NotNull ModuleConfiguration configuration) {
        Predicate<PsiFile> completely = Predicates.and(notLibFiles(config.getLibFiles()), filesToAnalyzeCompletely);

        return AnalyzerFacadeForEverything.analyzeBodiesInFilesWithJavaIntegration(
                config.getProject(), Collections.<AnalyzerScriptParameter>emptyList(), completely, traceContext, bodiesResolveContext,
                configuration);
    }

    public static void checkForErrors(@NotNull Collection<JetFile> allFiles, @NotNull BindingContext bindingContext) {
        AnalyzingUtils.throwExceptionOnErrors(bindingContext);
        for (JetFile file : allFiles) {
            AnalyzingUtils.checkForSyntacticErrors(file);
        }
    }

    @NotNull
    private static Predicate<PsiFile> notLibFiles(@NotNull final List<JetFile> jsLibFiles) {
        return new Predicate<PsiFile>() {
            @Override
            public boolean apply(@Nullable PsiFile file) {
                assert file instanceof JetFile;
                @SuppressWarnings("UnnecessaryLocalVariable") boolean notLibFile = !jsLibFiles.contains(file);
                return notLibFile;
            }
        };
    }

    @NotNull
    public static ResolveSession getLazyResolveSession(Collection<JetFile> files, final Config config) {
        FileBasedDeclarationProviderFactory declarationProviderFactory = new FileBasedDeclarationProviderFactory(
                Config.withJsLibAdded(files, config), Predicates.<FqName>alwaysFalse());
        ModuleDescriptor lazyModule = new ModuleDescriptor(Name.special("<lazy module>"));
        return new ResolveSession(config.getProject(), lazyModule, new JsConfiguration(config.getProject(), null), declarationProviderFactory);
    }
}

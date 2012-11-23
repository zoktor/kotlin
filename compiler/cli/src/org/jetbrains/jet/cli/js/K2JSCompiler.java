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

package org.jetbrains.jet.cli.js;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import jet.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.analyzer.AnalyzeExhaust;
import org.jetbrains.jet.cli.common.CLICompiler;
import org.jetbrains.jet.cli.common.ExitCode;
import org.jetbrains.jet.cli.common.messages.*;
import org.jetbrains.jet.cli.jvm.compiler.JetCoreEnvironment;
import org.jetbrains.jet.config.CommonConfigurationKeys;
import org.jetbrains.jet.config.CompilerConfiguration;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.k2js.analyze.AnalyzerFacadeForJS;
import org.jetbrains.k2js.config.*;
import org.jetbrains.k2js.facade.K2JSTranslator;
import org.jetbrains.k2js.facade.MainCallParameters;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.jetbrains.jet.cli.common.messages.CompilerMessageLocation.NO_LOCATION;

/**
 * @author Pavel Talanov
 */
public class K2JSCompiler extends CLICompiler<K2JSCompilerArguments> {

    public static void main(String... args) {
        doMain(new K2JSCompiler(), args);
    }

    @NotNull
    @Override
    protected K2JSCompilerArguments createArguments() {
        return new K2JSCompilerArguments();
    }

    @NotNull
    @Override
    protected ExitCode doExecute(K2JSCompilerArguments arguments, PrintingMessageCollector messageCollector, Disposable rootDisposable) {
        if (arguments.sourceFiles == null) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "Specify sources location via -sourceFiles", NO_LOCATION);
            return ExitCode.INTERNAL_ERROR;
        }
        String outputFile = arguments.outputFile;
        if (outputFile == null) {
            messageCollector.report(CompilerMessageSeverity.ERROR, "Specify output file via -output", CompilerMessageLocation.NO_LOCATION);
            return ExitCode.INTERNAL_ERROR;
        }

        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.addAll(CommonConfigurationKeys.SOURCE_ROOTS_KEY, Arrays.asList(arguments.sourceFiles));
        JetCoreEnvironment environmentForJS = new JetCoreEnvironment(rootDisposable, configuration);

        Project project = environmentForJS.getProject();

        ClassPathLibrarySourcesLoader sourceLoader = new ClassPathLibrarySourcesLoader(project);
        environmentForJS.getSourceFiles().addAll(sourceLoader.findSourceFiles());

        if (arguments.isVerbose()) {
            reportCompiledSourcesList(messageCollector, environmentForJS);
        }

        final Config config = getConfig(arguments, project);
        final List<JetFile> sources = environmentForJS.getSourceFiles();
        AnalyzeExhaust libraryExhaust = analyze(messageCollector, config, config.getLibFiles(), null, false);
        if (libraryExhaust == null) {
            return ExitCode.COMPILATION_ERROR;
        }
        libraryExhaust.throwIfError();

        AnalyzeExhaust exhaust = analyze(messageCollector, config, sources, libraryExhaust.getBindingContext(), true);
        if (exhaust == null) {
            return ExitCode.COMPILATION_ERROR;
        }
        exhaust.throwIfError();

        MainCallParameters mainCallParameters = arguments.createMainCallParameters();
        try {
            K2JSTranslator.translateWithMainCallParametersAndSaveToFile(mainCallParameters, environmentForJS.getSourceFiles(), outputFile,
                                                                        config, exhaust);
        }
        catch (Throwable e) {
            messageCollector.report(CompilerMessageSeverity.EXCEPTION, MessageRenderer.PLAIN.renderException(e),
                                    CompilerMessageLocation.NO_LOCATION);
            return ExitCode.INTERNAL_ERROR;
        }
        return ExitCode.OK;
    }

    private static AnalyzeExhaust analyze(PrintingMessageCollector messageCollector, final Config config, final List<JetFile> sources, final BindingContext parentBindingContext, final boolean analyzeCompletely) {
        return new AnalyzerWithCompilerReport(messageCollector).analyzeAndReport(new Function0<AnalyzeExhaust>() {
            @Override
            public AnalyzeExhaust invoke() {
                return AnalyzerFacadeForJS.analyzeFiles(sources, analyzeCompletely, config, parentBindingContext, false);
            }
        }, sources);
    }

    private static void reportCompiledSourcesList(@NotNull PrintingMessageCollector messageCollector,
            @NotNull JetCoreEnvironment environmentForJS) {
        List<JetFile> files = environmentForJS.getSourceFiles();
        Iterable<String> fileNames = Iterables.transform(files, new Function<JetFile, String>() {
            @Override
            public String apply(@Nullable JetFile file) {
                assert file != null;
                VirtualFile virtualFile = file.getVirtualFile();
                if (virtualFile != null) {
                    return FileUtil.toSystemIndependentName(virtualFile.getPath());
                }
                return file.getName() + "(no virtual file)";
            }
        });
        messageCollector.report(CompilerMessageSeverity.LOGGING, "Compiling source files: " + Joiner.on(", ").join(fileNames),
                                CompilerMessageLocation.NO_LOCATION);
    }

    @NotNull
    private static Config getConfig(@NotNull K2JSCompilerArguments arguments, @NotNull Project project) {
        EcmaVersion ecmaVersion = EcmaVersion.fromString(arguments.target);
        String moduleId = FileUtil.getNameWithoutExtension(new File(arguments.outputFile));
        if (arguments.libraryFiles != null) {
            return new LibrarySourcesConfig(project, moduleId, Arrays.asList(arguments.libraryFiles), ecmaVersion, arguments.sourcemap);
        }
        else {
            // lets discover the JS library definitions on the classpath
            return new ClassPathLibraryDefintionsConfig(project, moduleId, ecmaVersion);
        }
    }
}

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

package org.jetbrains.jet.plugin.project;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.psi.JetFile;

import java.util.Collections;
import java.util.List;

/**
 * @author Pavel Talanov
 *         <p/>
 *         This class has utility functions to determine whether the project (or module) is js project.
 */
public final class JsModuleDetector {
    private JsModuleDetector() {
    }

    public static boolean isJsModule(@NotNull Module module) {
        return KotlinJsBuildConfigurationManager.getInstance(module).isJavaScriptModule();
    }

    public static boolean isJsModule(@NotNull JetFile file) {
        VirtualFile virtualFile = file.getOriginalFile().getVirtualFile();
        if (virtualFile != null) {
            Module module = ProjectRootManager.getInstance(file.getProject()).getFileIndex().getModuleForFile(virtualFile);
            if (module != null) {
                return isJsModule(module);
            }
        }

        return false;
    }

    @NotNull
    public static List<String> getLibPathAsList(@NotNull Project project) {
        Module module = getJSModule(project);
        if (module == null) {
            return Collections.emptyList();
        }
        else {
            return Collections.singletonList(getLibLocation(KotlinJsBuildConfigurationManager.getInstance(module), module));
        }
    }

    public static String getLibLocation(KotlinJsBuildConfigurationManager jsModuleComponent, Module module) {
        String libPath = jsModuleComponent.getPathToJavaScriptLibrary();
        if (libPath == null) {
            return null;
        }
        else {
            return ModuleRootManager.getInstance(module).getContentRoots()[0].getPath() + libPath;
        }
    }

    @Nullable
    private static Module getJSModule(@NotNull Project project) {
        Module[] modules = ModuleManager.getInstance(project).getModules();
        for (Module module : modules) {
            if (isJsModule(module)) {
                return module;
            }
        }
        return null;
    }
}

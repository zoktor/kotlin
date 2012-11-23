package org.jetbrains.k2js.analyze;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.DefaultModuleConfiguration;
import org.jetbrains.jet.lang.ModuleConfiguration;
import org.jetbrains.jet.lang.PlatformToKotlinClassMap;
import org.jetbrains.jet.lang.descriptors.NamespaceDescriptor;
import org.jetbrains.jet.lang.psi.JetImportDirective;
import org.jetbrains.jet.lang.psi.JetPsiFactory;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingTrace;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.ImportPath;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.lang.resolve.scopes.WritableScope;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;

import java.util.Collection;

public class JsModuleConfiguration implements ModuleConfiguration {
    private final Project project;
    private final BindingContext parentBindingContext;
    private final ModuleConfiguration delegateConfiguration;

    public JsModuleConfiguration(@NotNull Project project, BindingContext parentBindingContext) {
        this.project = project;
        this.parentBindingContext = parentBindingContext;
        this.delegateConfiguration = DefaultModuleConfiguration.createStandardConfiguration(project);
    }

    @Override
    public void addDefaultImports(@NotNull Collection<JetImportDirective> directives) {
        for (ImportPath path : JsConfiguration.DEFAULT_IMPORT_PATHS) {
            directives.add(JetPsiFactory.createImportDirective(project, path));
        }
        delegateConfiguration.addDefaultImports(directives);
    }

    @Override
    public void extendNamespaceScope(
            @NotNull BindingTrace trace, @NotNull NamespaceDescriptor namespaceDescriptor, @NotNull WritableScope namespaceMemberScope
    ) {
        if (parentBindingContext != null) {
            FqName qualifiedName = namespaceDescriptor.getQualifiedName();
            NamespaceDescriptor alreadyAnalyzedNamespace =
                    parentBindingContext.get(BindingContext.FQNAME_TO_NAMESPACE_DESCRIPTOR, qualifiedName);
            if (alreadyAnalyzedNamespace != null) {
                namespaceMemberScope.importScope(alreadyAnalyzedNamespace.getMemberScope());
            }
        }
        else if (DescriptorUtils.isRootNamespace(namespaceDescriptor)) {
            namespaceMemberScope.importScope(KotlinBuiltIns.getInstance().getBuiltInsScope());
        }
        else {
            delegateConfiguration.extendNamespaceScope(trace, namespaceDescriptor, namespaceMemberScope);
        }
    }

    @NotNull
    @Override
    public PlatformToKotlinClassMap getPlatformToKotlinClassMap() {
        return PlatformToKotlinClassMap.EMPTY;
    }
}

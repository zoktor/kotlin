package org.jetbrains.k2js.test.semantics;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.k2js.config.EcmaVersion;
import org.jetbrains.k2js.test.MultipleFilesTranslationTest;

import java.util.List;

public class ModuleLayoutTest extends MultipleFilesTranslationTest {
    public ModuleLayoutTest() {
        super("moduleLayout");
    }

    @NotNull
    @Override
    protected List<String> additionalJSFiles(@NotNull EcmaVersion ecmaVersion) {
        List<String> result = super.additionalJSFiles(ecmaVersion);
        result.add("js/kotlin-require.js");
        return result;
    }

    public void _testRequireJs() throws Exception {
        runMultiFileTest("requireJs", "a.foo", "box", true);
    }
}

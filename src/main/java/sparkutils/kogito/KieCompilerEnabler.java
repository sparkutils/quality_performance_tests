package org.kie.kogito;

import org.kie.dmn.core.compiler.*;
import org.kie.dmn.feel.parser.feel11.profiles.DoCompileFEELProfile;

/**
 * register with a jvm option
 */
public class KieCompilerEnabler implements DMNDecisionLogicCompilerFactory {
    public KieCompilerEnabler() {
    }

    public DMNDecisionLogicCompiler newDMNDecisionLogicCompiler(DMNCompilerImpl dmnCompiler, DMNCompilerConfigurationImpl dmnCompilerConfig) {
        dmnCompilerConfig.addFEELProfile(new DoCompileFEELProfile());
        return DMNEvaluatorCompiler.dmnEvaluatorCompilerFactory(dmnCompiler, dmnCompilerConfig);
    }
}
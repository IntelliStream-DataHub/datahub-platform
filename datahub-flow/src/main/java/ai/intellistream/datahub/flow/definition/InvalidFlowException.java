// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.flow.definition;

import java.util.List;

/** A flow definition that could not be parsed or did not validate, with everything wrong with it. */
public class InvalidFlowException extends RuntimeException {

    private final List<ValidationProblem> problems;

    public InvalidFlowException(List<ValidationProblem> problems) {
        super(summary(problems));
        this.problems = List.copyOf(problems);
    }

    public List<ValidationProblem> problems() {
        return problems;
    }

    private static String summary(List<ValidationProblem> problems) {
        StringBuilder text = new StringBuilder("The flow definition is invalid:");
        for (ValidationProblem p : problems) {
            text.append("\n  ").append(p.pointer()).append(": ").append(p.message());
        }
        return text.toString();
    }
}

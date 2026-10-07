package ir.karname.ai.provider;

import ir.karname.ai.llm.Effort;

/** What a model is used for; each task can be routed to its own provider and model. */
public enum AiTask {

    /** Conversations with the assistant: reasoning over the user's data with tools. */
    CHAT(Effort.MEDIUM),
    /** Turning free text, bank SMS and descriptions into transactions or categories: short and frequent. */
    EXTRACT(Effort.LOW),
    /** The monthly narrative report: one careful analysis a month. */
    REPORT(Effort.HIGH);

    private final Effort defaultEffort;

    AiTask(Effort defaultEffort) {
        this.defaultEffort = defaultEffort;
    }

    public Effort defaultEffort() {
        return defaultEffort;
    }
}

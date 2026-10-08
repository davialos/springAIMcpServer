package com.springaimcpservercommon.celfaker.workflow;

/**
 * The k6 load shape of a workflow.
 *
 * @param profile    {@code smoke}, {@code load}, {@code stress}, {@code spike} or {@code custom}
 * @param vus        virtual users (peak for ramping profiles)
 * @param duration   k6 duration of the steady phase ({@code 30s}, {@code 5m})
 * @param negatives  maximum negative (validation) iterations to run; 0 disables the negative scenario
 */
public record Load(String profile, int vus, String duration, int negatives) {

    /** Defaults. */
    public Load {
        profile = profile == null ? "smoke" : profile;
        vus = vus <= 0 ? 5 : vus;
        duration = duration == null ? "30s" : duration;
        negatives = negatives < 0 ? 0 : negatives;
    }

    /**
     * Default load: smoke profile, up to 100 negative iterations.
     *
     * @return load
     */
    public static Load smoke() {
        return new Load("smoke", 1, "30s", 100);
    }
}

package com.springaimcpservercommon.loadtest.ci;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Generates a CI pipeline that runs a load-test suite on every pull request and nightly: it builds and starts the
 * application, runs the suite (thresholds fail the job), compares the run with the baseline kept from the main branch
 * (fails on a regression), comments the result on the pull request, appends it to the trend history and uploads the
 * reports. The pipeline is a starting point — the application start-up and the database it needs are the part every
 * project edits.
 */
public final class CiPipelines {

    private CiPipelines() {
    }

    /** The CI systems that can be generated. */
    public enum Provider {
        /** {@code .github/workflows/loadtest.yml}. */
        GITHUB(".github/workflows/loadtest.yml"),
        /** {@code .gitlab-ci.loadtest.yml}, to be {@code include}d from {@code .gitlab-ci.yml}. */
        GITLAB(".gitlab-ci.loadtest.yml"),
        /** {@code Jenkinsfile.loadtest}. */
        JENKINS("Jenkinsfile.loadtest");

        private final String file;

        Provider(String file) {
            this.file = file;
        }

        /**
         * Where the pipeline goes, relative to the project.
         *
         * @return the file
         */
        public String file() {
            return file;
        }

        /**
         * Parses a provider name.
         *
         * @param name {@code github}, {@code gitlab} or {@code jenkins}
         * @return the provider
         */
        public static Provider parse(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "github", "github-actions" -> GITHUB;
                case "gitlab", "gitlab-ci" -> GITLAB;
                case "jenkins" -> JENKINS;
                default -> throw new IllegalArgumentException("--provider must be github, gitlab or jenkins, got " + name);
            };
        }
    }

    /**
     * How the project is built.
     */
    public enum BuildTool {
        /** {@code pom.xml}: the load-test Maven plugin runs the suite. */
        MAVEN,
        /** {@code build.gradle[.kts]}: {@code gradle/loadtest.gradle} runs the suite. */
        GRADLE
    }

    /**
     * What to generate.
     *
     * @param provider        the CI system
     * @param tool            the project's build tool
     * @param suite           the suite directory relative to the project ({@code load-tests})
     * @param mode            the load mode of every pull request and push ({@code smoke})
     * @param nightlyMode     the heavier load mode of the nightly run ({@code mixed-load})
     * @param startCommand    starts the built application in the background
     * @param buildCommand    builds it
     * @param healthUrl       answers 200 when the application is up
     * @param baseUrl         what the suite load-tests
     * @param k6Version       the k6 version to install
     * @param generatorVersion the version of the load-test plugin / tool
     */
    public record Options(Provider provider, BuildTool tool, String suite, String mode, String nightlyMode,
                          String startCommand, String buildCommand, String healthUrl, String baseUrl, String k6Version,
                          String generatorVersion) {

        /**
         * Defaults for a project.
         *
         * @param provider the CI system
         * @param tool     the build tool
         * @param suite    suite directory
         * @param baseUrl  the application's address (with context path)
         * @return options
         */
        public static Options defaults(Provider provider, BuildTool tool, String suite, String baseUrl) {
            boolean maven = tool == BuildTool.MAVEN;
            String clean = baseUrl.replaceAll("/+$", "");
            return new Options(provider, tool, suite, "smoke", "mixed-load",
                    maven ? "java -jar target/*.jar" : "java -jar build/libs/*.jar",
                    maven ? "mvn -B -DskipTests package" : "./gradlew bootJar -x test", clean + "/actuator/health", clean,
                    "1.3.0", "0.1.0-SNAPSHOT");
        }
    }

    /**
     * The pipeline text.
     *
     * @param o what to generate
     * @return the file's content
     */
    public static String render(Options o) {
        String text = switch (o.provider()) {
            case GITHUB -> github();
            case GITLAB -> gitlab();
            case JENKINS -> jenkins();
        };
        String run = o.tool() == BuildTool.MAVEN
                ? "mvn -B -q com.springaimcpservercommon:spring-ai-mcp-server-common-loadtest-maven-plugin:@VERSION@:run"
                + " -Dloadtest.suite=@SUITE@ -Dloadtest.mode=$MODE -Dloadtest.baseUrl=$BASE_URL"
                : "./gradlew loadtestRun -Ploadtest.mode=$MODE -Ploadtest.baseUrl=$BASE_URL";
        String compare = o.tool() == BuildTool.MAVEN
                ? "mvn -B -q com.springaimcpservercommon:spring-ai-mcp-server-common-loadtest-maven-plugin:@VERSION@:compare"
                + " -Dloadtest.suite=@SUITE@ -Dloadtest.mode=$MODE -Dloadtest.baseline=@SUITE@/baseline/$MODE.json"
                + " -Dloadtest.ci=true -Dloadtest.history=@SUITE@/history/$MODE.jsonl $UPDATE_BASELINE"
                : "./gradlew loadtestCompare -Ploadtest.mode=$MODE -Ploadtest.baseline=@SUITE@/baseline/$MODE.json"
                + " -Ploadtest.ci -Ploadtest.history=@SUITE@/history/$MODE.jsonl $UPDATE_BASELINE";
        String updateBaseline = o.tool() == BuildTool.MAVEN ? "-Dloadtest.updateBaseline=true" : "-Ploadtest.updateBaseline";
        return text.replace("@RUN@", run).replace("@COMPARE@", compare).replace("@UPDATE_FLAG@", updateBaseline)
                .replace("@SUITE@", o.suite()).replace("@MODE@", o.mode()).replace("@NIGHTLY@", o.nightlyMode())
                .replace("@BUILD@", o.buildCommand()).replace("@START@", o.startCommand())
                .replace("@HEALTH@", o.healthUrl()).replace("@BASE_URL@", o.baseUrl()).replace("@K6@", o.k6Version())
                .replace("@VERSION@", o.generatorVersion());
    }

    /**
     * Writes the pipeline into a project.
     *
     * @param project the project directory
     * @param o       what to generate
     * @param force   overwrite an existing file
     * @return the file written, or {@code null} when it exists and {@code force} is not set
     * @throws IOException when it cannot be written
     */
    public static @Nullable Path write(Path project, Options o, boolean force) throws IOException {
        Path file = project.resolve(o.provider().file());
        if (Files.exists(file) && !force) {
            return null;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, render(o));
        return file;
    }

    private static String github() {
        return """
                # Load test on every pull request, push to main and nightly (generated by `loadtest init-ci`; yours to edit).
                #  - builds and starts the application, runs the k6 suite: failed thresholds fail the job
                #  - compares the run with the baseline kept from the main branch: a regression fails the job
                #  - comments the comparison + trend on the pull request (updated in place) and in the job summary
                #  - the baseline and the trend history live in the Actions cache; main refreshes them
                name: load-test

                on:
                  pull_request:
                  push:
                    branches: [main]
                  schedule:
                    - cron: '17 2 * * *'      # nightly: the heavier mode below
                  workflow_dispatch:
                    inputs:
                      mode:
                        description: Load mode
                        default: @MODE@

                permissions:
                  contents: read
                  pull-requests: write

                concurrency:
                  group: load-test-${{ github.ref }}
                  cancel-in-progress: true

                env:
                  MODE: ${{ github.event_name == 'schedule' && '@NIGHTLY@' || github.event.inputs.mode || '@MODE@' }}
                  BASE_URL: @BASE_URL@
                  # on main (and nightly) a passing run becomes the new baseline
                  UPDATE_BASELINE: ${{ github.ref == 'refs/heads/main' && '@UPDATE_FLAG@' || '' }}

                jobs:
                  loadtest:
                    runs-on: ubuntu-latest
                    timeout-minutes: 45
                    # services:                # databases and brokers the application needs, e.g.
                    #   postgres:
                    #     image: postgres:17
                    #     env: { POSTGRES_PASSWORD: postgres }
                    #     ports: ['5432:5432']
                    #     options: --health-cmd pg_isready --health-interval 5s --health-retries 10
                    steps:
                      - uses: actions/checkout@v4
                      - uses: actions/setup-java@v4
                        with:
                          distribution: temurin
                          java-version: '25'
                          cache: maven
                      - uses: grafana/setup-k6-action@v1
                        with:
                          k6-version: '@K6@'

                      - name: Restore baseline and trend
                        uses: actions/cache/restore@v4
                        with:
                          path: |
                            @SUITE@/baseline
                            @SUITE@/history
                          key: loadtest-${{ env.MODE }}-${{ github.sha }}
                          restore-keys: loadtest-${{ env.MODE }}-

                      - name: Build
                        run: @BUILD@

                      - name: Start the application
                        run: |
                          nohup @START@ > app.log 2>&1 &
                          curl -fsS --retry 60 --retry-delay 2 --retry-connrefused @HEALTH@ > /dev/null

                      - name: Load test
                        id: run
                        continue-on-error: true       # the comparison and the comment are published either way
                        run: @RUN@

                      - name: Compare with the baseline, update the trend
                        if: always()
                        run: @COMPARE@

                      - name: Comment on the pull request
                        if: always() && github.event_name == 'pull_request'
                        uses: actions/github-script@v7
                        with:
                          script: |
                            const fs = require('fs');
                            const file = '@SUITE@/reports/pr-comment.md';
                            if (!fs.existsSync(file)) return;
                            const body = fs.readFileSync(file, 'utf8');
                            const marker = body.split('\\n')[0];
                            const { owner, repo } = context.repo;
                            const issue_number = context.issue.number;
                            const comments = await github.paginate(github.rest.issues.listComments, { owner, repo, issue_number });
                            const previous = comments.find((c) => c.body && c.body.startsWith(marker));
                            if (previous) await github.rest.issues.updateComment({ owner, repo, comment_id: previous.id, body });
                            else await github.rest.issues.createComment({ owner, repo, issue_number, body });

                      - name: Keep the baseline and trend (main only)
                        if: always() && github.ref == 'refs/heads/main'
                        uses: actions/cache/save@v4
                        with:
                          path: |
                            @SUITE@/baseline
                            @SUITE@/history
                          key: loadtest-${{ env.MODE }}-${{ github.sha }}

                      - name: Upload reports
                        if: always()
                        uses: actions/upload-artifact@v4
                        with:
                          name: loadtest-${{ env.MODE }}
                          path: |
                            @SUITE@/reports
                            app.log

                      - name: Fail when the load test failed
                        if: steps.run.outcome == 'failure'
                        run: exit 1
                """;
    }

    private static String gitlab() {
        return """
                # Load test on merge requests, the default branch and a nightly schedule (generated by `loadtest init-ci`).
                # Add to .gitlab-ci.yml:   include: { local: .gitlab-ci.loadtest.yml }
                # Merge-request comment (optional): set the masked variable LOADTEST_GITLAB_TOKEN (a project access
                # token with api scope). The baseline and the trend live in the job cache, refreshed on the default branch.
                loadtest:
                  image: eclipse-temurin:25-jdk
                  stage: test
                  timeout: 45 minutes
                  rules:
                    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
                    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
                    - if: $CI_PIPELINE_SOURCE == "schedule"
                  variables:
                    MODE: @MODE@
                    BASE_URL: @BASE_URL@
                  # services:                # databases the application needs
                  #   - name: postgres:17
                  #     alias: db
                  cache:
                    key: loadtest-$CI_DEFAULT_BRANCH
                    policy: pull
                    paths:
                      - @SUITE@/baseline
                      - @SUITE@/history
                  before_script:
                    - apt-get update -qq && apt-get install -y -qq curl gnupg ca-certificates > /dev/null
                    - curl -fsSL https://dl.k6.io/key.gpg | gpg --dearmor -o /usr/share/keyrings/k6.gpg
                    - echo "deb [signed-by=/usr/share/keyrings/k6.gpg] https://dl.k6.io/deb stable main" > /etc/apt/sources.list.d/k6.list
                    - apt-get update -qq && apt-get install -y -qq k6 > /dev/null
                  script:
                    - if [ "$CI_PIPELINE_SOURCE" = "schedule" ]; then MODE=@NIGHTLY@; fi
                    - if [ "$CI_COMMIT_BRANCH" = "$CI_DEFAULT_BRANCH" ]; then UPDATE_BASELINE="@UPDATE_FLAG@"; fi
                    - @BUILD@
                    - nohup @START@ > app.log 2>&1 &
                    - curl -fsS --retry 60 --retry-delay 2 --retry-connrefused @HEALTH@ > /dev/null
                    - status=0
                    - @RUN@ || status=$?
                    - @COMPARE@ || status=$?
                    - |
                      if [ -n "$CI_MERGE_REQUEST_IID" ] && [ -n "$LOADTEST_GITLAB_TOKEN" ]; then
                        curl -fsS --request POST --header "PRIVATE-TOKEN: $LOADTEST_GITLAB_TOKEN" \\
                          --data-urlencode "body@@SUITE@/reports/pr-comment.md" \\
                          "$CI_API_V4_URL/projects/$CI_PROJECT_ID/merge_requests/$CI_MERGE_REQUEST_IID/notes" > /dev/null || true
                      fi
                    - exit $status
                  after_script:
                    - echo "reports are in the job artifacts"
                  artifacts:
                    when: always
                    expire_in: 30 days
                    paths:
                      - @SUITE@/reports
                      - @SUITE@/baseline
                      - @SUITE@/history
                      - app.log

                # The default branch saves the refreshed baseline and trend for the next runs.
                loadtest-cache:
                  stage: .post
                  needs: [loadtest]
                  rules:
                    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
                  cache:
                    key: loadtest-$CI_DEFAULT_BRANCH
                    policy: push
                    paths:
                      - @SUITE@/baseline
                      - @SUITE@/history
                  dependencies: [loadtest]
                  script:
                    - echo "baseline and trend cached"
                """;
    }

    private static String jenkins() {
        return """
                // Load test pipeline (generated by `loadtest init-ci`; yours to edit).
                // Needs: a JDK 25 tool ('jdk25'), k6 on the agent, the Copy Artifact plugin for the baseline.
                // Every run archives reports/pr-comment.md; post it to the pull request with your SCM plugin
                // (e.g. GitHub Branch Source `pullRequest.comment(readFile(...))`).
                pipeline {
                  agent any
                  options { timeout(time: 45, unit: 'MINUTES'); timestamps() }
                  tools { jdk 'jdk25' }
                  triggers { cron('H 2 * * *') }          // nightly: the heavier mode
                  environment {
                    BASE_URL = '@BASE_URL@'
                    MODE = "${env.BUILD_CAUSE_TIMERTRIGGER ? '@NIGHTLY@' : '@MODE@'}"
                    UPDATE_BASELINE = "${env.BRANCH_NAME == 'main' ? '@UPDATE_FLAG@' : ''}"
                  }
                  stages {
                    stage('Baseline') {
                      steps {
                        // the baseline and the trend come from the last successful build of the main branch
                        copyArtifacts(projectName: env.JOB_NAME.replaceAll('/[^/]+$', '/main'), selector: lastSuccessful(),
                                      filter: '@SUITE@/baseline/**,@SUITE@/history/**', optional: true)
                      }
                    }
                    stage('Build') { steps { sh '@BUILD@' } }
                    stage('Start the application') {
                      steps {
                        sh '''
                          nohup @START@ > app.log 2>&1 &
                          curl -fsS --retry 60 --retry-delay 2 --retry-connrefused @HEALTH@ > /dev/null
                        '''
                      }
                    }
                    stage('Load test') {
                      steps {
                        script {
                          // the comparison is published even when the run failed its thresholds
                          int run = sh(returnStatus: true, script: '@RUN@')
                          int compare = sh(returnStatus: true, script: '@COMPARE@')
                          if (run != 0 || compare != 0) { error("load test failed (run ${run}, comparison ${compare})") }
                        }
                      }
                    }
                  }
                  post {
                    always {
                      archiveArtifacts artifacts: '@SUITE@/reports/**, @SUITE@/baseline/**, @SUITE@/history/**, app.log', allowEmptyArchive: true
                      sh 'pkill -f "@START@" || true'
                    }
                  }
                }
                """;
    }
}

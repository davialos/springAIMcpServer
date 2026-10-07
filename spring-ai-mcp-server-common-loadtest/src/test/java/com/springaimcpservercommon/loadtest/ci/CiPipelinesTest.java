package com.springaimcpservercommon.loadtest.ci;

import com.springaimcpservercommon.loadtest.cli.LoadTestCli;
import com.springaimcpservercommon.loadtest.discovery.Documents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The generated pipelines are valid YAML, wired to the suite, and written where each CI system looks. */
class CiPipelinesTest {

    @TempDir
    Path dir;

    private static CiPipelines.Options options(CiPipelines.Provider provider, CiPipelines.BuildTool tool) {
        return CiPipelines.Options.defaults(provider, tool, "load-tests", "http://localhost:8080/shop");
    }

    @Test
    void theGithubWorkflowIsValidYamlWithTheGateTheCommentAndTheCache() {
        String text = CiPipelines.render(options(CiPipelines.Provider.GITHUB, CiPipelines.BuildTool.MAVEN));
        assertThat(text).doesNotContain("@SUITE@").doesNotContain("@RUN@").doesNotContain("@MODE@");
        JsonNode wf = Documents.parse(text);
        assertThat(wf.path("name").asString()).isEqualTo("load-test");
        assertThat(wf.path("on").propertyNames()).contains("pull_request", "push", "schedule", "workflow_dispatch");
        assertThat(wf.path("permissions").path("pull-requests").asString()).isEqualTo("write");
        JsonNode steps = wf.path("jobs").path("loadtest").path("steps");
        java.util.List<String> names = new java.util.ArrayList<>();
        steps.forEach(s -> names.add(s.path("name").asString(s.path("uses").asString())));
        assertThat(names).containsSubsequence("Restore baseline and trend", "Build", "Start the application", "Load test",
                "Compare with the baseline, update the trend", "Comment on the pull request",
                "Keep the baseline and trend (main only)", "Upload reports", "Fail when the load test failed");
        String run = text.lines().filter(l -> l.contains("loadtest-maven-plugin") && l.contains(":run")).findFirst().orElseThrow();
        assertThat(run).contains("-Dloadtest.suite=load-tests").contains("-Dloadtest.baseUrl=$BASE_URL");
        assertThat(text).contains("-Dloadtest.baseline=load-tests/baseline/$MODE.json").contains("-Dloadtest.ci=true")
                .contains("load-tests/history/$MODE.jsonl").contains("BASE_URL: http://localhost:8080/shop")
                .contains("curl -fsS --retry 60 --retry-delay 2 --retry-connrefused http://localhost:8080/shop/actuator/health")
                .contains("java -jar target/*.jar").contains("'mixed-load'").contains("github.ref == 'refs/heads/main'");
        assertThat(steps.get(2).path("with").path("k6-version").asString()).isEqualTo("1.3.0");
    }

    @Test
    void gradleProjectsUseTheGradleTasks() {
        String text = CiPipelines.render(options(CiPipelines.Provider.GITHUB, CiPipelines.BuildTool.GRADLE));
        Documents.parse(text);
        assertThat(text).contains("./gradlew loadtestRun -Ploadtest.mode=$MODE").contains("./gradlew loadtestCompare")
                .contains("-Ploadtest.ci").contains("./gradlew bootJar -x test").contains("java -jar build/libs/*.jar")
                .doesNotContain("maven-plugin");
    }

    @Test
    void theGitlabIncludeIsValidYamlAndCachesOnTheDefaultBranch() {
        String text = CiPipelines.render(options(CiPipelines.Provider.GITLAB, CiPipelines.BuildTool.MAVEN));
        JsonNode ci = Documents.parse(text);
        JsonNode job = ci.path("loadtest");
        assertThat(job.path("cache").path("policy").asString()).isEqualTo("pull");
        assertThat(ci.path("loadtest-cache").path("cache").path("policy").asString()).isEqualTo("push");
        assertThat(job.path("variables").path("BASE_URL").asString()).isEqualTo("http://localhost:8080/shop");
        java.util.List<String> script = new java.util.ArrayList<>();
        job.path("script").forEach(l -> script.add(l.asString()));
        assertThat(script).anyMatch(l -> l.contains("loadtest-maven-plugin") && l.contains(":compare") && l.endsWith("|| status=$?"))
                .anyMatch(l -> l.contains("merge_requests") && l.contains("body@load-tests/reports/pr-comment.md"))
                .last().isEqualTo("exit $status");
    }

    @Test
    void theJenkinsfileIsADeclarativePipelineWiredToTheSuite() {
        String text = CiPipelines.render(options(CiPipelines.Provider.JENKINS, CiPipelines.BuildTool.MAVEN));
        assertThat(text).startsWith("// Load test pipeline").contains("pipeline {").contains("copyArtifacts(")
                .contains("load-tests/baseline/**").contains("sh(returnStatus: true, script: 'mvn -B -q")
                .contains("archiveArtifacts artifacts: 'load-tests/reports/**").doesNotContain("@");
    }

    @Test
    void theCliWritesTheFileWhereTheCiSystemLooksAndDetectsTheBuildTool() throws IOException {
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { java }");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LoadTestCli cli = new LoadTestCli(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), new ByteArrayInputStream(new byte[0]));
        assertThat(cli.execute(new String[]{"init-ci", "--project", dir.toString(), "--provider", "github", "--mode", "load",
                "--start", "java -jar build/libs/app.jar"})).as(err.toString(StandardCharsets.UTF_8)).isZero();
        Path workflow = dir.resolve(".github/workflows/loadtest.yml");
        assertThat(workflow).exists().content().contains("./gradlew loadtestRun").contains("nohup java -jar build/libs/app.jar")
                .contains("default: load");
        out.reset();
        assertThat(cli.execute(new String[]{"init-ci", "--project", dir.toString(), "--provider", "github"})).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("exists (--force to overwrite)");
        assertThat(cli.execute(new String[]{"init-ci", "--project", dir.toString(), "--provider", "gitlab"})).isZero();
        assertThat(dir.resolve(".gitlab-ci.loadtest.yml")).exists();
        assertThat(cli.execute(new String[]{"init-ci", "--project", dir.toString(), "--provider", "teamcity"})).isEqualTo(2);
    }
}

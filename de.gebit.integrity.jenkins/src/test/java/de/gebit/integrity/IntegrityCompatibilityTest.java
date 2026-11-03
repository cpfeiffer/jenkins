package de.gebit.integrity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.htmlunit.Page;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class IntegrityCompatibilityTest {

    private static String report(int passed, int failed, int testExceptions, int callExceptions) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<integrity name=\"Compatibility\"><suite><result successCount=\"" + passed
                + "\" failureCount=\"" + failed + "\" testExceptionCount=\"" + testExceptions
                + "\" callExceptionCount=\"" + callExceptions + "\"/></suite></integrity>";
    }

    private FreeStyleProject project(JenkinsRule j, String contents, boolean failOnErrors) throws IOException {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws IOException, InterruptedException {
                build.getWorkspace().child("integrity.xml").write(contents, "UTF-8");
                return true;
            }
        });
        project.getPublishersList().add(new IntegrityTestResultRecorder("integrity.xml", false, failOnErrors));
        return project;
    }

    @Test
    void freestyleReportsSurviveReloadAndRemainBrowsable(JenkinsRule j) throws Exception {
        String xml = report(3, 1, 2, 1);
        FreeStyleProject project = project(j, xml, false);
        FreeStyleBuild build = j.assertBuildStatus(Result.UNSTABLE, project.scheduleBuild2(0));
        IntegrityTestResultAction action = build.getAction(IntegrityTestResultAction.class);
        assertCounts(action, 3, 1, 2, 1);
        assertTrue(Files.exists(build.getRootDir().toPath().resolve("integrityResultData.xml")));
        assertFalse(Files.readString(build.getRootDir().toPath().resolve("build.xml")).contains("<rawData>"));
        String projectName = project.getFullName();
        j.jenkins.reload();
        project = j.jenkins.getItemByFullName(projectName, FreeStyleProject.class);
        build = project.getBuildByNumber(1);
        action = build.getAction(IntegrityTestResultAction.class);
        assertCounts(action, 3, 1, 2, 1);
        IntegrityTestResult child = (IntegrityTestResult) action.getResult().getChildren().iterator().next();
        assertEquals(xml, child.getRawDataString());
        assertSame(action.getResult(), child.getParent());

        try (JenkinsRule.WebClient client = j.createWebClient()) {
            HtmlPage results = client.getPage(build, "integrityReport/");
            assertTrue(results.asNormalizedText().contains("Compatibility"));
            Page raw = client.getPage(j.getURL() + build.getUrl() + "integrityReport/" + child.getSafeName() + "/");
            assertEquals(200, raw.getWebResponse().getStatusCode());
            assertEquals(xml, raw.getWebResponse().getContentAsString().trim());
            HtmlPage jobPage = client.getPage(project);
            assertTrue(jobPage.asNormalizedText().contains("Latest Integrity Test Results"));
            Page graph = client.getPage(j.getURL() + project.getUrl() + "integrity/trend/countGraph/png");
            assertEquals("image/png", graph.getWebResponse().getContentType());
        }
    }

    @Test
    void recorderConfigurationRoundTrips(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        IntegrityTestResultRecorder recorder = new IntegrityTestResultRecorder("**/reports/*.html", true, true);
        project.getPublishersList().add(recorder);
        j.configRoundtrip(project);
        j.assertEqualDataBoundBeans(
                recorder, project.getPublishersList().get(IntegrityTestResultRecorder.class));
    }

    @Test
    void parsesReportsOnAnAgent(JenkinsRule j) throws Exception {
        FreeStyleProject project = project(j, report(4, 0, 0, 0), false);
        project.setAssignedNode(j.createOnlineSlave());
        FreeStyleBuild build = j.buildAndAssertSuccess(project);
        assertCounts(build.getAction(IntegrityTestResultAction.class), 4, 0, 0, 0);
    }

    @Test
    void errorsAndMissingReportsKeepTheirConfiguredBuildStatus(JenkinsRule j) throws Exception {
        FreeStyleProject failed = project(j, report(0, 1, 0, 0), true);
        j.assertBuildStatus(Result.FAILURE, failed.scheduleBuild2(0));
        FreeStyleProject malformed = project(j, "<?xml version=\"1.0\"?><broken>", false);
        FreeStyleBuild malformedBuild = j.assertBuildStatus(Result.UNSTABLE, malformed.scheduleBuild2(0));
        assertEquals(1, malformedBuild.getAction(IntegrityTestResultAction.class).getTestExceptionCount());
        FreeStyleProject missing = j.createFreeStyleProject();
        missing.getPublishersList().add(new IntegrityTestResultRecorder("missing.xml", false, false));
        j.assertBuildStatus(Result.FAILURE, missing.scheduleBuild2(0));
        missing.getPublishersList().replace(new IntegrityTestResultRecorder("missing.xml", true, false));
        j.buildAndAssertSuccess(missing);
    }

    @Test
    void pipelineStepPublishesHtmlReports(JenkinsRule j) throws Exception {
        String xml = report(2, 0, 0, 0);
        String html = "<!DOCTYPE html><html><body><h1>Integrity report</h1><xmldata id=\"results\">"
                + xml.substring(xml.indexOf("<integrity"))
                + "</xmldata></body></html>";
        WorkflowJob project = j.createProject(WorkflowJob.class);
        project.setDefinition(new CpsFlowDefinition(
                "node { writeFile file: 'integrity.html', text: '" + html + "'; "
                        + "integrity testResultFileNamePattern: 'integrity.html', "
                        + "ignoreNoResults: false, failOnTestErrors: false }",
                true));
        WorkflowRun build = j.buildAndAssertSuccess(project);
        IntegrityTestResultAction action = build.getAction(IntegrityTestResultAction.class);
        assertCounts(action, 2, 0, 0, 0);
        IntegrityTestResult child = (IntegrityTestResult) action.getResult().getChildren().iterator().next();
        assertEquals("text/html;charset=UTF-8", child.getContentType());
        assertArrayEquals(html.getBytes(StandardCharsets.UTF_8), child.getRawData());
        try (JenkinsRule.WebClient client = j.createWebClient()) {
            HtmlPage results = client.getPage(build, "integrityReport/");
            assertTrue(results.asNormalizedText().contains("Compatibility"));
            HtmlPage raw = assertInstanceOf(HtmlPage.class, client.getPage(
                    j.getURL() + build.getUrl() + "integrityReport/" + child.getSafeName() + "/"));
            assertTrue(raw.asNormalizedText().contains("Integrity report"));
        }
    }

    @Test
    void staleReportsAreRejected(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        FilePath workspace = j.jenkins.getWorkspaceFor(project);
        assertNotNull(workspace);
        workspace.mkdirs();
        FilePath reportFile = workspace.child("old.xml");
        reportFile.write(report(1, 0, 0, 0), "UTF-8");
        reportFile.touch(1);
        project.getPublishersList().add(new IntegrityTestResultRecorder("old.xml", false, false));
        FreeStyleBuild build = j.assertBuildStatus(Result.FAILURE, project.scheduleBuild2(0));
        j.assertLogContains("none of them are new", build);
    }

    private void assertCounts(IntegrityTestResultAction action, int passed, int failed, int tests, int calls) {
        assertNotNull(action);
        assertEquals(passed, action.getPassCount());
        assertEquals(failed, action.getFailCount());
        assertEquals(tests, action.getTestExceptionCount());
        assertEquals(calls, action.getCallExceptionCount());
        assertEquals(passed + failed + tests, action.getTotalCount());
    }
}

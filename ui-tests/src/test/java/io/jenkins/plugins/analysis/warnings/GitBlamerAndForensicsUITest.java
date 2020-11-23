package io.jenkins.plugins.analysis.warnings;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.jvnet.hudson.test.Issue;

import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.docker.fixtures.GitContainer;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.DockerTest;
import org.jenkinsci.test.acceptance.junit.WithCredentials;
import org.jenkinsci.test.acceptance.junit.WithDocker;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.git.GitRepo;
import org.jenkinsci.test.acceptance.plugins.git.GitScm;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.FreeStyleJob;
import org.jenkinsci.test.acceptance.po.WorkflowJob;

import static org.assertj.core.api.Assertions.*;

/**
 * Verifies the blamer and forensics tabs and tables of the {@link IssuesRecorder}.
 *
 * @author Ullrich Hafner
 */
@WithDocker
@Category(DockerTest.class)
@WithPlugins({"git", "git-forensics"})
@WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {"gitplugin", "/org/jenkinsci/test/acceptance/docker/fixtures/GitContainer/unsafe"})
public class GitBlamerAndForensicsUITest extends AbstractJUnitTest {
    @Inject
    private DockerContainerHolder<GitContainer> gitServer;

    private static final String USERNAME = "gitplugin";
    private String repoUrl;
    private String host;
    private int port;

    private static final String DETAILS = "Details";
    private static final String FILE = "File";
    private static final String AGE = "Age";
    private static final String AUTHOR = "Author";
    private static final String EMAIL = "Email";
    private static final String COMMIT = "Commit";
    private static final String ADDED = "Added";

    /**
     * Setups a GitRepo with one initial commit.
     *
     * @return a GitRepo
     */
    private GitRepo setupInitialGitRepository() {
        GitRepo repo = new GitRepo();
        repo.setIdentity("Git SampleRepoRule", "gits@mplereporule");
        repo.changeAndCommitFile("file", "Initial Commit", "init");
        return repo;
    }

    /**
     * Commits multiple files to a given GitRepo.
     *
     * @param repo
     *         to add commits
     *
     * @return Map with filename and the commit hash
     */
    private Map<String, String> commitDifferentFilesToGitRepository(final GitRepo repo) {
        Map<String, String> commits = new HashMap<>();

        repo.setIdentity("Git SampleRepoRule", "gits@mplereporule");
        repo.changeAndCommitFile("Test.java", "public class Test {\n"
                + "    public Test() {\n"
                + "        System.out.println(\"Test\");"
                + "    }\n"
                + "}", "commit");
        commits.put("Test", repo.getLastSha1());

        repo.setIdentity("John Doe", "john@doe");
        repo.changeAndCommitFile("LoremIpsum.java", "public class LoremIpsum {\n"
                + "    public LoremIpsum() {\n"
                + "        Log.log(\"Lorem ipsum dolor sit amet\");"
                + "    }\n"
                + "}", "commit");
        commits.put("LoremIpsum", repo.getLastSha1());

        repo.setIdentity("Alice Miller", "alice@miller");
        repo.changeAndCommitFile("Bob.java", "public class Bob {\n"
                + "    public Bob() {\n"
                + "        Log.log(\"Bob: 'Where are you?'\");"
                + "    }\n"
                + "}", "commit");
        commits.put("Bob", repo.getLastSha1());

        return commits;
    }

    /** Initialize a Git repository that will be used by all tests. */
    @Before
    public void initGitRepository() {
        repoUrl = gitServer.get().getRepoUrl();
        host = gitServer.get().host();
        port = gitServer.get().port();
    }

    /** Verifies that freestyle jobs will correctly blame issues. */
    @Test
    public void shouldBlameOneIssueWithFreestyle() throws IOException {
        try (GitRepo repo = setupInitialGitRepository()) {
            repo.changeAndCommitFile("Test.java", "public class Test {}", "commit");
            String commitId = repo.getLastSha1();
            repo.changeAndCommitFile("warnings.txt", "[javac] Test.java:1: warning: Test Warning for Jenkins",
                    "commit");

            Build build = generateFreeStyleJob(repo);
            build.open();

            AnalysisSummary blame = new AnalysisSummary(build, "java");
            AnalysisResult resultPage = blame.openOverallResult();
            BlamesTable blamesTable = resultPage.openBlamesTable();
            BlamesTableRow row = blamesTable.getRowAs(0, BlamesTableRow.class);

            assertThat(blamesTable.getTableRows()).hasSize(1);
            assertColumnHeader(blamesTable);
            assertColumnsOfTest(row, commitId);
        }
    }

    /** Verifies that pipelines will correctly blame issues. */
    @Test
    public void shouldBlameElevenIssuesWithPipeline() throws IOException {
        try (GitRepo repo = new GitRepo()) {
            Map<String, String> commits = commitDifferentFilesToGitRepository(repo);
            repo.changeAndCommitFile("Jenkinsfile", "node {\n"
                    + "  stage ('Checkout') {\n"
                    + "    checkout scm\n"
                    + "  }\n"
                    + "  stage ('Build and Analysis') {"
                    + "    echo '[javac] Test.java:1: warning: Test Warning for Jenkins'\n"
                    + "    echo '[javac] Test.java:2: warning: Test Warning for Jenkins'\n"
                    + "    echo '[javac] Test.java:3: warning: Test Warning for Jenkins'\n"
                    + "    echo '[javac] LoremIpsum.java:1: warning: Another Warning for Jenkins'\n"
                    + "    echo '[javac] LoremIpsum.java:2: warning: Another Warning for Jenkins'\n"
                    + "    echo '[javac] LoremIpsum.java:3: warning: Another Warning for Jenkins'\n"
                    + "    echo '[javac] LoremIpsum.java:4: warning: Another Warning for Jenkins'\n"
                    + "    echo '[javac] Bob.java:1: warning: Bobs Warning for Jenkins'\n"
                    + "    echo '[javac] Bob.java:2: warning: Bobs Warning for Jenkins'\n"
                    + "    echo '[javac] Bob.java:3: warning: Bobs Warning for Jenkins'\n"
                    + "    discoverGitReferenceBuild()\n"
                    + "    mineRepository()\n"
                    + "    recordIssues tools: [java()]\n"
                    + "  }\n"
                    + "}", "commit");

            Build build = generateWorkflowJob(repo);
            build.open();

            AnalysisSummary blame = new AnalysisSummary(build, "java");
            AnalysisResult resultPage = blame.openOverallResult();
            BlamesTable blamesTable = resultPage.openBlamesTable();

            assertThat(blamesTable.getTableRows()).hasSize(10);
            assertColumnHeader(blamesTable);
            assertElevenIssues(commits, blamesTable);
        }
    }

    /**
     * Verifies that freestyle jobs will correctly blame issues. This test handles multiple issue pages.
     */
    @Test
    public void shouldBlameElevenIssuesWithFreestyle() throws IOException {
        try (GitRepo repo = new GitRepo()) {
            Map<String, String> commits = commitDifferentFilesToGitRepository(repo);
            repo.changeAndCommitFile("warnings.txt", "[javac] Test.java:1: warning: Test Warning for Jenkins\n"
                            + "[javac] Test.java:2: warning: Test Warning for Jenkins\n"
                            + "[javac] Test.java:3: warning: Test Warning for Jenkins\n"
                            + "[javac] LoremIpsum.java:1: warning: Another Warning for Jenkins\n"
                            + "[javac] LoremIpsum.java:2: warning: Another Warning for Jenkins\n"
                            + "[javac] LoremIpsum.java:3: warning: Another Warning for Jenkins\n"
                            + "[javac] LoremIpsum.java:4: warning: Another Warning for Jenkins\n"
                            + "[javac] Bob.java:1: warning: Bobs Warning for Jenkins\n"
                            + "[javac] Bob.java:2: warning: Bobs Warning for Jenkins\n"
                            + "[javac] Bob.java:3: warning: Bobs Warning for Jenkins",
                    "commit");

            Build build = generateFreeStyleJob(repo);
            build.open();

            AnalysisSummary blame = new AnalysisSummary(build, "java");
            AnalysisResult resultPage = blame.openOverallResult();
            BlamesTable blamesTable = resultPage.openBlamesTable();

            assertThat(blamesTable.getTableRows()).hasSize(10);
            assertColumnHeader(blamesTable);
            assertElevenIssues(commits, blamesTable);
        }
    }

    /** Test if blaming works on a build out of tree. See JENKINS-57260. */
    @Test
    @Issue("JENKINS-57260")
    // TODO: forensics is not yet working, see JENKINS-64280 for details
    public void shouldBlameWithBuildOutOfTree() throws IOException {
        try (GitRepo repo = setupInitialGitRepository()) {
            repo.changeAndCommitFile("Test.h", "#ifdef \"", "commit");

            String firstCommit = repo.getLastSha1();

            repo.changeAndCommitFile("Jenkinsfile", "pipeline {\n"
                    + "  agent any\n"
                    + "  options {\n"
                    + "    skipDefaultCheckout()\n"
                    + "  }\n"
                    + "  stages {\n"
                    + "    stage('Prepare') {\n"
                    + "      steps {\n"
                    + "        dir('source') {\n"
                    + "          checkout scm\n"
                    + "        }\n"
                    + "      }\n"
                    + "    }\n"
                    + "    stage('Doxygen') {\n"
                    + "      steps {\n"
                    + "        dir('build/doxygen') {\n"
                    + "          echo 'Test.h:1: Error: Unexpected character'\n"
                    + "        }\n"
                    + "        discoverGitReferenceBuild()\n"
                    + "        mineRepository()\n"
                    + "        recordIssues(aggregatingResults: true, "
                    + "             enabledForFailure: true, "
                    + "             tool: doxygen(name: 'Doxygen'), "
                    + "             sourceDirectory: 'source'"
                    + "        )\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}", "commit");

            Build build = generateWorkflowJob(repo);
            build.open();

            AnalysisSummary blame = new AnalysisSummary(build, "doxygen");
            AnalysisResult resultPage = blame.openOverallResult();
            BlamesTable blamesTable = resultPage.openBlamesTable();

            assertColumnHeader(blamesTable);
            assertThat(blamesTable.getTableRows()).hasSize(1);
            BlamesTableRow row = blamesTable.getRowAs(0, BlamesTableRow.class);

            assertThat(row.getAuthor()).isEqualTo("Git SampleRepoRule");
            assertThat(row.getEmail()).isEqualTo("gits@mplereporule");
            assertThat(row.getFileName()).isEqualTo("Test.h");
            assertThat(row.getCommit()).isEqualTo(renderCommit(firstCommit));
        }
    }

    /** Verifies that freestyle jobs will correctly show Git forensics statistics. */
    @Test
    public void shouldShowGitForensicsOneIssue() throws IOException {
        try (GitRepo repo = setupInitialGitRepository()) {
            repo.changeAndCommitFile("Test.java", "public class Test {}", "commit");
            repo.changeAndCommitFile("warnings.txt", "[javac] Test.java:1: warning: Test Warning for Jenkins",
                    "commit");

            Build build = generateFreeStyleJob(repo);
            build.open();

            AnalysisSummary summary = new AnalysisSummary(build, "java");
            AnalysisResult result = summary.openOverallResult();
            ForensicsTable forensicsTable = result.openForensicsTable();
            ForensicsTableRow row = forensicsTable.getRowAs(0, ForensicsTableRow.class);
            assertThat(forensicsTable.getTableRows()).hasSize(1);

            verifyForensicsTableModel(forensicsTable);
            assertColumnsOfRow(row, "Test.java", 1, 1);
        }
    }

    /** Verifies that pipelines will correctly show Git forensics statistics. */
    @Test
    public void shouldShowGitForensicsMultipleIssuesWithPipeline() throws IOException {
        try (GitRepo repo = new GitRepo()) {
            commitDifferentFilesToGitRepository(repo);
            repo.changeAndCommitFile("Jenkinsfile", "node {\n"
                            + "  stage ('Checkout') {\n"
                            + "    checkout scm\n"
                            + "  }\n"
                            + "  stage ('Build and Analysis') {"
                            + "    echo '[javac] Test.java:1: warning: Test Warning for Jenkins'\n"
                            + "    echo '[javac] Test.java:2: warning: Test Warning for Jenkins'\n"
                            + "    echo '[javac] Test.java:3: warning: Test Warning for Jenkins'\n"
                            + "    echo '[javac] LoremIpsum.java:1: warning: Another Warning for Jenkins'\n"
                            + "    echo '[javac] LoremIpsum.java:2: warning: Another Warning for Jenkins'\n"
                            + "    echo '[javac] LoremIpsum.java:3: warning: Another Warning for Jenkins'\n"
                            + "    echo '[javac] LoremIpsum.java:4: warning: Another Warning for Jenkins'\n"
                            + "    echo '[javac] Bob.java:1: warning: Bobs Warning for Jenkins'\n"
                            + "    echo '[javac] Bob.java:2: warning: Bobs Warning for Jenkins'\n"
                            + "    echo '[javac] Bob.java:3: warning: Bobs Warning for Jenkins'\n"
                            + "    discoverGitReferenceBuild()\n"
                            + "    mineRepository()\n"
                            + "    recordIssues tools: [java()]\n"
                            + "  }\n"
                            + "}",
                    "commit"
            );
            Build build = generateWorkflowJob(repo);
            build.open();

            AnalysisSummary summary = new AnalysisSummary(build, "java");
            AnalysisResult result = summary.openOverallResult();
            ForensicsTable forensicsTable = result.openForensicsTable();
            assertThat(forensicsTable.getTableRows()).hasSize(10);

            verifyForensicsTableModel(forensicsTable);
            assertMultipleIssuesAndAuthors(forensicsTable, 1, 1);
        }
    }

    /**
     * Verifies that freestyle jobs will correctly show Git forensics statistics. This test handles multiple issue
     * pages.
     */
    @Test
    public void shouldShowGitForensicsMultipleIssuesWithFreestyle() throws IOException {
        try (GitRepo repo = new GitRepo()) {
            commitDifferentFilesToGitRepository(repo);
            repo.changeAndCommitFile("warnings.txt", "[javac] Test.java:1: warning: Test Warning for Jenkins\n"
                    + "[javac] Test.java:2: warning: Test Warning for Jenkins\n"
                    + "[javac] Test.java:3: warning: Test Warning for Jenkins\n"
                    + "[javac] LoremIpsum.java:1: warning: Another Warning for Jenkins\n"
                    + "[javac] LoremIpsum.java:2: warning: Another Warning for Jenkins\n"
                    + "[javac] LoremIpsum.java:3: warning: Another Warning for Jenkins\n"
                    + "[javac] LoremIpsum.java:4: warning: Another Warning for Jenkins\n"
                    + "[javac] Bob.java:1: warning: Bobs Warning for Jenkins\n"
                    + "[javac] Bob.java:2: warning: Bobs Warning for Jenkins\n"
                    + "[javac] Bob.java:3: warning: Bobs Warning for Jenkins", "commit");

            Build build = generateFreeStyleJob(repo);
            build.open();

            AnalysisSummary summary = new AnalysisSummary(build, "java");
            AnalysisResult result = summary.openOverallResult();

            ForensicsTable forensicsTable = result.openForensicsTable();
            assertThat(forensicsTable.getTableRows()).hasSize(10);

            verifyForensicsTableModel(forensicsTable);
            assertMultipleIssuesAndAuthors(forensicsTable, 1, 1);
        }
    }

    /**
     * Verifies that freestyle jobs will correctly show Git forensics statistics. This test handles multiple commits and
     * authors.
     */
    @Test
    public void shouldShowGitForensicsMultipleIssuesWithMultipleCommitsAndAuthors() throws IOException {
        try (GitRepo repo = new GitRepo()) {
            commitDifferentFilesToGitRepository(repo);
            repo.setIdentity("Alice Miller", "alice@miller");
            repo.changeAndCommitFile("LoremIpsum.java", "public class LoremIpsum {\n"
                    + "    public LoremIpsum() {\n"
                    + "        Log.log(\"Lorem ipsum dolor sit amet\");"
                    + "    }\n"
                    + "}", "commit");
            repo.changeAndCommitFile("warnings.txt", "[javac] Test.java:1: warning: Test Warning for Jenkins\n"
                            + "[javac] Test.java:2: warning: Test Warning for Jenkins\n"
                            + "[javac] Test.java:3: warning: Test Warning for Jenkins\n"
                            + "[javac] LoremIpsum.java:1: warning: Another Warning for Jenkins\n"
                            + "[javac] LoremIpsum.java:2: warning: Another Warning for Jenkins\n"
                            + "[javac] LoremIpsum.java:3: warning: Another Warning for Jenkins\n"
                            + "[javac] LoremIpsum.java:4: warning: Another Warning for Jenkins\n"
                            + "[javac] Bob.java:1: warning: Bobs Warning for Jenkins\n"
                            + "[javac] Bob.java:2: warning: Bobs Warning for Jenkins\n"
                            + "[javac] Bob.java:3: warning: Bobs Warning for Jenkins",
                    "commit");

            Build build = generateFreeStyleJob(repo);
            build.open();

            AnalysisSummary summary = new AnalysisSummary(build, "java");
            AnalysisResult result = summary.openOverallResult();

            ForensicsTable forensicsTable = result.openForensicsTable();
            assertThat(forensicsTable.getTableRows()).hasSize(10);

            verifyForensicsTableModel(forensicsTable);
            assertMultipleIssuesAndAuthors(forensicsTable, 2, 2);
        }
    }

    private void verifyForensicsTableModel(final ForensicsTable forensicsTable) {
        assertThat(forensicsTable.getHeaders()).containsExactly("Details", "File", "Age", "#Authors", "#Commits",
                "Last Commit", "Added", "#LoC", "Code Churn");
    }

    private void assertElevenIssues(final Map<String, String> commits, final BlamesTable table) {
        assertColumnsOfRowBob(table.getRowAs(0, BlamesTableRow.class), commits.get("Bob"));
        assertColumnsOfRowBob(table.getRowAs(1, BlamesTableRow.class), commits.get("Bob"));
        assertColumnsOfRowBob(table.getRowAs(2, BlamesTableRow.class), commits.get("Bob"));

        assertColumnsOfRowLoremIpsum(table.getRowAs(3, BlamesTableRow.class), commits.get("LoremIpsum"));
        assertColumnsOfRowLoremIpsum(table.getRowAs(4, BlamesTableRow.class), commits.get("LoremIpsum"));
        assertColumnsOfRowLoremIpsum(table.getRowAs(5, BlamesTableRow.class), commits.get("LoremIpsum"));
        assertColumnsOfRowLoremIpsum(table.getRowAs(6, BlamesTableRow.class), commits.get("LoremIpsum"));

        assertColumnsOfTest(table.getRowAs(7, BlamesTableRow.class), commits.get("Test"));
        assertColumnsOfTest(table.getRowAs(8, BlamesTableRow.class), commits.get("Test"));
        assertColumnsOfTest(table.getRowAs(9, BlamesTableRow.class), commits.get("Test"));
    }

    private void assertColumnsOfTest(final BlamesTableRow row, final String commit) {
        assertThat(row.getAuthor()).isEqualTo("Git SampleRepoRule");
        assertThat(row.getEmail()).isEqualTo("gits@mplereporule");
        assertThat(row.getFileName()).isEqualTo("Test.java");
        assertThat(row.getCommit()).isEqualTo(renderCommit(commit));
        assertThat(row.getAge()).isEqualTo(1);
    }

    private String renderCommit(final String commit) {
        return commit.substring(0, 7);
    }

    private void assertColumnsOfRowBob(final BlamesTableRow row, final String commit) {
        assertThat(row.getAuthor()).isEqualTo("Alice Miller");
        assertThat(row.getEmail()).isEqualTo("alice@miller");
        assertThat(row.getFileName()).isEqualTo("Bob.java");
        assertThat(row.getCommit()).isEqualTo(renderCommit(commit));
        assertThat(row.getAge()).isEqualTo(1);
    }

    private void assertColumnsOfRowLoremIpsum(final BlamesTableRow row, final String commit) {
        assertThat(row.getAuthor()).isEqualTo("John Doe");
        assertThat(row.getEmail()).isEqualTo("john@doe");
        assertThat(row.getFileName()).isEqualTo("LoremIpsum.java");
        assertThat(row.getCommit()).isEqualTo(renderCommit(commit));
        assertThat(row.getAge()).isEqualTo(1);
    }

    private void assertColumnHeader(final BlamesTable table) {
        assertThat(table.getHeaders()).containsExactly(DETAILS, FILE, AGE, AUTHOR, EMAIL, COMMIT, ADDED);
    }

    private void assertColumnsOfRow(final ForensicsTableRow row, final String filename, final int commits,
            final int authors) {
        assertThat(row.getFileName()).isEqualTo(filename);
        assertThat(row.getAge()).isEqualTo(1);
        assertThat(row.getAuthors()).isEqualTo(authors);
        assertThat(row.getCommits()).isEqualTo(commits);
        assertThat(row.getLastCommit()).isNotNull();
        assertThat(row.getAdded()).isNotNull();
    }

    private void assertMultipleIssuesAndAuthors(final ForensicsTable forensicsTable, final int commits,
            final int authors) {
        assertColumnsOfRow(forensicsTable.getRowAs(0, ForensicsTableRow.class), "Bob.java", 1, 1);
        assertColumnsOfRow(forensicsTable.getRowAs(1, ForensicsTableRow.class), "Bob.java", 1, 1);
        assertColumnsOfRow(forensicsTable.getRowAs(2, ForensicsTableRow.class), "Bob.java", 1, 1);
        assertColumnsOfRow(forensicsTable.getRowAs(3, ForensicsTableRow.class), "LoremIpsum.java", commits, authors);
        assertColumnsOfRow(forensicsTable.getRowAs(5, ForensicsTableRow.class), "LoremIpsum.java", commits, authors);
        assertColumnsOfRow(forensicsTable.getRowAs(6, ForensicsTableRow.class), "LoremIpsum.java", commits, authors);
        assertColumnsOfRow(forensicsTable.getRowAs(7, ForensicsTableRow.class), "Test.java", 1, 1);
        assertColumnsOfRow(forensicsTable.getRowAs(8, ForensicsTableRow.class), "Test.java", 1, 1);
        assertColumnsOfRow(forensicsTable.getRowAs(9, ForensicsTableRow.class), "Test.java", 1, 1);
    }

    private Build generateFreeStyleJob(final GitRepo repo) {
        FreeStyleJob freestyleJob = jenkins.jobs.create();
        freestyleJob.configure();
        repo.transferToDockerContainer(host, port);
        freestyleJob.useScm(GitScm.class)
                .url(repoUrl)
                .credentials(USERNAME);

        freestyleJob.addPublisher(ForensicsPublisher.class);
        addRecorder(freestyleJob);
        freestyleJob.save();

        return freestyleJob.startBuild().waitUntilFinished();
    }

    private Build generateWorkflowJob(final GitRepo repo) {
        WorkflowJob workflowJob = jenkins.jobs.create(WorkflowJob.class);
        workflowJob.configure();
        repo.transferToDockerContainer(host, port);
        workflowJob.setJenkinsFileRepository(repoUrl, USERNAME);
        workflowJob.save();
        return workflowJob.startBuild().waitUntilFinished();
    }

    private void addRecorder(final FreeStyleJob job) {
        job.addPublisher(IssuesRecorder.class, recorder -> {
            recorder.setTool("Java").setPattern("warnings.txt");
            recorder.setEnabledForFailure(true);
        });
    }
}

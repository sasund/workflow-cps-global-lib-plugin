/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.libs;

import hudson.model.Result;
import hudson.plugins.git.GitSCM;
import java.util.Arrays;
import java.util.Collections;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.GitSampleRepoRule;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.SnippetizerTester;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepConfigTester;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

public class LibraryStepTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo = new GitSampleRepoRule();
    @Rule public GitSampleRepoRule sampleRepo2 = new GitSampleRepoRule();

    @Test public void configRoundtrip() throws Exception {
        StepConfigTester stepTester = new StepConfigTester(r);
        SnippetizerTester snippetizerTester = new SnippetizerTester(r);
        LibraryStep s = new LibraryStep("foo");
        r.assertEqualDataBoundBeans(s, stepTester.configRoundTrip(s));
        snippetizerTester.assertRoundTrip(s, "library 'foo'");
        s.setRetriever(new SCMSourceRetriever(new GitSCMSource("id", "https://nowhere.net/", "", "*", "", true)));
        r.assertEqualDataBoundBeans(s, stepTester.configRoundTrip(s));
        // TODO should fix up common SCMSource implementations to use proper @DataBoundConstructor/@DataBoundSetter hygiene and add @Symbol
        snippetizerTester.assertRoundTrip(s, "library identifier: 'foo', retriever: [$class: 'SCMSourceRetriever', scm: [$class: 'GitSCMSource', credentialsId: '', excludes: '', id: 'id', ignoreOnPushNotifications: true, includes: '*', remote: 'https://nowhere.net/']]");
        s.setRetriever(new SCMRetriever(new GitSCM("https://phony.jenkins.io/bar.git")));
        r.assertEqualDataBoundBeans(s, stepTester.configRoundTrip(s));
        // TODO GitSCM form too ugly to bother testing for now
    }

    @Test public void vars() throws Exception {
        sampleRepo.init();
        sampleRepo.write("vars/x.groovy", "def call() {echo 'ran library'}");
        sampleRepo.git("add", "vars");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("library 'stuff@master'; x()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("ran library", b);
        LibrariesAction action = b.getAction(LibrariesAction.class);
        assertNotNull(action);
        assertEquals("[LibraryRecord{name=stuff, version=master, variables=[x], trusted=true}]", action.getLibraries().toString());
        p.setDefinition(new CpsFlowDefinition("library identifier: 'otherstuff@master', retriever: [$class: 'SCMSourceRetriever', scm: [$class: 'GitSCMSource', remote: '" + sampleRepo + "', credentialsId: '']]; x()", true));
        b = r.buildAndAssertSuccess(p);
        r.assertLogContains("ran library", b);
        action = b.getAction(LibrariesAction.class);
        assertNotNull(action);
        assertEquals("[LibraryRecord{name=otherstuff, version=master, variables=[x], trusted=false}]", action.getLibraries().toString());
    }

    @Test public void classes() throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/some/pkg/Lib.groovy", "package some.pkg; class Lib {static class Inner {static String stuff() {Constants.CONST}}}");
        sampleRepo.write("src/some/pkg/Constants.groovy", "package some.pkg; class Constants {static String CONST = 'constant'}");
        sampleRepo.write("src/some/pkg/App.groovy", "package some.pkg; class App implements Serializable {def run() {Lib.Inner.stuff()}}");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Collections.singletonList(new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("def lib = library 'stuff@master'; echo(/using ${lib.some.pkg.Lib.Inner.stuff()} vs. ${lib.some.pkg.App.new().run()}/)", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("using constant vs. constant", b);
    }

    @Test public void classesFromWrongPlace() throws Exception {
        sampleRepo.init();
        sampleRepo.write("src/some/pkg/Lib.groovy", "package some.pkg; class Lib {static void m() {}}");
        sampleRepo.git("add", "src");
        sampleRepo.git("commit", "--message=init");
        sampleRepo2.init();
        sampleRepo2.write("src/other/pkg/Lib.groovy", "package other.pkg; class Lib {static void m() {}}");
        sampleRepo2.git("add", "src");
        sampleRepo2.git("commit", "--message=init");
        GlobalLibraries.get().setLibraries(Arrays.asList(
            new LibraryConfiguration("stuff", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo.toString(), "", "*", "", true))),
            new LibraryConfiguration("stuph", new SCMSourceRetriever(new GitSCMSource(null, sampleRepo2.toString(), "", "*", "", true)))));
        WorkflowJob p = r.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("library('stuff@master').some.pkg.Lib.m()", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        p.setDefinition(new CpsFlowDefinition("library('stuph@master').other.pkg.Lib.m()", true));
        b = r.buildAndAssertSuccess(p);
        p.setDefinition(new CpsFlowDefinition("library('stuph@master'); library('stuff@master').other.pkg.Lib.m()", true));
        b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains(IllegalAccessException.class.getName(), b);
        p.setDefinition(new CpsFlowDefinition("library('stuff@master'); library('stuph@master').some.pkg.Lib.m()", true));
        b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains(IllegalAccessException.class.getName(), b);
        p.setDefinition(new CpsFlowDefinition("library('stuff@master').java.beans.Introspector.flushCaches()", true));
        b = r.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
        r.assertLogContains(IllegalAccessException.class.getName(), b);
    }

    // TODO call methods with one or two arguments or nulls
    // TODO classes from untrusted libs
    // TODO configRoundtrip test
    // TODO duplicated library (@Library + library; library + library) should merely load existing library
    // TODO no matching library
    // TODO replay
    // TODO restart test of classes and vars

}

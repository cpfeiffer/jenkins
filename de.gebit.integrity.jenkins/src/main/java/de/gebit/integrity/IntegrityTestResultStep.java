/*******************************************************************************
 * Copyright (c) 2017 Rene Schneider, GEBIT Solutions GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package de.gebit.integrity;

import java.io.Serializable;
import java.util.Set;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.common.collect.ImmutableSet;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Run;
import hudson.model.TaskListener;

/**
 * The step for pipeline builds.
 *
 * @author Rene Schneider - initial API and implementation
 *
 */
public class IntegrityTestResultStep extends Step implements Serializable {

	/**
	 * Serial version.
	 */
	private static final long serialVersionUID = 8357333095856302442L;

	/**
	 * The file name pattern string.
	 */
	private final String testResultFileNamePattern;

	/**
	 * Whether "no results" should be ignored.
	 */
	private final Boolean ignoreNoResults;

	/**
	 * Fail the build on test errors.
	 */
	private final Boolean failOnTestErrors;

	@DataBoundConstructor
	public IntegrityTestResultStep(String testResultFileNamePattern, Boolean ignoreNoResults,
			Boolean failOnTestErrors) {
		this.testResultFileNamePattern = testResultFileNamePattern;
		this.ignoreNoResults = ignoreNoResults;
		this.failOnTestErrors = failOnTestErrors;
	}

	public String getTestResultFileNamePattern() {
		return testResultFileNamePattern;
	}

	public Boolean getIgnoreNoResults() {
		return ignoreNoResults;
	}

	public Boolean getFailOnTestErrors() {
		return failOnTestErrors;
	}

	@Override
	public StepExecution start(StepContext context) throws Exception {
		return new IntegrityTestResultStepExecution(this, context);
	}

	@Extension
	public static class DescriptorImpl extends StepDescriptor {

		@Override
		public String getFunctionName() {
			return "integrity";
		}

		@NonNull
		@Override
		public String getDisplayName() {
			return "Archive Integrity Test Results";
		}

		@Override
		public Set<? extends Class<?>> getRequiredContext() {
			return ImmutableSet.of(FilePath.class, Run.class, TaskListener.class, Launcher.class);
		}
	}

}

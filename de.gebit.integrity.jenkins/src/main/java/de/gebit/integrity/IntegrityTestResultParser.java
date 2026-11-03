/*******************************************************************************
 * Copyright (c) 2013 Rene Schneider, GEBIT Solutions GmbH and others. All rights reserved. This program and the
 * accompanying materials are made available under the terms of the Eclipse Public License v1.0 which accompanies this
 * distribution, and is available at http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package de.gebit.integrity;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Charsets;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import hudson.AbortException;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.test.TestResult;
import jenkins.MasterToSlaveFileCallable;

/**
 * The actual parser for parsing the Integrity result files and extraction of summary information.
 * 
 * @author Rene Schneider - initial API and implementation
 */
public class IntegrityTestResultParser implements Serializable {

	/**
	 * The serial version.
	 */
	private static final long serialVersionUID = 4841424533054027138L;

	/**
	 * The system property to control the maximum number of threads used to parse results.
	 */
	private static final String MAX_PARSER_THREADS_SYSTEM_PROPERTY = "integrity.threadcount";

	/**
	 * The default number of threads to use when parsing results.
	 */
	private static final int MAX_PARSER_THREADS_DEFAULT = 16;

	/**
	 * The actual number of threads used when parsing results. Will be whatever is smaller: either the number of
	 * processor cores, or whatever is configured via system property {@link #MAX_PARSER_THREADS_SYSTEM_PROPERTY} (if
	 * nothing is configured, the default {@link #MAX_PARSER_THREADS_DEFAULT} is used).
	 */
	private static final int MAX_PARSER_THREADS = Math.min(Integer.parseInt(
			System.getProperty(MAX_PARSER_THREADS_SYSTEM_PROPERTY, Integer.toString(MAX_PARSER_THREADS_DEFAULT))),
			Runtime.getRuntime().availableProcessors());

	private static final boolean IGNORE_TIMESTAMP_CHECK = Boolean.getBoolean(
			"hudson.tasks.test.TestResultParser.ignoreTimestampCheck");

	/**
	 * Resolves and parses report files on the agent without serializing the build or launcher.
	 */
	public TestResult parseResult(final String testResultLocations, Run<?, ?> build, final FilePath workspace,
			Launcher launcher, final TaskListener listener) throws InterruptedException, IOException {
		final long buildTime = build.getTimestamp().getTimeInMillis();
		return workspace.act(new MasterToSlaveFileCallable<TestResult>() {
			final boolean ignoreTimestampCheck = IGNORE_TIMESTAMP_CHECK; // so that the property can be set on the
																			// master
			final long nowMaster = System.currentTimeMillis();

			@Override
			public TestResult invoke(File dir, VirtualChannel channel) throws IOException, InterruptedException {
				final long nowSlave = System.currentTimeMillis();

				// files older than this timestamp is considered stale
				long localBuildTime = buildTime + (nowSlave - nowMaster);

				FilePath[] paths = new FilePath(dir).list(testResultLocations);
				if (paths.length == 0)
					throw new AbortException(
							"No test reports that matches " + testResultLocations + " found. Configuration error?");

				// since dir is local, paths all point to the local files
				List<File> files = new ArrayList<File>(paths.length);
				for (FilePath path : paths) {
					File report = new File(path.getRemote());
					if (ignoreTimestampCheck || localBuildTime - 3000 /* error margin */ < report.lastModified()) {
						// this file is created during this build
						files.add(report);
					}
				}

				if (files.isEmpty()) {
					// none of the files were new
					throw new AbortException(String.format(
							"Test reports were found but none of them are new. Did tests run? %n"
									+ "For example, %s is %s old%n",
							paths[0].getRemote(), Util.getTimeSpanString(localBuildTime - paths[0].lastModified())));
				}

				return parse(workspace, files, listener);
			}
		});
	}

	/**
	 * This method performs the actual file parsing on the agent.
	 * 
	 * @param someReportFiles
	 * @param aListener
	 * @return
	 */
	protected TestResult parse(FilePath workspace, List<File> someReportFiles, final TaskListener aListener) {
		final IntegrityCompoundTestResult tempCompoundTestResult = new IntegrityCompoundTestResult();

		ExecutorService tempExecutor = new ThreadPoolExecutor(MAX_PARSER_THREADS, MAX_PARSER_THREADS, 10L,
				TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
		aListener.getLogger().println("Will parse Integrity test results using " + MAX_PARSER_THREADS + " threads...");

		Set<String> tempUsedResultNames = new HashSet<>();
		for (final File tempFile : someReportFiles) {
			// Prevent collisions if the same name is used by two files
			String tempResultName = tempFile.getName();
			int tempSuffix = 0;
			while (tempUsedResultNames.contains(tempResultName)) {
				tempSuffix++;
				tempResultName = tempFile.getName() + "_" + tempSuffix;
			}
			tempUsedResultNames.add(tempResultName);

			final String tempFinalResultName = tempResultName;

			Runnable tempRunnable = new Runnable() {

				@Override
				public void run() {
					aListener.getLogger().println("Now parsing Integrity test result file " + tempFile.getAbsolutePath()
							+ " using Thread '" + Thread.currentThread().getName() + "'");

					try {
						// Read the file into memory. Mainly done to archive it in the result, but the buffer is also
						// fed into a SAX parser below to prevent reading the file twice.
						FileInputStream tempInputStream = new FileInputStream(tempFile);
						final byte[] tempBuffer = new byte[(int) tempFile.length()];
						try {
							int tempTotalRead = 0;
							int tempRead = 0;
							while (tempTotalRead < tempBuffer.length && tempRead >= 0) {
								tempRead = tempInputStream.read(tempBuffer, tempTotalRead,
										tempBuffer.length - tempTotalRead);
								if (tempRead > 0) {
									tempTotalRead += tempRead;
								}
							}
						} finally {
							tempInputStream.close();
						}

						String tempContentType = null;
						int tempXMLDataStartPos = 0;
						int tempDoctypeEndPos = 0;
						boolean tempIsHtml = false;
						int tempBufferStart = 0;
						if (tempBuffer.length > 10) {
							// Skip some whitespace in the beginning, if there is any
							while(tempBuffer[tempBufferStart] <= ' ' && tempBufferStart < 5) {
								tempBufferStart++;
							}
							
							if (tempBuffer[tempBufferStart + 0] == '<' && tempBuffer[tempBufferStart + 1] == '?' && tempBuffer[tempBufferStart + 2] == 'x'
									&& tempBuffer[tempBufferStart + 3] == 'm' && tempBuffer[tempBufferStart + 4] == 'l') {
								// This seems to be XML data
								tempContentType = "text/xml;charset=UTF-8";
							} else {
								// This seems to be HTML
								tempContentType = "text/html;charset=UTF-8";
								tempIsHtml = true;

								// Find out where the DOCTYPE declaration ends
								if ("<!DOCTYPE ".equals(new String(tempBuffer, tempBufferStart , 10, "US-ASCII"))) {
									do {
										tempDoctypeEndPos++;
									} while (tempDoctypeEndPos < tempBuffer.length
											&& tempBuffer[tempDoctypeEndPos - 1] != '>');
									tempXMLDataStartPos = tempDoctypeEndPos; // XML cannot start before the DOCTYPE
								}

								// To increase robustness, we forward the stream to the start of the actual XML data
								// embedded in the HTML
								while (tempXMLDataStartPos < tempBuffer.length - 10
										&& !(tempBuffer[tempXMLDataStartPos] == '<'
												&& tempBuffer[tempXMLDataStartPos + 1] == 'x'
												&& tempBuffer[tempXMLDataStartPos + 2] == 'm'
												&& tempBuffer[tempXMLDataStartPos + 3] == 'l'
												&& tempBuffer[tempXMLDataStartPos + 4] == 'd'
												&& tempBuffer[tempXMLDataStartPos + 5] == 'a'
												&& tempBuffer[tempXMLDataStartPos + 6] == 't'
												&& tempBuffer[tempXMLDataStartPos + 7] == 'a'
												&& tempBuffer[tempXMLDataStartPos + 8] == ' ')) {
									tempXMLDataStartPos++;
								}
							}
						}

						InputStream tempFinalInputStream;
						if (tempDoctypeEndPos > 0 && tempXMLDataStartPos < tempBuffer.length) {
							// If we have an end position for the DOCTYPE declaration and a valid XML data start, just
							// sequence the doctype declaration with the XML data, thereby eliminating everything in
							// between that could cause trouble
							tempFinalInputStream = new SequenceInputStream(
									new ByteArrayInputStream(tempBuffer, tempBufferStart, tempDoctypeEndPos),
									new ByteArrayInputStream(tempBuffer, tempXMLDataStartPos,
											tempBuffer.length - tempXMLDataStartPos));
						} else {
							// Just start parsing where the XML begins
							tempFinalInputStream = new ByteArrayInputStream(tempBuffer, tempXMLDataStartPos,
									tempBuffer.length - tempXMLDataStartPos);
						}

						XMLInputFactory tempInputFactory = XMLInputFactory.newInstance();
						tempInputFactory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
						tempInputFactory.setProperty(XMLInputFactory.IS_VALIDATING, false);
						tempInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
						XMLEventReader tempEventReader = tempInputFactory.createXMLEventReader(tempFinalInputStream);

						IntegrityContentHandler tempHandler = new IntegrityContentHandler();
						try {
							while (tempEventReader.hasNext() && tempHandler.handleEvent(tempEventReader.nextEvent())) {
								// loop
							}
						} finally {
							tempEventReader.close();
						}

						tempCompoundTestResult.addChild(new IntegrityTestResult(tempCompoundTestResult,
								tempFinalResultName, tempHandler.getTestName(), tempBuffer, tempContentType,
								tempHandler.getSuccessCount(), tempHandler.getFailureCount(),
								tempHandler.getTestExceptionCount(), tempHandler.getCallExceptionCount()));

						aListener.getLogger().println(
								"Successfully parsed Integrity test result file " + tempFile.getAbsolutePath());
					} catch (Throwable exc) {
						aListener.getLogger().println("Exception while parsing Integrity result: " + exc.getMessage());
						tempCompoundTestResult.addChild(new IntegrityTestResult(tempCompoundTestResult, tempFinalResultName, 
							"Parse Error in file " + tempFile.getName() + " - see build logs or this report for details",
							exc.getMessage().getBytes(Charsets.UTF_8), "text/plain;charset=UTF-8", 0, 0, 1, 0));
					}
				}
			};

			tempExecutor.execute(tempRunnable);
		}

		tempExecutor.shutdown();

		aListener.getLogger().println("Now waiting for async Integrity test result parsers to finish");

		while (!tempExecutor.isTerminated()) {
			try {
				if (!tempExecutor.awaitTermination(1, TimeUnit.DAYS)) {
					throw new RuntimeException("Integrity test result parsing threads did not terminate in time :-( "
							+ "since the timeout is obscenely high, this should never happen in practice");
				}
			} catch (InterruptedException exc) {
				// ignored
			}
		}

		aListener.getLogger().println("Integrity test result parsers have finished, "
				+ tempCompoundTestResult.getChildren().size() + " result(s) were parsed");
		tempCompoundTestResult.updateCounts();

		return tempCompoundTestResult;
	}

	private static class IntegrityContentHandler {

		/**
		 * The number of successful tests.
		 */
		private int successCount;

		/**
		 * The number of failed tests.
		 */
		private int failureCount;

		/**
		 * The number of exceptions in tests.
		 */
		private int testExceptionCount;

		/**
		 * The number of exceptions in calls.
		 */
		private int callExceptionCount;

		/**
		 * The depth of the stack of suites at the moment.
		 */
		private int suiteStackDepth;

		/**
		 * Whether we are currently inside the XSLT script part.
		 */
		private boolean insideXslt;

		/**
		 * The name of the test run.
		 */
		private String testName;

		public int getSuccessCount() {
			return successCount;
		}

		public int getFailureCount() {
			return failureCount;
		}

		public int getTestExceptionCount() {
			return testExceptionCount;
		}

		public int getCallExceptionCount() {
			return callExceptionCount;
		}

		public String getTestName() {
			return testName;
		}

		public boolean handleEvent(XMLEvent anEvent) {
			if (anEvent.isStartElement()) {
				StartElement tempStartEvent = anEvent.asStartElement();

				if (!insideXslt) {
					if ("stylesheet".equals(tempStartEvent.getName().getLocalPart())) {
						insideXslt = true;
						return true;
					}

					if ("suite".equals(tempStartEvent.getName().getLocalPart())) {
						suiteStackDepth++;
					} else if ("integrity".equals(tempStartEvent.getName().getLocalPart())) {
						testName = tempStartEvent.getAttributeByName(new QName("name")).getValue();
					} else if ("result".equals(tempStartEvent.getName().getLocalPart())) {
						if (suiteStackDepth == 1 && tempStartEvent.getAttributeByName(new QName("type")) == null) {
							// This seems to be the outermost suite result element (call results are also <result>
							// elements,
							// but they contain a result type instead of a summary). We simply fetch the execution
							// totals
							// from this one and rely on Integrity for summing them up correctly.

							String tempSuccessCount
									= getValueIgnoreCase(tempStartEvent.getAttributes(), "successCount");
							if (tempSuccessCount != null) {
								successCount = Integer.parseInt(tempSuccessCount);
							}

							String tempFailureCount
									= getValueIgnoreCase(tempStartEvent.getAttributes(), "failureCount");
							if (tempFailureCount != null) {
								failureCount = Integer.parseInt(tempFailureCount);
							}

							String tempTestExceptionCount
									= getValueIgnoreCase(tempStartEvent.getAttributes(), "testExceptionCount");
							if (tempTestExceptionCount != null) {
								testExceptionCount = Integer.parseInt(tempTestExceptionCount);
							}

							String tempCallExceptionCount
									= getValueIgnoreCase(tempStartEvent.getAttributes(), "callExceptionCount");
							if (tempTestExceptionCount != null) {
								callExceptionCount = Integer.parseInt(tempCallExceptionCount);
							}

							// When we've arrived here, we have parsed everything necessary out of the file!
							return false;
						}
					}
				}
			} else if (anEvent.isEndElement()) {
				EndElement tempEndEvent = anEvent.asEndElement();

				if (insideXslt) {
					if ("stylesheet".equals(tempEndEvent.getName().getLocalPart())) {
						insideXslt = false;
					}
				} else {
					if ("suite".equals(tempEndEvent.getName().getLocalPart())) {
						suiteStackDepth--;
					}
				}
			}

			return true;
		}

		private String getValueIgnoreCase(@SuppressWarnings("rawtypes") Iterator someAttributes, String aName) {
			while (someAttributes.hasNext()) {
				Attribute tempAttribute = (Attribute) someAttributes.next();
				String tempQName = tempAttribute.getName().getLocalPart();
				if (tempQName.equalsIgnoreCase(aName)) {
					return tempAttribute.getValue();
				}
			}
			return null;
		}
	}

}

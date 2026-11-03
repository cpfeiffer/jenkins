/*******************************************************************************
 * Copyright (c) 2013 Rene Schneider, GEBIT Solutions GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package de.gebit.integrity;

import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import com.thoughtworks.xstream.core.util.CustomObjectOutputStream;

import hudson.XmlFile;
import hudson.model.Run;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TabulatedResult;
import hudson.tasks.test.TestObject;
import hudson.tasks.test.TestResult;
import hudson.util.HeapSpaceStringConverter;
import hudson.util.XStream2;

/**
 * This test result combines multiple single test results into one. The single test results are children of this
 * compound.
 * 
 * @author Rene Schneider - initial API and implementation
 */
public class IntegrityCompoundTestResult extends TabulatedResult {

	/**
	 * The serialization version.
	 */
	private static final long serialVersionUID = 5660708469068256878L;

	/**
	 * The XStream instance used for result persistence.
	 */
	private static final XStream XSTREAM = new XStream2();

	static {
		XSTREAM.alias("result", IntegrityCompoundTestResult.class);
		XSTREAM.processAnnotations(IntegrityCompoundTestResult.class);
		XSTREAM.registerConverter(new HeapSpaceStringConverter(), 100);
	}

	/**
	 * The inner test results. This field is non-transient in order to ensure that it is serialized and transferred from
	 * build slaves to the master (which works via Java Serialization), but it is omitted by XStream, because for
	 * XStream serialization (which Jenkins uses to persist build results) we want this field to be considered transient
	 * - its contents can get quite huge, and we serialize it into a separate file and deserialize it only on demand.
	 */
	@XStreamOmitField
	private List<IntegrityTestResult> tempChildren;

	/**
	 * Whether we already updated child links.
	 */
	private transient boolean hasUpdatedChildLinks;

	/**
	 * Whether the currently loaded children are persisted.
	 */
	private transient boolean hasPersistedChildren;

	/**
	 * Total count of failed tests over all children.
	 */
	private int failCount;

	/**
	 * Total count of passed tests over all children.
	 */
	private int passCount;

	/**
	 * Total count of skipped tests over all children.
	 */
	private int skipCount;

	/**
	 * Total count of exceptions in tests over all children.
	 */
	private int testExceptionCount;

	/**
	 * Total count of exceptions in calls over all children.
	 */
	private int callExceptionCount;

	/**
	 * The action owning this result.
	 */
	private transient AbstractTestResultAction<?> parentAction;

	/**
	 * Adds a child (single test result).
	 * 
	 * @param aChild
	 *            the child to add
	 */
	public synchronized void addChild(IntegrityTestResult aChild) {
		if (tempChildren == null) {
			tempChildren = new ArrayList<IntegrityTestResult>();
		}

		tempChildren.add(aChild);
		aChild.setParent(this);
		hasPersistedChildren = false;

		sortChildren();
	}

	private XmlFile getXmlFile() {
		File tempRootDir = null;
		Run<?, ?> tempRun = getRun();
		if (tempRun != null) {
			tempRootDir = tempRun.getRootDir();
		}

		if (tempRootDir == null) {
			return null;
		} else {
			return new XmlFile(XSTREAM, new File(tempRootDir, "integrityResultData.xml"));
		}
	}

	private void sortChildren() {
		Collections.sort(tempChildren, new Comparator<IntegrityTestResult>() {

			@Override
			public int compare(IntegrityTestResult o1, IntegrityTestResult o2) {
				String tempFirstName = o1.getDisplayName() != null ? o1.getDisplayName() : "";
				String tempSecondName = o2.getDisplayName() != null ? o2.getDisplayName() : "";

				return tempFirstName.compareToIgnoreCase(tempSecondName);
			}
		});
	}

	@SuppressWarnings("unchecked")
	private void loadChildren() {
		try {
			XmlFile tempFile = getXmlFile();
			if (tempFile != null) {
				tempChildren = (List<IntegrityTestResult>) getXmlFile().read();
			} else {
				tempChildren = new ArrayList<IntegrityTestResult>();
			}
		} catch (IOException exc) {
			exc.printStackTrace();
			tempChildren = new ArrayList<IntegrityTestResult>();
		}

		hasPersistedChildren = true;
		sortChildren();
	}

	private void persistChildren() {
		if (tempChildren != null) {
			try {
				XmlFile tempFile = getXmlFile();
				if (tempFile != null) {
					getXmlFile().write(tempChildren);
					hasPersistedChildren = true;
				}
			} catch (IOException exc) {
				exc.printStackTrace();
			}
		}
	}

	public void updateCounts() {
		passCount = 0;
		failCount = 0;
		skipCount = 0;
		testExceptionCount = 0;
		callExceptionCount = 0;

		if (hasChildren()) {
			for (TestResult tempResult : getChildren()) {
				passCount += tempResult.getPassCount();
				failCount += tempResult.getFailCount();
				skipCount += tempResult.getSkipCount();
				if (tempResult instanceof IntegrityTestResult) {
					testExceptionCount += ((IntegrityTestResult) tempResult).getTestExceptionCount();
					callExceptionCount += ((IntegrityTestResult) tempResult).getCallExceptionCount();
				}
			}
		}
	}

	private void writeObject(ObjectOutputStream stream) throws IOException {
		updateCounts();

		stream.defaultWriteObject();

		if (stream instanceof CustomObjectOutputStream) {
			// Only in case of XStream, persist the children into a separate file.
			// With Java Serialization we don't, since the children are transported within the serialized compound
			// result.
			if (!hasPersistedChildren) {
				persistChildren();
			}
		}
	}

	public String getDisplayName() {
		return "Integrity Test Results";
	}

	@Override
	public TestObject getParent() {
		return null;
	}

	@Override
	public Run<?, ?> getRun() {
		return (parentAction == null ? null : parentAction.run);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void setParentAction(AbstractTestResultAction aParentAction) {
		parentAction = aParentAction;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public AbstractTestResultAction getParentAction() {
		return parentAction;
	}

	@Override
	public Object getDynamic(String aToken, StaplerRequest2 aRequest, StaplerResponse2 aResponse) {
		TestResult tempResult = findCorrespondingResult(aToken);
		if (tempResult != null) {
			return tempResult;
		}
		return null;
	}

	@Override
	public TestResult findCorrespondingResult(String anId) {
		if (getId().equals(anId) || (anId == null)) {
			return this;
		} else {
			if (hasChildren()) {
				// For some totally unexplainable reason (no, really...I spent several hours to find an explanation, but
				// was unsuccessful) the Jenkins sometimes designates an ID of "(empty)" to instances of this class. If
				// it does, the children automatically expand their ID, so the queried ID does not match anymore. I
				// simply expand it as well here :-)
				String tempChildId = (getId() != null && getId().length() > 0) ? getId() + "/" + anId : anId;

				for (TestResult tempChild : getChildren()) {
					TestResult tempResult = tempChild.findCorrespondingResult(tempChildId);
					if (tempResult != null) {
						return tempResult;
					}
				}
			}
			return null;
		}
	}

	@Override
	public boolean hasChildren() {
		return getChildren().size() > 0;
	}

	@Override
	public Collection<? extends TestResult> getChildren() {
		if (tempChildren == null) {
			loadChildren();
		}

		if (!hasUpdatedChildLinks) {
			for (IntegrityTestResult tempChild : tempChildren) {
				tempChild.setParent(this);
			}
			hasUpdatedChildLinks = true;
		}

		return tempChildren;
	}

	@Override
	public int getFailCount() {
		return failCount;
	}

	public int getFailChildCount() {
		int tempCount = 0;
		for (TestResult tempChild : getChildren()) {
			if (tempChild.getFailCount() > 0) {
				tempCount++;
			}
		}

		return tempCount;
	}

	@Override
	public int getPassCount() {
		return passCount;
	}

	public int getPassChildCount() {
		int tempCount = 0;
		for (TestResult tempChild : getChildren()) {
			if (tempChild.getFailCount() == 0 && ((IntegrityTestResult) tempChild).getExceptionCount() == 0) {
				tempCount++;
			}
		}

		return tempCount;
	}

	@Override
	public int getSkipCount() {
		return skipCount;
	}

	public int getExceptionChildCount() {
		int tempCount = 0;
		for (TestResult tempChild : getChildren()) {
			if (((IntegrityTestResult) tempChild).getExceptionCount() > 0) {
				tempCount++;
			}
		}

		return tempCount;
	}

	public int getTestExceptionCount() {
		return testExceptionCount;
	}

	public int getCallExceptionCount() {
		return callExceptionCount;
	}

	/**
	 * Returns the total exception count.
	 * 
	 * @return
	 */
	public int getExceptionCount() {
		return getTestExceptionCount() + getCallExceptionCount();
	}
}

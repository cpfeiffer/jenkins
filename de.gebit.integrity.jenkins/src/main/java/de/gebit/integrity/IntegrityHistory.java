/*******************************************************************************
 * Copyright (c) 2013 Rene Schneider, GEBIT Solutions GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package de.gebit.integrity;

import java.awt.Color;
import java.awt.Paint;
import java.util.ArrayList;
import java.util.List;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.CategoryAxis;
import org.jfree.chart.axis.CategoryLabelPositions;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.renderer.category.StackedAreaRenderer;
import org.jfree.data.category.CategoryDataset;
import org.jfree.ui.RectangleInsets;
import org.kohsuke.stapler.Stapler;

import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.tasks.test.TestResult;
import hudson.util.ChartUtil;
import hudson.util.ColorPalette;
import hudson.util.DataSetBuilder;
import hudson.util.Graph;
import hudson.util.RunList;
import hudson.util.ShiftedCategoryAxis;
import hudson.util.StackedAreaRenderer2;
import jenkins.model.Jenkins;
import jenkins.security.stapler.StaplerAccessibleType;

/**
 * The history graph view for Integrity result history. Heavily based on the standard test history view in Jenkins,
 * which isn't that well-suited for customization, thus a some of the code was duplicated.
 * 
 * @author Rene Schneider - initial API and implementation
 */
@StaplerAccessibleType
public class IntegrityHistory {

	/**
	 * The test result used as base for the graph.
	 */
	private final RunList<?> runs;

	/**
	 * Creates a new instance.
	 * 
	 * @param aRunList
	 *            the run list
	 */
	public IntegrityHistory(RunList<?> aRunList) {
		this.runs = aRunList;
	}

	/**
	 * Checks whether a history is available at all.
	 * 
	 * @return true if available, false otherwise
	 */
	public boolean historyAvailable() {
		return runs.size() > 1;
	}

	/**
	 * Fetches test results in a certain build number interval.
	 * 
	 * @param aStart
	 *            the start build
	 * @param anEnd
	 *            the end build
	 * @return the test results
	 */
	public List<TestResult> getList(int aStart, int anEnd) {
		List<TestResult> tempList = new ArrayList<TestResult>();
		int tempEnd = Math.min(anEnd, runs.size());
		for (Run<?, ?> tempBuild : runs.subList(aStart, tempEnd)) {
			if (tempBuild.isBuilding()) {
				continue;
			}
			IntegrityTestResultAction tempResultAction = tempBuild.getAction(IntegrityTestResultAction.class);
			if (tempResultAction != null && tempResultAction.getTarget() != null) {
				tempList.add((IntegrityCompoundTestResult) tempResultAction.getTarget());
			}
		}
		return tempList;
	}

	public List<TestResult> getList() {
		return getList(0, runs.size());
	}

	/**
	 * Returns a graph of test results (# successful, failed etc.) over time.
	 * 
	 * @return
	 */
	public Graph getCountGraph() {
		return new GraphImpl("") {

			@Override
			protected DataSetBuilder<String, ChartLabel> createDataSet() {
				DataSetBuilder<String, ChartLabel> tempData = new DataSetBuilder<String, ChartLabel>();

				List<TestResult> tempResultList;
				try {
					tempResultList = getList(Integer.parseInt(Stapler.getCurrentRequest2().getParameter("start")),
							Integer.parseInt(Stapler.getCurrentRequest2().getParameter("end")));
				} catch (NumberFormatException exc) {
					tempResultList = getList();
				}

				for (TestResult tempResult : tempResultList) {
					tempData.add(tempResult.getPassCount(), "2Passed", new ChartLabel(tempResult));
					tempData.add(tempResult.getFailCount(), "1Failed", new ChartLabel(tempResult));
					tempData.add(tempResult.getSkipCount(), "0Skipped", new ChartLabel(tempResult));
				}

				return tempData;
			}
		};
	}

	private abstract class GraphImpl extends Graph {

		/**
		 * The label displayed at the y axis.
		 */
		private final String yLabel;

		protected GraphImpl(String anYLabel) {
			super(-1, 500, 200);
			this.yLabel = anYLabel;
		}

		protected abstract DataSetBuilder<String, ChartLabel> createDataSet();

		@Override
		protected JFreeChart createGraph() {
			final CategoryDataset dataset = createDataSet().build();

			final JFreeChart chart = ChartFactory.createStackedAreaChart(null, null, yLabel, dataset,
					PlotOrientation.VERTICAL, false, true, false);

			chart.setBackgroundPaint(Color.white);

			final CategoryPlot plot = chart.getCategoryPlot();

			// plot.setAxisOffset(new Spacer(Spacer.ABSOLUTE, 5.0, 5.0, 5.0, 5.0));
			plot.setBackgroundPaint(Color.WHITE);
			plot.setOutlinePaint(null);
			plot.setForegroundAlpha(0.8f);
			plot.setRangeGridlinesVisible(true);
			plot.setRangeGridlinePaint(Color.BLACK);

			CategoryAxis tempDomainAxis = new ShiftedCategoryAxis(null);
			plot.setDomainAxis(tempDomainAxis);
			tempDomainAxis.setCategoryLabelPositions(CategoryLabelPositions.UP_90);
			tempDomainAxis.setLowerMargin(0.0);
			tempDomainAxis.setUpperMargin(0.0);
			tempDomainAxis.setCategoryMargin(0.0);

			final NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
			ChartUtil.adjustChebyshev(dataset, rangeAxis);
			rangeAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());
			rangeAxis.setAutoRange(true);

			StackedAreaRenderer tempAreaRenderer = new MyStackedAreaRenderer(dataset);
			plot.setRenderer(tempAreaRenderer);
			tempAreaRenderer.setSeriesPaint(0, ColorPalette.YELLOW); // Failures.
			tempAreaRenderer.setSeriesPaint(1, ColorPalette.RED); // Skips.
			tempAreaRenderer.setSeriesPaint(2, ColorPalette.BLUE); // Total.

			// crop extra space around the graph
			plot.setInsets(new RectangleInsets(0, 0, 0, 5.0));

			return chart;
		}
	}

	public static class MyStackedAreaRenderer extends StackedAreaRenderer2 {

		/**
		 * The serialization version.
		 */
		private static final long serialVersionUID = 2284582320567360791L;

		public MyStackedAreaRenderer(CategoryDataset aDataset) {
			super();
		}

		@Override
		public Paint getItemPaint(int aRow, int aColumn) {
			return super.getItemPaint(aRow, aColumn);
		}

		@Override
		public String generateURL(CategoryDataset aDataset, int aRow, int aColumn) {
			ChartLabel tempLabel = (ChartLabel) aDataset.getColumnKey(aColumn);
			return tempLabel.getUrl();
		}

		@Override
		public String generateToolTip(CategoryDataset aDataset, int aRow, int aColumn) {
			ChartLabel tempLabel = (ChartLabel) aDataset.getColumnKey(aColumn);
			TestResult tempResult = tempLabel.result;
			return tempResult.getRun().getDisplayName() + ": " + tempResult.getPassCount() + " successful tests, "
					+ tempResult.getFailCount() + " failures, " + tempResult.getSkipCount()
					+ " exceptions during tests";
		}

		@Override
		public boolean equals(Object obj) {
			return super.equals(obj);
		}

		@Override
		public int hashCode() {
			return super.hashCode();
		}
	};

	public static class ChartLabel implements Comparable<ChartLabel> {

		/**
		 * The test result.
		 */
		private TestResult result;

		/**
		 * The URL where this label is to be linked.
		 */
		private String url;

		public ChartLabel(TestResult aResult) {
			this.result = aResult;
		}

		public String getUrl() {
			if (url == null) {
				generateUrl();
			}
			return url;
		}

		private void generateUrl() {
			Run<?, ?> tempBuild = result.getRun();
			String tempBuildLink = tempBuild.getUrl();
			String tempActionUrl = IntegrityTestResultAction.ACTION_URL;
			this.url = Jenkins.get().getServletContext().getContextPath() + "/" + tempBuildLink + tempActionUrl;
		}

		@Override
		public int compareTo(ChartLabel anOtherLabel) {
			return this.result.getRun().number - anOtherLabel.result.getRun().number;
		}

		@Override
		public boolean equals(Object anOtherObject) {
			if (!(anOtherObject instanceof ChartLabel)) {
				return false;
			}
			ChartLabel tempOtherObject = (ChartLabel) anOtherObject;
			return this.result.equals(tempOtherObject.result) && this.result == tempOtherObject.result;
		}

		public Color getColor() {
			return null;
		}

		@Override
		public int hashCode() {
			return result.hashCode();
		}

		@Override
		public String toString() {
			Run<?, ?> tempRun = result.getRun();
			String tempLabel = tempRun.getDisplayName();
			if (tempRun instanceof AbstractBuild<?, ?>) {
				String tempBuiltOn = ((AbstractBuild<?, ?>) tempRun).getBuiltOnStr();
				if (tempBuiltOn != null) {
					tempLabel += ' ' + tempBuiltOn;
				}
			}
			return tempLabel;
		}

	}
}

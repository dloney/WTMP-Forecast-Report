/*
 *
 *  * Copyright 2023 United States Bureau of Reclamation (USBR).
 *  * United States Department of the Interior
 *  * All Rights Reserved. USBR PROPRIETARY/CONFIDENTIAL.
 *  * Source may not be released without written approval
 *  * from USBR
 *
 */

package usbr.wat.plugins.forecastreport.io;

import java.util.Arrays;
import java.util.List;
import com.rma.util.XMLUtilities;
import org.jdom.Element;
import rma.util.RMAIO;
import usbr.wat.plugins.actionpanel.io.ReportXmlFile;
import usbr.wat.plugins.actionpanel.model.AbstractSimulationGroup;
import usbr.wat.plugins.actionpanel.model.SimulationReportInfo;
import usbr.wat.plugins.actionpanel.model.forecast.EnsembleReportInfo;
import usbr.wat.plugins.actionpanel.model.forecast.ForecastSimGroup;

/**
 * {@link ReportXmlFile} subclass that adds forecast-specific data (ensemble
 * set information) to the XML data file used to feed the Jasper-based
 * forecast report.
 * <p>
 * In addition to the base simulation information written by
 * {@link ReportXmlFile}, this class writes, for each configured
 * {@link EnsembleReportInfo}, the ensemble set name, its associated
 * operations/meteorology/temperature-target data set names, the collection
 * index range for the ensemble set within the simulation, and the list of
 * ensemble members that should be included in the report.
 */
public class ForecastReportXmlFile extends ReportXmlFile
{
	// XML element/attribute name constants below define the tag names used
	// when writing the forecast-specific sections of the report XML file.

	// Parent element that wraps all <EnsembleSet> children for a simulation.
	private static final String ENSEMBLE_SETS = "EnsembleSets";
	// Element representing a single ensemble set entry.
	private static final String ENSEMBLE_SET = "EnsembleSet";
	// Name of the operations (ops) boundary condition data set used by the ensemble set.
	private static final String OPERATIONS_NAMES = "OperationsName";
	// Name of the meteorology (met) boundary condition data set used by the ensemble set.
	private static final String MET_NAME = "MetName";
	// Name of the temperature target data set used by the ensemble set.
	private static final String TEMP_TARGET_NAME = "TempTargetName";
	// Generic "Name" tag used for the ensemble set's own name.
	private static final String NAME = "Name";
	// Start index (inclusive) of the collection range for this ensemble set.
	private static final String COLLECTIONS_START = "CollectionsStart";
	// End index (inclusive) of the collection range for this ensemble set.
	private static final String COLLECTIONS_END = "CollectionsEnd";
	// Comma-separated list of ensemble member indices to include in the report.
	private static final String MEMBERS_TO_REPORT = "MembersToReport";
	// The report "type" value written by getReportType() for this XML file.
	private static final String FORECAST_TYPE = "Forecast";

	// The list of ensemble sets (and which members of each to report on) that
	// should be written into the XML file. Populated via setEnsembleInfo().
	private List<EnsembleReportInfo> _ensembleInfos;

	// The forecast simulation group used to look up collection indexing for
	// each ensemble set. Populated via setSimulationGroup().
	private ForecastSimGroup _simulationGroup;

	/**
	 * Creates a new forecast report XML file wrapper that will write to the
	 * given filename.
	 *
	 * @param filename the full path of the XML file to be created/written
	 */
	public ForecastReportXmlFile(String filename)
	{
		super(filename);
	}

	/**
	 * Adds the forecast-specific ensemble set information to the given
	 * simulation XML element. For every {@link EnsembleReportInfo} configured
	 * via {@link #setEnsembleInfo(List)}, this writes a child
	 * {@code <EnsembleSet>} element (under a parent {@code <EnsembleSets>}
	 * element) containing the ensemble set's name, associated data set
	 * names, collection index range, and the members to report on.
	 *
	 * @param simElem the simulation XML element to append the ensemble set
	 *                information to
	 * @param info    the simulation report info for the simulation being
	 *                written (used to resolve the collection indexing for
	 *                each ensemble set)
	 */
	protected void addAdditionalInfoForSim(Element simElem, SimulationReportInfo info)
	{
		// Create and attach the parent <EnsembleSets> element that will hold
		// one <EnsembleSet> child per configured ensemble report info entry.
		Element ensemblesElem = new Element(ENSEMBLE_SETS);
		simElem.addContent(ensemblesElem);
		EnsembleReportInfo esInfo;
		// Iterate over every ensemble set configured for this report.
		for (int i = 0;i < _ensembleInfos.size(); i++ )
		{
			esInfo = _ensembleInfos.get(i);

			// Create the <EnsembleSet> element for this ensemble info entry.
			Element ensembleElem = new Element(ENSEMBLE_SET);
			ensemblesElem.addContent(ensembleElem);

			// Write the ensemble set's own name.
			XMLUtilities.addChildContent(ensembleElem, NAME,esInfo.getEnsembleSet().getName());

			// Write the names of the operations/met/temperature-target data
			// sets that back this ensemble set's boundary conditions.
			XMLUtilities.addChildContent(ensembleElem, OPERATIONS_NAMES,esInfo.getEnsembleSet().getBcData().getOpsDataName());
			XMLUtilities.addChildContent(ensembleElem, MET_NAME,esInfo.getEnsembleSet().getBcData().getMetDataName());
			XMLUtilities.addChildContent(ensembleElem, TEMP_TARGET_NAME,esInfo.getEnsembleSet().getTemperatureTargetSetName());

			// Look up the [start, end] collection index range that this
			// ensemble set occupies within the simulation's collections.
			int[] collectionIndexing = _simulationGroup.getEnsembleSetCollectionIndexing(info.getSimulation(), esInfo.getEnsembleSet());
			XMLUtilities.addChildContent(ensembleElem, COLLECTIONS_START,collectionIndexing[0]);
			XMLUtilities.addChildContent(ensembleElem, COLLECTIONS_END,collectionIndexing[1]);

			// Build a comma-separated string of the member indices to report
			// on, stripping the surrounding brackets produced by Arrays.toString().
			int[] members = esInfo.getMembersToReportOn();
			String membersStr = Arrays.toString(members);
			membersStr = RMAIO.removeChar(membersStr, '[');
			membersStr = RMAIO.removeChar(membersStr, ']');
			XMLUtilities.addChildContent(ensembleElem, MEMBERS_TO_REPORT,membersStr);
		}
	}

	/**
	 * Sets the list of ensemble sets (and the members within each to report
	 * on) that should be written to the XML file when it is created.
	 *
	 * @param ensembleReportInfos the ensemble report info entries to write
	 */
	public void setEnsembleInfo(List<EnsembleReportInfo> ensembleReportInfos)
	{
		_ensembleInfos = ensembleReportInfos;
	}

	/**
	 * Sets the forecast simulation group used to resolve collection index
	 * ranges for each ensemble set when writing the XML file.
	 *
	 * @param simulationGroup the forecast simulation group to use
	 */
	public void setSimulationGroup(ForecastSimGroup simulationGroup)
	{
		_simulationGroup = simulationGroup;
	}

	/**
	 * Returns the report "type" value written into the XML file to identify
	 * it as a forecast report (as opposed to other report types supported by
	 * the base {@link ReportXmlFile} hierarchy).
	 *
	 * @return the constant string {@link #FORECAST_TYPE} ("Forecast")
	 */
	protected String getReportType()
	{
		return FORECAST_TYPE;
	}
}

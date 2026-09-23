/*
 * Copyright 2021  Hydrologic Engineering Center (HEC).
 * United States Army Corps of Engineers
 * All Rights Reserved.  HEC PROPRIETARY/CONFIDENTIAL.
 * Source may not be released without written approval
 * from HEC
 */
package usbr.wat.plugins.forecastreport.actions;


import java.awt.EventQueue;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.swing.Action;
import hec2.plugin.model.Simulation;
import org.python.core.PyCode;
import org.python.util.PythonInterpreter;

import com.rma.io.FileManagerImpl;
import com.rma.io.RmaFile;
import com.rma.model.Project;

import hec2.plugin.model.ModelAlternative;
import hec2.wat.model.WatSimulation;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRExporter;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.repo.FileRepositoryPersistenceServiceFactory;
import net.sf.jasperreports.repo.FileRepositoryService;
import net.sf.jasperreports.repo.PersistenceServiceFactory;
import net.sf.jasperreports.repo.RepositoryService;
import rma.util.RMAIO;
import usbr.wat.plugins.actionpanel.ActionPanelPlugin;
import usbr.wat.plugins.actionpanel.ActionsWindow;
import usbr.wat.plugins.actionpanel.actions.AbstractReportAction;
import usbr.wat.plugins.actionpanel.io.ReportOptions;
import usbr.wat.plugins.actionpanel.model.ForecastReportingPlugin;
import usbr.wat.plugins.actionpanel.model.ReportsManager;
import usbr.wat.plugins.actionpanel.model.SimulationReportInfo;
import usbr.wat.plugins.actionpanel.ui.UsbrPanel;
import usbr.wat.plugins.forecastreport.io.ForecastReportXmlFile;
import usbr.wat.plugins.actionpanel.model.forecast.EnsembleReportInfo;

/**
 * @author Mark Ackerman
 *
 */
// NOTE: Several helper members used in this class (e.g. REPORT_DIR,
// PYTHON_REPORT_BAT, JASPER_FILE, getObsDataPath(), findFpartForPython(),
// getDirectoryToUse(), runProcess(), getJasperRelativeFolder(),
// compileJasperFiles(), fillReport(), and the single-argument overload of
// runPythonScript(String)) are not declared in this file. They are inherited
// from the {@link AbstractReportAction} superclass (or one of its
// ancestors) and are documented there; comments below describe how they are
// used from this class's point of view.
//
// This class implements the {@link ForecastReportingPlugin} contract and is
// responsible for orchestrating the full forecast report creation pipeline:
// 1. Write out an XML "data adapter" file describing the simulations and
//    ensemble sets to report on (createSimulationXmlFile).
// 2. Update/point the Jasper data adapter XML at that file (editDataAdapterFile,
//    inherited).
// 3. Run an external Python script (via a batch/command line call) that
//    performs the actual forecast-vs-observed data processing
//    (runPythonScript).
// 4. Compile and fill the Jasper report template with the processed data and
//    export it to a PDF file (runJasperReport).
@SuppressWarnings("serial")
public class CreateReportsAction extends AbstractReportAction
	implements ForecastReportingPlugin
{

	// Name of the sub-folder (relative to the study directory) where the
	// compiled/compiling Jasper report templates live.
	private static final String JASPER_REPORT_DIR = "Reports";

	// Prefix used for the generated PDF report file name; the current
	// timestamp and the file extension are appended to this when the report
	// is actually written out (see runJasperReport()).
	public static final String JASPER_OUT_FILE = "WTMP_report_draft-";

	// File extension applied to the generated report output file.
	public static final String REPORT_FILE_EXT = ".pdf";

	// Name of the XML data adapter document consumed by the Jasper report
	// definition to source its report data.
	private static final String XML_DATA_DOCUMENT = "USBRAutomatedReportDataAdapter.xml";

	// Name of the folder (relative to the plugin's install/data directory)
	// that contains the python/batch scripts used to pre-process forecast
	// data before the Jasper report is generated.
	private static final String SCRIPTS_DIR = "scripts";

	// Maven artifact path (group/artifact style identifier) used to locate
	// this plugin's resources; also returned by getMavenPath().
	private static final String MAVEN_PATH = "usbr.wat.plugins/usbr-forecast-report";

	// The parent actions window; re-fetched from the ActionPanelPlugin
	// singleton in createSimulationXmlFile() to ensure it is current.
	private ActionsWindow _parent;

	// Embedded Jython interpreter and compiled code, presumably used
	// elsewhere/by the superclass for running python-based report logic
	// in-process rather than via the external script call in
	// runPythonScript(); not directly referenced within this file.
	private PythonInterpreter  _interp;
	private PyCode _pycode;

	/**
	 * Creates the forecast "Create Reports" action, disabled by default,
	 * bound to the given parent actions window.
	 *
	 * @param parent the parent actions window that owns this action
	 */
	public CreateReportsAction(ActionsWindow parent )
	{
		super("Create Reports", null);
		// Disabled until enabled elsewhere once preconditions are met.
		setEnabled(false);
		_parent = parent;
	}

	/**
	 * Runs the full forecast report creation pipeline for the given
	 * simulations and ensemble sets: writes the XML data file, updates the
	 * Jasper data adapter to point at it, runs the external Python
	 * pre-processing script, and finally fills/exports the Jasper report.
	 *
	 * @param sims                 the simulation(s) to report on
	 * @param ensembleReportInfos  the ensemble sets (and member selections) to include in the report
	 * @param options              report output options (e.g. output format)
	 * @return {@code true} if every step of the pipeline succeeded and the
	 *         report was created; {@code false} if any step failed
	 */
	public boolean createReport(List<SimulationReportInfo> sims, List<EnsembleReportInfo> ensembleReportInfos, ReportOptions options)
	{

		WatSimulation sim;
		// Track elapsed time for diagnostic purposes (not currently logged
		// from this variable, but mirrors the timing pattern used elsewhere
		// in this class).
		long t1 = System.currentTimeMillis();
		// Step 1: write the XML data file describing the simulations and
		// ensemble sets; returns null if writing failed or was skipped.
		String xmlFile = createSimulationXmlFile(sims, ensembleReportInfos);
		if ( xmlFile != null )
		{
			// Step 2: point the Jasper data adapter at the simulation
			// folder for the first simulation in the list. Inherited from
			// AbstractReportAction.
			if ( !editDataAdapterFile(sims.get(0).getSimFolder()))
			{
				return false;
			}
			// Step 3: run the external python pre-processing script against
			// the XML file just written.
			if ( runPythonScript(xmlFile))
			{
				// Step 4: compile/fill/export the Jasper report for the
				// first simulation using the requested output options.
				return runJasperReport(sims.get(0), options);
			}
		}
		return false;
	}

	/**
	 * Builds the XML "data adapter" file that describes the simulations
	 * and ensemble sets to be reported on. The file is written into the
	 * simulation's report directory, under a file name derived from the
	 * simulation group's name.
	 * <p>
	 * If the system property {@code SkipForecastReportFile} is set to
	 * {@code true}, the XML file is not actually (re)written; the expected
	 * filename is simply returned as-is (useful for testing/debugging when
	 * an existing XML file should be reused without regenerating it).
	 *
	 * @param sims                the simulations to include in the XML file
	 * @param ensembleReportInfos the ensemble sets to include in the XML file
	 * @return the full path to the (written or pre-existing) XML file, or
	 *         {@code null} if writing the XML file failed
	 */
	private String createSimulationXmlFile(List<SimulationReportInfo> sims, List<EnsembleReportInfo> ensembleReportInfos)
	{
		// Re-fetch the current actions window from the plugin singleton
		// rather than relying on the (possibly stale) _parent field.
		_parent = ActionPanelPlugin.getInstance().getActionsWindow();
		Project prj = Project.getCurrentProject();
		String studyDir = prj.getProjectDirectory();
		String simFolder = sims.get(0).getSimFolder();

		// Build the target XML file path: <simFolder>/<REPORT_DIR>/<simName>forecast.xml
		String filename = RMAIO.concatPath(simFolder, REPORT_DIR);
		filename = RMAIO.concatPath(filename, RMAIO.userNameToFileName(sims.get(0).getName()+"forecast")+".xml");

		// Testing/debugging escape hatch: skip regenerating the file and
		// just report back the filename that would have been used.
		if ( Boolean.getBoolean("SkipForecastReportFile"))
		{
			return filename;
		}

		// Construct the XML writer and populate it with study, simulation,
		// simulation group, and ensemble information before writing it out.
		ForecastReportXmlFile xmlFile = new ForecastReportXmlFile(filename);
		xmlFile.setStudyInfo(studyDir, getObsDataPath(studyDir));

		List<SimulationReportInfo>simList = new ArrayList<>();
		simList.addAll(sims);

		xmlFile.setSimulationInfo(_parent.getForecastPanel().getSimulationGroup().getName(), sims);
		xmlFile.setSimulationGroup(_parent.getForecastPanel().getSimulationGroup());
		xmlFile.setEnsembleInfo(ensembleReportInfos);
		//xmlFile.setEnsembleSetInfo();

		// Actually write the XML file to disk; only return the filename on success.
		if (  xmlFile.createXMLFile() )
		{
			return filename;
		}
		return null;
	}

	/**
	 * Runs the external python (via batch file) pre-processing script that
	 * prepares the forecast-vs-observed data used by the Jasper report.
	 * <p>
	 * The batch file is invoked with the following positional arguments (in
	 * order): the study/watershed directory, the simulation directory, the
	 * model program name (e.g. "ResSim"), the alternative's F-Part, the path
	 * to the observation data within the study, the alternative's name, and
	 * the base simulation name.
	 *
	 * @param sim               the simulation being processed
	 * @param modelAlt          the model alternative (e.g. the ResSim
	 *                          alternative) whose data should be processed
	 * @param baseSimulationName the base/root name of the simulation, used
	 *                          to help locate matching output
	 * @return {@code true} if the external process completed successfully;
	 *         {@code false} otherwise (including when no matching ResSim
	 *         alternative/F-Part could be found)
	 */
	public boolean runPythonScript(WatSimulation sim, ModelAlternative modelAlt, String baseSimulationName)
	{
		// first run the python script through the .bat file
		// bat file needs: 
		// 1. watershed path
		// 2. simulation path
		// 3. model name ... i.e. ResSim
		// 4. alternative's F-Part
		// 5. folder to the observation data in the study
		// 6. alternative's name
		// 7. simulation's base name

		// Track how long the external process takes, logged in the finally block below.
		long t1 = System.currentTimeMillis();
		try
		{
			// Locate the F-Part associated with the ResSim alternative for
			// this simulation/model alternative combination; required by the
			// python script to identify the correct DSS data to process.
			String fpart = findFpartForPython(sim, modelAlt);
			if ( fpart == null )
			{
				System.out.println("createReportAction:no ResSim Alternative found in Simulation "+sim);
				return false;
			}

			// Build up the command line (executable + arguments) to invoke
			// the batch file with the required positional parameters.
			List<String>cmdList = new ArrayList<>();
			String dirToUse = getDirectoryToUse();
			String exeFile = RMAIO.concatPath(dirToUse, PYTHON_REPORT_BAT);
			String studyDir = Project.getCurrentProject().getProjectDirectory();

			//cmdList.add("cmd.exe");
			//cmdList.add("/c");
			cmdList.add(exeFile);
			cmdList.add(studyDir);

			// hack for having a comma in the path and the RMAIO.userNameToFileName() not catching it
			String simDir = sim.getSimulationDirectory();
			simDir = RMAIO.removeChar(simDir, ',');
			cmdList.add(simDir);
			cmdList.add(modelAlt.getProgram());
			cmdList.add(fpart);

			// Path to the observation data used to compare against the forecast.
			String obsPath = getObsDataPath(studyDir);
			cmdList.add(obsPath);
			cmdList.add(modelAlt.getName());
			cmdList.add(baseSimulationName);

			// Launch the external batch/python process and wait for it to complete.
			return runProcess(cmdList, dirToUse);
		}
		finally
		{
			// Always log the elapsed time for this operation, whether it
			// succeeded, failed, or threw an exception.
			long t2 = System.currentTimeMillis();
			System.out.println("runProcess:time to run python for "+sim+" alt "+modelAlt+" is "+(t2-t1)+"ms");
		}
	}

	/**
	 * Compiles (if needed) and fills the Jasper report template for the
	 * given simulation, then exports the filled report to a timestamped PDF
	 * file within the simulation's report directory.
	 *
	 * @param sim     the simulation report info describing the simulation
	 *                whose report is being created
	 * @param options the report output options (used to select/build the exporter)
	 * @return {@code true} if the report was compiled, filled and exported
	 *         successfully; {@code false} if any step failed
	 */
	public boolean runJasperReport(SimulationReportInfo sim, ReportOptions options)
	{
		// Track total elapsed time for the whole Jasper report generation.
		long t1 = System.currentTimeMillis();
		try
		{
			//Log log = LogFactory.getLog(JasperFillManager.class);
			String studyDir = Project.getCurrentProject().getProjectDirectory();
			String simDir = sim.getSimFolder();
			String jasperReportFolder = getJasperRelativeFolder();
			// Directory (within the study) where the compiled Jasper report
			// templates/resources are read from.
			String jasperRepoDir = RMAIO.concatPath(studyDir, REPORT_DIR);
			String rptFile = RMAIO.concatPath(jasperRepoDir, JASPER_FILE);
			String installDir = System.getProperty("user.dir");
			installDir = RMAIO.getDirectoryFromPath(installDir);
			//rptFile = RMAIO.concatPath(rptFile, JASPER_FILE);

			System.out.println("runReportWithOutputFile:report repository:"+jasperRepoDir);

			// Set up a Jasper repository service backed by the study's
			// report folder so relative resource references (e.g.
			// sub-reports, images) in the report template can be resolved.
			SimpleJasperReportsContext context = new SimpleJasperReportsContext();
			FileRepositoryService fileRepository = new FileRepositoryService(context, 
					jasperRepoDir, true);
			context.setExtensions(RepositoryService.class, Collections.singletonList(fileRepository));
			context.setExtensions(PersistenceServiceFactory.class, 
					Collections.singletonList(FileRepositoryPersistenceServiceFactory.getInstance()));
			String inJasperFile = rptFile;

			// Configure Jasper to use the Jaxen-based XPath executor, which
			// is required for XPath expressions used in the report's XML
			// data source queries.
			JRPropertiesUtil.getInstance(context).setProperty("net.sf.jasperreports.xpath.executer.factory",
					"net.sf.jasperreports.engine.util.xml.JaxenXPathExecuterFactory");

			// Ensure the simulation's report output folder exists before
			// attempting to write the generated PDF into it.
			String outputFile = RMAIO.concatPath(simDir, REPORT_DIR);
			RmaFile simDirFile = FileManagerImpl.getFileManager().getFile(outputFile);
			if ( !simDirFile.exists() )
			{
				if ( !simDirFile.mkdirs())
				{
					System.out.println("runJasperReport:failed to create folder "+simDirFile.getAbsolutePath());
				}
			}

			// Compile the Jasper report source files (.jrxml) into their
			// binary (.jasper) form if they have not already been compiled.
			if ( !compileJasperFiles(studyDir, installDir, jasperReportFolder))
			{
				return false;
			}

			// Fill the compiled report template with data (from the XML
			// data adapter produced earlier in the pipeline) and parameters.
			JasperPrint jasperPrint = fillReport(context, studyDir, installDir, jasperReportFolder, sim, options);
			if ( jasperPrint == null )
			{
				return false;
			}	

			long t4 = System.currentTimeMillis();

			// Build the final output file name: <REPORT_DIR>/<JASPER_OUT_FILE><timestamp><ext>
			outputFile = RMAIO.concatPath(outputFile, JASPER_OUT_FILE);

			SimpleDateFormat fmt= new SimpleDateFormat("yyyy.MM.dd-HHmm");

			Date date = new Date();
			outputFile = outputFile.concat(fmt.format(date));
			outputFile = outputFile.concat(REPORT_FILE_EXT);

			// fills compiled report with parameters and a connection
			JRExporter exporter = options.getOutputType().buildExporter(jasperPrint, outputFile);

			try
			{
				// Actually write the filled report out to the target file
				// (PDF or whichever format the exporter was built for).
				exporter.exportReport();
				System.out.println("runJasperReport:comparison report written to "+outputFile);
			}
			catch (JRException e)
			{
				// Export failed; log the stack trace and report failure to the caller.
				e.printStackTrace();
				return false;
			}

			long t5 = System.currentTimeMillis();
			System.out.println("runJasperReport:time to write jasper comparison report for "+sim+ "is "+(t5-t4)+"ms");
			return true;
		}
		finally
		{
			// Always log the total elapsed time for this method, regardless
			// of whether it returned successfully or via an early return.
			long end = System.currentTimeMillis();
			System.out.println("runJasperReport:total time to create jasper comparison report for "+sim+" is "+(end-t1)+"ms");
		}
	}


	/**
	 * Part of the {@link usbr.wat.plugins.actionpanel.actions.AbstractReportAction}
	 * comparison-report contract. This plugin does not support comparison
	 * reports (see {@link #isComparisonReport()}), so this always returns
	 * {@code false} without doing any work.
	 *
	 * @param sris    simulation report infos (unused)
	 * @param options report options (unused)
	 * @return always {@code false}
	 */
	@Override
	public boolean createReport(List<SimulationReportInfo> sris, ReportOptions options)
	{
		return false;
	}

	/**
	 * @return the human-readable name of this report plugin, as shown in the UI
	 */
	@Override
	public String getName()
	{
		return "Forecast Report";
	}
	/**
	 * @return a short human-readable description of this report plugin
	 */
	@Override
	public String getDescription()
	{
		return "Forecast Report for Forecast Simulations";
	}
	/**
	 * @return {@code false}; this plugin does not produce comparison reports
	 */
	@Override
	public boolean isComparisonReport()
	{
		return false;
	}
	/**
	 * @return {@code false}; this plugin does not produce iteration reports
	 */
	@Override
	public boolean isIterationReport()
	{
		return false;
	}

	/**
	 * @return the plugin's display name, same as {@link #getName()}
	 */
	@Override
	public String toString()
	{
		return getName();
	}

	/**
	 * Registers a new instance of this action, bound to the current actions
	 * window, with the global {@link ReportsManager} so it becomes available
	 * as a report plugin elsewhere in the application.
	 */
	public static void register()
	{
		ReportsManager.register(new CreateReportsAction(ActionPanelPlugin.getInstance().getActionsWindow()));
	}

	/**
	 * Simple entry point that schedules {@link #register()} to run on the
	 * Swing event dispatch thread. Primarily useful for manual/standalone
	 * testing of the registration logic.
	 *
	 * @param args unused command-line arguments
	 */
	public static void main(String[] args)
	{
		EventQueue.invokeLater(()->register());
	}

	/**
	 * @return the Maven artifact path used to locate this plugin's resources
	 */
	@Override
	public String getMavenPath()
	{
		return MAVEN_PATH;
	}

	/**
	 * Returns the Swing action that should be used to trigger this plugin's
	 * report-creation UI. In this case, opens the ensemble set selector
	 * dialog rather than immediately creating a report.
	 *
	 * @param parent      the parent actions window
	 * @param parentPanel the panel hosting the action
	 * @return a new {@link DisplayEnsembleSelectorAction} bound to the given parent/panel
	 */
	@Override
	public Action getReportAction(ActionsWindow parent, UsbrPanel parentPanel)
	{
		return new DisplayEnsembleSelectorAction(parent, parentPanel);
	}

	/**
	 * Convenience overload of {@link #createReport(List, List, ReportOptions)}
	 * for the common case of reporting on a single simulation. Wraps the
	 * single {@link SimulationReportInfo} in a one-element list and delegates.
	 *
	 * @param sims                the single simulation to report on
	 * @param ensembleReportInfos the ensemble sets to include in the report
	 * @param options             report output options
	 * @return {@code true} if the report was created successfully; {@code false} otherwise
	 */
	@Override
	public boolean createReport(SimulationReportInfo sims, List<EnsembleReportInfo> ensembleReportInfos, ReportOptions options)
	{
		List<SimulationReportInfo>simInfos = new ArrayList<>();
		simInfos.add(sims);
		return createReport(simInfos, ensembleReportInfos, options);
	}
}

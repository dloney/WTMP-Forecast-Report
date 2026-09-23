/*
 *
 *  * Copyright 2023 United States Bureau of Reclamation (USBR).
 *  * United States Department of the Interior
 *  * All Rights Reserved. USBR PROPRIETARY/CONFIDENTIAL.
 *  * Source may not be released without written approval
 *  * from USBR
 *
 */

package usbr.wat.plugins.forecastreport.editors;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingWorker;
import javax.swing.event.ListSelectionEvent;
import com.rma.io.FileManagerImpl;
import com.rma.model.Project;
import hec.util.AnimatedWaitGlassPane;
import hec2.wat.model.WatSimulation;
import rma.swing.ButtonCmdPanel;
import rma.swing.ButtonCmdPanelListener;
import rma.swing.RmaInsets;
import rma.swing.RmaJComboBox;
import rma.swing.RmaJDialog;
import rma.swing.RmaJTable;
import rma.swing.RmaJTextField;
import rma.swing.list.RmaListModel;
import rma.util.IntVector;
import rma.util.RMAFilenameFilter;
import rma.util.RMAIO;
import usbr.wat.plugins.actionpanel.ActionsWindow;
import usbr.wat.plugins.actionpanel.actions.DisplayReportAction;
import usbr.wat.plugins.actionpanel.actions.forecast.RunForecastSimulationAction;
import usbr.wat.plugins.actionpanel.editors.ReportOptionsPanel;
import usbr.wat.plugins.actionpanel.editors.TemplateWrapper;
import usbr.wat.plugins.actionpanel.io.ReportOptions;
import usbr.wat.plugins.actionpanel.model.ForecastReportingPlugin;
import usbr.wat.plugins.actionpanel.model.ReportPlugin;
import usbr.wat.plugins.actionpanel.model.ReportsManager;
import usbr.wat.plugins.actionpanel.model.SimulationReportInfo;
import usbr.wat.plugins.actionpanel.model.forecast.EnsembleReportInfo;
import usbr.wat.plugins.actionpanel.model.forecast.EnsembleSet;
import usbr.wat.plugins.actionpanel.model.forecast.ForecastSimGroup;
import usbr.wat.plugins.actionpanel.ui.UsbrPanel;


import static rma.swing.ButtonCmdPanel.CLOSE_BUTTON;
import static rma.swing.ButtonCmdPanel.OK_BUTTON;
import static usbr.wat.plugins.actionpanel.editors.DisplayReportsSelector.REPORTS_DIRS;

/**
 * Modal dialog that lets the user select which ensemble sets (and which
 * member simulations within each set) should be included in a forecast
 * report, choose report output options and an optional report template,
 * and then trigger report creation.
 * <p>
 * The dialog displays one row per ensemble set available for the currently
 * selected simulation(s), with checkboxes to select sets, an editable
 * "members to report" column, and a read-only "computed members" column for
 * reference. Buttons allow quickly selecting/clearing all members for the
 * highlighted rows. Once the user clicks "Create Report", report generation
 * is delegated to a {@link ForecastReportingPlugin} and executed on a
 * background thread (via {@link SwingWorker} and an {@link ExecutorService})
 * so the UI remains responsive, with progress shown via an animated glass
 * pane.
 */
public class DisplayForecastReportsSelector extends RmaJDialog
{
	// Column indices for the ensemble table, used throughout this class to
	// read/write specific cells without relying on "magic numbers" everywhere.
	private static final int SELECTION_COL = 0;
	private static final int ENSEMBLE_SET_COL = 1;
	private static final int MEMBERS_TO_REPORT_COL = 2;
	// (Note: the 4th, "Computed Members" column is referenced by its literal
	// index (3) further down rather than via a named constant.)

	// The parent actions window that owns this dialog; used to access the
	// current simulation group and to center/parent dialogs and messages.
	private final ActionsWindow _parent;

	// The panel that launched this dialog; used to fetch the simulations to
	// report on and to display generated reports afterward.
	private final UsbrPanel _parentPanel;

	// Table listing each available ensemble set with a selection checkbox,
	// an editable "members to report" cell, and computed members for reference.
	private RmaJTable _ensembleTable;

	// OK/Close button panel at the bottom of the dialog.
	private ButtonCmdPanel _cmdPanel;
	private JButton _selectAllBtn;
	private JButton _unselectAllBtn;

	// Panel allowing the user to choose report output options (e.g. format).
	private ReportOptionsPanel _optionsPanel;

	// Read-only field showing the name of the simulation being reported on.
	private RmaJTextField _simulationFld;

	// The simulation(s) currently being reported on, populated by fillForm().
	private List<SimulationReportInfo> _sims;

	// Animated "please wait" glass pane shown over the dialog while reports
	// are being generated in the background.
	private AnimatedWaitGlassPane _agp;

	// The dialog's original glass pane, saved/restored around the animated
	// wait glass pane in setGlassPane()/resetGlassPane().
	private Component _glassPane;

	// Combo box allowing the user to pick an optional report CSV template.
	private RmaJComboBox _reportTypeCombo;

	/**
	 * Constructs the dialog (modal to the given parent window), builds its
	 * UI controls, wires up event listeners, and sizes/positions it relative
	 * to the parent. The dialog is not shown automatically; call
	 * {@link #setVisible(boolean)} to display it.
	 *
	 * @param parent      the parent actions window (used as the dialog's owner)
	 * @param parentPanel the panel that is launching this dialog
	 */
	public DisplayForecastReportsSelector(ActionsWindow parent, UsbrPanel parentPanel)
	{
		super(parent, true);
		_parent =parent;
		_parentPanel = parentPanel;
		// Build all Swing components and lay them out.
		buildControls();
		// Wire up button/table event handlers.
		addListeners();
		pack();
		// Force a specific initial size (overriding whatever pack() computed).
		setSize(600,400);
		setLocationRelativeTo(getParent());
	}

	/**
	 * Creates and lays out every Swing control used by this dialog: the
	 * simulation name field, the ensemble set table (with select/clear
	 * buttons), the report options panel, the report template combo box,
	 * and the OK/Close button panel. Uses {@link GridBagLayout} throughout.
	 */
	private void buildControls()
	{
		// Require an explicit action (via the OK/Close buttons) to close the
		// dialog, rather than allowing the window manager's close button to
		// dispose it directly.
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

		getContentPane().setLayout(new GridBagLayout());
		setTitle("Select Ensemble Sets to Report on");

		// --- "Simulation:" label + read-only text field showing its name ---
		JLabel label = new JLabel("Simulation:");
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = 1;
		gbc.weightx = 0.0;
		gbc.weighty = 0.0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.fill = GridBagConstraints.NONE;
		gbc.insets = RmaInsets.INSETS5505;
		add(label, gbc);

		_simulationFld = new RmaJTextField();
		_simulationFld.setEditable(false);
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = GridBagConstraints.REMAINDER;
		gbc.weightx = 1.0;
		gbc.weighty = 0.0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = RmaInsets.INSETS5505;
		add(_simulationFld, gbc);

		// --- Ensemble set table: Select checkbox, Ensemble Set name, editable
		// "Members for Report" text, and read-only "Computed Members" ---
		String[] headers = new String[]{"Select", "Ensemble Set",  "Members for Report ", "Computed Members"};
		_ensembleTable = new RmaJTable(this, headers)
		{
			// Only the "Select" checkbox column (0) and the "Members for
			// Report" column (2) are user-editable; the ensemble set name
			// and computed members columns are display-only.
			public boolean isCellEditable(int row, int column)
			{
				return column == 0 || column == 2;
			}

		};
		// Render/edit column 0 as a checkbox rather than plain text.
		_ensembleTable.setCheckBoxCellEditor(0);
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = GridBagConstraints.REMAINDER;
		gbc.weightx = 1.0;
		gbc.weighty = 1.0;
		gbc.anchor = GridBagConstraints.NORTHWEST;
		gbc.fill = GridBagConstraints.BOTH;
		gbc.insets = RmaInsets.INSETS5505;
		add(_ensembleTable.getScrollPane(), gbc);

		// --- "Select All" / "Clear All" buttons, laid out in their own sub-panel ---
		JPanel buttonPanel = new JPanel(new GridBagLayout());
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = GridBagConstraints.REMAINDER;
		gbc.weightx = 0.0;
		gbc.weighty = 0.0;
		gbc.anchor = GridBagConstraints.NORTH;
		gbc.fill = GridBagConstraints.NONE;
		gbc.insets = RmaInsets.INSETS5505;
		add(buttonPanel, gbc);

		_selectAllBtn = new JButton("Select All");
		_selectAllBtn.setToolTipText("Select All the Ensemble Members for the selected table rows");
		// Disabled until at least one table row is selected (see tableRowSelected()).
		_selectAllBtn.setEnabled(false);
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = 1;
		gbc.weightx = 0.0;
		gbc.weighty = 0.0;
		gbc.anchor = GridBagConstraints.NORTHWEST;
		gbc.fill = GridBagConstraints.NONE;
		gbc.insets = RmaInsets.INSETS5505;
		buttonPanel.add(_selectAllBtn, gbc);

		_unselectAllBtn = new JButton("Clear All");
		// NOTE: this tooltip is set on _selectAllBtn again rather than
		// _unselectAllBtn; preserved as-is (behavior unchanged), but appears
		// to be a pre-existing copy/paste slip in the original code.
		_selectAllBtn.setToolTipText("Clear All the Ensemble Members for the selected table rows");
		_unselectAllBtn.setEnabled(false);
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = GridBagConstraints.REMAINDER;
		gbc.weightx = 0.0;
		gbc.weighty = 0.0;
		gbc.anchor = GridBagConstraints.NORTHWEST;
		gbc.fill = GridBagConstraints.NONE;
		gbc.insets = RmaInsets.INSETS5505;
		buttonPanel.add(_unselectAllBtn, gbc);

		// --- Report options panel (output format, etc.) ---
		_optionsPanel = new ReportOptionsPanel();
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = 1;
		gbc.weightx = 1.0;
		gbc.weighty = 0.0;
		gbc.anchor = GridBagConstraints.NORTHWEST;
		gbc.fill = GridBagConstraints.NONE;
		gbc.insets = RmaInsets.INSETS5505;
		add(_optionsPanel, gbc);

		// --- "Report Type:" label + combo box of available report CSV templates ---
		label = new JLabel("Report Type:");
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = 1;
		gbc.weightx = 0.0;
		gbc.weighty = 0.0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.fill = GridBagConstraints.NONE;
		gbc.insets = RmaInsets.INSETS0505;
		add(label, gbc);

		_reportTypeCombo = new RmaJComboBox<>();
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = GridBagConstraints.REMAINDER;
		gbc.weightx = 1.0;
		gbc.anchor = GridBagConstraints.WEST;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = RmaInsets.INSETS0505;
		add(_reportTypeCombo, gbc);

		// --- OK ("Create Report") / Close command button panel ---
		_cmdPanel = new ButtonCmdPanel(ButtonCmdPanel.OK_BUTTON| CLOSE_BUTTON);
		JButton okBtn = _cmdPanel.getButton(OK_BUTTON);
		// Relabel the generic "OK" button to describe what it actually does.
		okBtn.setText("Create Report");
		gbc.gridx = GridBagConstraints.RELATIVE;
		gbc.gridy = GridBagConstraints.RELATIVE;
		gbc.gridwidth = GridBagConstraints.REMAINDER;
		gbc.weightx = 1.0;
		gbc.weighty = 0.0;
		gbc.anchor = GridBagConstraints.NORTHWEST;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		gbc.insets = RmaInsets.INSETS5555;
		add(_cmdPanel, gbc);
	}

	/**
	 * Registers all event listeners for this dialog's interactive controls:
	 * table row selection (to enable/disable the select/clear buttons), the
	 * "Select All" / "Clear All" buttons, and the OK/Close command buttons.
	 */
	private void addListeners()
	{
		_ensembleTable.getSelectionModel().addListSelectionListener(e->tableRowSelected(e));
		_selectAllBtn.addActionListener(e->selectAllAction());
		_unselectAllBtn.addActionListener(e->unselectAllAction());
		_cmdPanel.addCmdPanelListener(new ButtonCmdPanelListener()
		{
			public void buttonCmdActionPerformed(ActionEvent e)
			{
				// Dispatch based on which command button was pressed.
				switch (e.getID())
				{
					case OK_BUTTON:
						createReport();
						break;
					case CLOSE_BUTTON:
						setVisible(false);
						break;
				}
			}
		});
	}

	/**
	 * Enables/disables the "Select All" and "Clear All" buttons based on
	 * whether any row in the ensemble table is currently selected.
	 *
	 * @param e the list selection event from the table's selection model
	 */
	private void tableRowSelected(ListSelectionEvent e)
	{
		// Ignore intermediate events fired while the selection is still
		// being adjusted (e.g. during a drag-select).
		if ( e.getValueIsAdjusting())
		{
			return;
		}
		int row = _ensembleTable.getSelectedRow();
		boolean enabled = row > -1;

		_selectAllBtn.setEnabled(enabled);
		_unselectAllBtn.setEnabled(enabled);
	}

	/**
	 * Clears the "Members for Report" cell (column 2) for every currently
	 * selected row in the ensemble table. No-op if no rows are selected.
	 */
	private void unselectAllAction()
	{
		int[] rows = _ensembleTable.getSelectedRows();
		if ( rows == null || rows.length == 0 )
		{
			return;
		}
		// Blank out the "members to report" cell for each selected row.
		for (int r = 0;r < rows.length;r++)
		{
			_ensembleTable.setValueAt("", r, 2);
		}
	}

	/**
	 * Copies the "Computed Members" value (column 3) into the "Members for
	 * Report" cell (column 2) for every currently selected row in the
	 * ensemble table, effectively selecting every computed member for
	 * reporting. No-op if no rows are selected.
	 */
	private void selectAllAction()
	{
		int[] rows = _ensembleTable.getSelectedRows();
		if ( rows == null || rows.length == 0 )
		{
			return;
		}
		// Copy the read-only "computed members" value into the editable
		// "members to report" column for each selected row.
		for (int r = 0;r < rows.length;r++)
		{
			Object obj = _ensembleTable.getValueAt(r, 3);
			_ensembleTable.setValueAt(obj, r, 2);
		}
	}

	/**
	 * Shows or hides this dialog. Overridden so that, before the dialog is
	 * actually made visible, the current simulation selection is validated
	 * (see {@link #checkSims()}); if validation fails, the dialog is not
	 * shown at all.
	 *
	 * @param visible {@code true} to show the dialog, {@code false} to hide it
	 */
	public void setVisible(boolean visible)
	{
		if ( visible )
		{
			// Abort showing the dialog if there is no valid simulation
			// selection to report on (checkSims() also shows the
			// appropriate warning message to the user in that case).
			if ( !checkSims())
			{
				return;
			}
		}
		super.setVisible(visible);
		if ( !visible)
		{
			//saveSelectedReports();
		}
	}

	/**
	 * Validates that there is a usable simulation group and at least one
	 * simulation with results to report on, showing an informational dialog
	 * and returning {@code false} if not. Simulations without results
	 * (i.e. whose simulation folder does not exist) are silently removed
	 * from the list before it is used to populate the form.
	 *
	 * @return {@code true} if there is at least one valid simulation to
	 *         report on and the form was populated; {@code false} otherwise
	 */
	private boolean checkSims()
	{
		// A simulation group must be selected before reports can be created.
		if ( _parent.getSimulationGroup() == null )
		{
			JOptionPane.showMessageDialog(_parent,"Please create or select a Simulation Group first",
					"No Simulation Group Selected", JOptionPane.INFORMATION_MESSAGE);
			return false;

		}

		List<SimulationReportInfo> sims = _parentPanel.getSimulationReportInfos();

		SimulationReportInfo sri;
		String folder;

		// Remove any simulations that don't actually have results on disk
		// (i.e. their simulation folder doesn't exist), warning the user for
		// each one removed.
		Iterator<SimulationReportInfo> iter = sims.iterator();
		while ( iter.hasNext())
		{
			sri = iter.next();
			folder = sri.getSimFolder();
			if ( !FileManagerImpl.getFileManager().fileExists(folder))
			{
				final SimulationReportInfo fSri = sri;
				// Defer the message dialog to the event queue so it doesn't
				// block iteration/removal from the list.
				EventQueue.invokeLater(()->JOptionPane.showMessageDialog(_parent, "Simulation "+fSri.getSimulation().getName()
						+" has no results so no report can be created for it.","No Results", JOptionPane.INFORMATION_MESSAGE));
				iter.remove();
			}
		}

		// If nothing is left after filtering, there's nothing to report on.
		if ( sims.isEmpty())
		{
			EventQueue.invokeLater(()->JOptionPane.showMessageDialog(_parent,"There are no Simulations with results selected to create reports for",
					"No Simulations Selected", JOptionPane.INFORMATION_MESSAGE));
			return false;
		}
		// Populate the table/fields with the remaining valid simulations.
		fillForm(sims);
		return true;
	}

	/**
	 * Populates the ensemble table with one row per ensemble set available
	 * for the given simulations, and refreshes the report template combo
	 * box for the current forecast reporting plugin. Also updates the
	 * "Simulation:" field to show the name of the last simulation processed.
	 *
	 * @param sims the simulations to populate the form for
	 */
	private void fillForm(List<SimulationReportInfo> sims)
	{
		// Clear any previously displayed rows before repopulating.
		_ensembleTable.deleteCells();
		boolean canBeComparisionReport = sims.size()>1;
		ReportPlugin plugin;
		ForecastSimGroup simGroup = (ForecastSimGroup) _parent.getSimulationGroup();
		Vector<Object> row;
		_sims = sims;
		// For every simulation, add one table row per ensemble set defined
		// for that simulation within the forecast simulation group.
		for(int s = 0;s < sims.size();s++ )
		{
			SimulationReportInfo sim = sims.get(s);
			WatSimulation simulation = sim.getSimulation();
			// NOTE: this overwrites _simulationFld on every iteration, so
			// only the last simulation's name will end up displayed if more
			// than one simulation is present.
			_simulationFld.setText(simulation.getName());
			List<EnsembleSet> esets = simGroup.getEnsembleSets(simulation);
			for (int e = 0;e < esets.size(); e++ )
			{
				row = new Vector<>();
				// Column 0: selection checkbox, initially unchecked.
				row.add(Boolean.FALSE);
				EnsembleSet eset = esets.get(e);
				// Column 1: the ensemble set itself (its toString()/renderer
				// presumably shows its name).
				row.add(eset);
				// Column 2: "members to report" text, initially blank.
				row.add("");
				// Column 3: computed members, formatted as a sorted,
				// comma-separated list with the surrounding brackets removed.
				IntVector members = eset.getComputedMembers();
				int[] membersArray = members.toArray();
				Arrays.sort(membersArray);
				String str = Arrays.toString(membersArray);
				str = RMAIO.removeChar(str, ']');
				str = RMAIO.removeChar(str, '[');
				row.add(str);

				_ensembleTable.appendRow(row);

			}
		}
		// Refresh the report template combo box based on the current
		// forecast reporting plugin (if any).
		fillReportTemplateCombo(getForecastReportingPlugin());
		//updateCreateReportButtonState();
	}

	/**
	 * Looks up and returns the first registered {@link ReportPlugin} that
	 * also implements {@link ForecastReportingPlugin}.
	 *
	 * @return the first forecast reporting plugin found among the
	 *         registered {@link ReportsManager} plugins, or {@code null} if
	 *         none is registered
	 */
	public ForecastReportingPlugin getForecastReportingPlugin()
	{
		List<ReportPlugin> plugins = ReportsManager.getPlugins();
		for (int i = 0;i < plugins.size(); i++ )
		{
			if ( plugins.get(i) instanceof ForecastReportingPlugin )
			{
				return (ForecastReportingPlugin) plugins.get(i);
			}
		}
		return null;
	}

	/**
	 * Builds the list of {@link EnsembleReportInfo} entries from the current
	 * state of the ensemble table: one entry per row whose "Select" checkbox
	 * is checked and whose "Members for Report" cell parses to a non-empty
	 * set of member indices.
	 *
	 * @return the ensemble report info entries corresponding to the
	 *         currently selected/configured table rows (may be empty, but
	 *         never {@code null})
	 */
	private List<EnsembleReportInfo> getEnsembleInfos()
	{
		int rows = _ensembleTable.getNumRows();
		Object obj;
		List<EnsembleReportInfo>eInfoList = new ArrayList<>();
		// Walk every row in the table looking for ones that are both
		// selected and have a non-empty parsed member set.
		for (int r = 0;r < rows; r++ )
		{
			obj = _ensembleTable.getValueAt(r, SELECTION_COL);
			if ( isSelected(obj))
			{
				int [] memberSet = getMembers((String)_ensembleTable.getValueAt(r, MEMBERS_TO_REPORT_COL));
				if ( memberSet != null && memberSet.length > 0 )
				{
					EnsembleReportInfo eInfo = new EnsembleReportInfo((EnsembleSet)_ensembleTable.getValueAt(r,ENSEMBLE_SET_COL),memberSet);
					eInfoList.add(eInfo);
				}
			}
		}
		return eInfoList;
	}

	/**
	 * Parses a comma-separated string of ensemble member indices (as
	 * displayed/edited in the "Members for Report" table column) into an
	 * array of ints. Delegates the actual parsing to
	 * {@link RunForecastSimulationAction#getIntegerSet(String)}.
	 *
	 * @param memberSet the comma-separated member index string to parse
	 * @return the parsed array of member indices (format/edge-case behavior
	 *         defined by the delegate)
	 */
	private int[] getMembers(String memberSet)
	{
		return RunForecastSimulationAction.getIntegerSet(memberSet);
	}

	/**
	 * Interprets a table cell value from the "Select" column as a boolean:
	 * {@code true} if it is {@link Boolean#TRUE} or a string that parses to
	 * {@code true} (see {@link RMAIO#parseBoolean(String, boolean)}).
	 *
	 * @param obj the cell value to interpret
	 * @return {@code true} if the value represents a selected/checked state
	 */
	private boolean isSelected(Object obj)
	{
		return  obj != null && (obj == Boolean.TRUE || RMAIO.parseBoolean(obj.toString(), false));
	}

	/**
	 * Handles the "Create Report" button: validates that a forecast
	 * reporting plugin exists and that at least one ensemble set is
	 * selected with members to report on, then kicks off report generation
	 * on a background thread pool (via a {@link SwingWorker}) so the UI
	 * remains responsive. An animated "please wait" glass pane is shown for
	 * the duration, and a success/failure dialog is shown once generation
	 * completes.
	 *
	 * @return {@code true} once background report generation has been
	 *         successfully kicked off (this does not reflect whether the
	 *         report generation itself ultimately succeeds — that is
	 *         reported asynchronously via dialogs); {@code false} if
	 *         validation failed before generation could start
	 */
	private boolean createReport()
	{
		// Commit any in-progress cell edit (e.g. a "members to report" field
		// the user was still typing in) before reading the table's state.
		_ensembleTable.commitEdit(true);

		// Find the (first) registered plugin that can actually create
		// forecast reports; without one, there's nothing to do.
		List<ReportPlugin> plugins = ReportsManager.getPlugins();
		ForecastReportingPlugin plugin = null;
		for (int i = 0;i < plugins.size(); i++ )
		{
			if ( plugins.get(i) instanceof ForecastReportingPlugin )
			{
				plugin = (ForecastReportingPlugin) plugins.get(i);
				break;
			}
		}
		if ( plugin == null )
		{
			JOptionPane.showMessageDialog(this, "No Forecast Reporting Plugin found to create report with.", "Missing Plugin", JOptionPane.PLAIN_MESSAGE);
			return false;
		}
		// Captured as final so it can be referenced from the inner
		// SwingWorker/Callable classes below.
		final ForecastReportingPlugin fPlugin = plugin;
		List<EnsembleReportInfo> ensembleInfos = getEnsembleInfos();
		if ( ensembleInfos.size() == 0 )
		{
			JOptionPane.showMessageDialog(this, "No Ensemble Sets selected to report on, or no members entered.", "Nothing to Report on", JOptionPane.PLAIN_MESSAGE);
			return false;
		}
		// Show the animated "please wait" overlay while the background work runs.
		_agp = new AnimatedWaitGlassPane();
		_agp.setTransparency(0.8f);
		setGlassPane("Creating Reports...");
		try
		{
			// Single-threaded pool: reports are generated one at a time even
			// though the infrastructure below (Futures/loops) is written to
			// support running several concurrently.
			int maxThreads = 1;
			ExecutorService threadPool = Executors.newFixedThreadPool(maxThreads);

			// Background worker that submits report-creation task(s) to the
			// thread pool, waits for them to finish, and publishes results
			// back to the EDT via process()/done() for UI updates.
			SwingWorker<Void, ReportCreator> worker = new SwingWorker<Void, ReportCreator>()
			{
				// Tracks whether every submitted report succeeded.
				private boolean _successful = true;
				// Records the first failed report, used in the failure dialog message.
				private ReportCreator _failedReport;

				@Override
				public Void doInBackground()
				{
					// If the user picked a specific report template from the
					// combo box (index 0 is the "no template" placeholder),
					// apply it to the simulation before generating the report.
					if ( _reportTypeCombo.getSelectedIndex() > 0 )
					{
						TemplateWrapper wrapper = (TemplateWrapper) _reportTypeCombo.getSelectedItem();
						_sims.get(0).setReportCsvFile(wrapper.getPath());
					}
					// Submit the (single) report creation task to the thread pool.
					Future<ReportCreator> future = createReport(fPlugin, _sims.get(0), ensembleInfos);

					List<Future<ReportCreator>>futures = new ArrayList<>();

					if ( future != null )
					{
						futures.add(future);
					}

					// Wait for every submitted task to complete and publish
					// each result so process() can update the UI/state.
					ReportCreator rv;
					for (int i = 0;i < futures.size(); i++ )
					{
						try
						{
							rv = futures.get(i).get();
							publish(rv);
						}
						catch (InterruptedException | ExecutionException e)
						{
							e.printStackTrace();
						}
					}

					return null;
				}

				/**
				 * Wraps the given report plugin/simulation/ensemble info in
				 * a {@link ReportCreator} task and submits it to the thread
				 * pool for background execution.
				 *
				 * @param reportPlugin the plugin to use to create the report
				 * @param sris         the simulation to report on
				 * @param esetInfo     the ensemble sets to include
				 * @return a {@link Future} representing the submitted task
				 */
				private Future<ReportCreator> createReport(ForecastReportingPlugin reportPlugin, SimulationReportInfo sris, List<EnsembleReportInfo>esetInfo)
				{
					ReportCreator rc = new ReportCreator(reportPlugin, sris, esetInfo);
					Future<ReportCreator> future = threadPool.submit(rc);
					return future;
				}

				@Override
				public void process(List<ReportCreator> chunks)
				{
					// Called on the EDT as results are published; update the
					// glass pane message and track overall success/failure.
					if ( chunks == null || chunks.isEmpty())
					{
						return;
					}
					ReportCreator rv;
					for (int i = 0;i < chunks.size(); i++ )
					{
						rv = chunks.get(i);
						if ( rv != null )
						{
							if ( !rv.wasReportSuccessFul())
							{
								_agp.setMessage("Failed to create report "+rv.getReportPlugin().getName());
								_successful = false;
								_failedReport = rv;
							}
						}
						else
						{
							// A null result indicates a task-level failure
							// with no ReportCreator to report details from.
							_agp.setMessage("Failed to create report ");
							_successful = false;
							_failedReport = rv;
						}
					}
				}

				@Override
				public void done()
				{
					// Runs on the EDT once doInBackground() has fully
					// completed; finalize the glass pane and show the
					// appropriate success/failure dialog to the user.
					_agp.setMessage("Reports Complete");
					try
					{
						// Give the thread pool a short grace period to fully
						// wind down before tearing down the UI overlay.
						threadPool.awaitTermination(5, TimeUnit.SECONDS);
					}
					catch (InterruptedException e)
					{
					}
					try
					{

					}
					finally
					{
						resetGlassPane();
					}

					if ( _successful )
					{
						// Offer the user the option to immediately view the
						// generated report(s) or simply close the dialog.
						int opt = JOptionPane.showOptionDialog(DisplayForecastReportsSelector.this, "Reports Created Successfully",
								"Complete",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE,
								null, new Object[] {"Close", "Display Reports"}, "Close");
						if ( opt == 1 )
						{
							DisplayReportAction action = new DisplayReportAction(_parentPanel);
							action.displayReportAction();
						}
					}
					else
					{
						JOptionPane.showMessageDialog(DisplayForecastReportsSelector.this,
								"Failed to create report for "+_failedReport._reportPlugin.getName(),
								"Report Failed", JOptionPane.INFORMATION_MESSAGE);
					}
				}
			};
			// Kick off the background worker (schedules doInBackground() on
			// a worker thread).
			worker.execute();

		}
		catch (Exception e )
		{
			// Catch-all: log and reset the glass pane so the dialog doesn't
			// get stuck showing the "please wait" overlay if something in
			// the setup above throws unexpectedly.
			Logger.getLogger(DisplayForecastReportsSelector.class.getName()).warning("Exception running reports " + e);
			resetGlassPane();
		}
		finally
		{

		}

		return true;
	}

	/**
	 * Replaces the dialog's current glass pane with an animated "please
	 * wait" overlay displaying the given message, saving the previous glass
	 * pane so it can be restored later via {@link #resetGlassPane()}.
	 *
	 * @param msg the status message to display on the wait overlay
	 */
	public void setGlassPane(String msg)
	{
		// Remember the current glass pane so it can be restored afterward.
		_glassPane = getGlassPane();
		_agp = new AnimatedWaitGlassPane();
		_agp.setColor(Color.BLACK);
		setGlassPane(_agp);
		_agp.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		_agp.setMessage(msg);
		_agp.setActive(true);
		_agp.setVisible(true);

	}

	/**
	 * Restores the dialog's glass pane to whatever it was before
	 * {@link #setGlassPane(String)} was called, deactivating and hiding the
	 * animated wait overlay first. No-op if there is no active wait overlay.
	 */
	public void resetGlassPane()
	{
		if(_agp != null && _glassPane != null)
		{
			_agp.setCursor(Cursor.getDefaultCursor());
			_agp.setActive(false);
			_agp.setVisible(false);
			setGlassPane(_glassPane);
			_agp = null;
			_glassPane = null;
		}
	}

	/**
	 * Background task (run on the dialog's thread pool) that invokes the
	 * forecast reporting plugin's {@code createReport(...)} method for a
	 * single simulation and set of ensemble infos, capturing whether it
	 * succeeded so the result can be published back to the EDT.
	 */
	public class ReportCreator
			implements Callable
	{

		// The ensemble sets/members this task should report on.
		private final List<EnsembleReportInfo> _esetInfo;

		// The plugin used to actually perform report creation.
		ForecastReportingPlugin _reportPlugin;

		// Result flag set once call() has finished; read via wasReportSuccessFul().
		private boolean _reportRv;

		// The simulation being reported on by this task.
		private SimulationReportInfo _sris;

		/**
		 * Creates a report-creation task bound to the given plugin,
		 * simulation, and ensemble set information.
		 *
		 * @param reportPlugin the plugin used to create the report
		 * @param sris         the simulation to report on
		 * @param esetInfo     the ensemble sets/members to include
		 */
		public ReportCreator(ForecastReportingPlugin reportPlugin, SimulationReportInfo sris, List<EnsembleReportInfo> esetInfo)
		{
			super();
			_reportPlugin = reportPlugin;
			_sris = sris;
			_esetInfo = esetInfo;
		}

		/**
		 * Executes the report creation for this task's simulation/ensemble
		 * info using the current report options from {@link #_optionsPanel}.
		 * Any exception thrown by the plugin is caught, logged, and treated
		 * as a failed report rather than propagated.
		 *
		 * @return this {@link ReportCreator} instance (so the caller can
		 *         inspect {@link #wasReportSuccessFul()} and
		 *         {@link #getReportPlugin()} on the result)
		 * @throws Exception never thrown directly by this implementation
		 *                    (declared by the {@link Callable} interface)
		 */
		@Override
		public Object call() throws Exception
		{
			_agp.setMessage("Creating report for "+_reportPlugin.getName());

			ReportOptions options = _optionsPanel.getReportOptions();
			try
			{
				_reportRv = _reportPlugin.createReport(_sris, _esetInfo, options);
			}
			catch ( Exception e )
			{
				// Treat any exception from the plugin as a failed report
				// rather than letting it propagate and abort the whole
				// background worker.
				Logger.getLogger(DisplayForecastReportsSelector.class.getName()).info("Failed to run report "+_reportPlugin.getName()
						+" Error:"+e);
				e.printStackTrace();
				_reportRv = false;
			}
			return this;
		}

		/**
		 * @return {@code true} if the report plugin reported success for this task
		 */
		public boolean wasReportSuccessFul()
		{
			return _reportRv;
		}

		/**
		 * @return the report plugin used by this task
		 */
		public ReportPlugin getReportPlugin()
		{
			return _reportPlugin;
		}

	}

	/**
	 * Refreshes the report template combo box with the list of available
	 * report CSV template files for the given plugin, found by looking in
	 * the study's reports directory under a sub-folder named after the
	 * plugin. An empty/blank entry is always inserted first so the user can
	 * choose "no template".
	 *
	 * @param selectedPlugin the plugin whose available templates should be
	 *                       listed; if {@code null}, the combo box is left
	 *                       unchanged
	 */
	private void fillReportTemplateCombo(ReportPlugin selectedPlugin)
	{
		if (selectedPlugin == null )
		{
			return;
		}

		String prjDir = Project.getCurrentProject().getProjectDirectory();
		// Build the path: <projectDir>/<REPORTS_DIRS>/<plugin name, file-safe>
		String dir = RMAIO.concatPath(prjDir, REPORTS_DIRS);
		dir = RMAIO.concatPath(dir, RMAIO.userNameToFileName(selectedPlugin.getName()));

		// Find every .csv file in that directory; these represent available
		// report templates for the selected plugin.
		List<String> templates = FileManagerImpl.getFileManager().list(dir, new RMAFilenameFilter("csv", "report CSV files"));
		if ( templates != null )
		{
			// Wrap each template file path so it can be displayed/selected
			// via the combo box, and prepend an empty "no template" option.
			List<TemplateWrapper> templatesList = templates.stream().map(e -> new TemplateWrapper(e)).collect(Collectors.toList());
			Vector<TemplateWrapper> vec = new Vector<>(templatesList);
			TemplateWrapper emptyWrapper = new TemplateWrapper("");
			vec.add(0, emptyWrapper);
			_reportTypeCombo.setModel(new RmaListModel(true,vec ));
		}
	}

}

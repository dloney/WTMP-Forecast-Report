/*
 *
 *  * Copyright 2023 United States Bureau of Reclamation (USBR).
 *  * United States Department of the Interior
 *  * All Rights Reserved. USBR PROPRIETARY/CONFIDENTIAL.
 *  * Source may not be released without written approval
 *  * from USBR
 *
 */

package usbr.wat.plugins.forecastreport.actions;

import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import usbr.wat.plugins.actionpanel.ActionsWindow;
import usbr.wat.plugins.actionpanel.ui.UsbrPanel;
import usbr.wat.plugins.forecastreport.editors.DisplayForecastReportsSelector;

/**
 * Swing {@link javax.swing.Action} that opens (and lazily creates) the
 * {@link DisplayForecastReportsSelector} dialog, which lets the user pick
 * which ensemble sets/members a forecast report should be generated for.
 * <p>
 * This action is disabled by default (see the constructor) and is expected
 * to be enabled elsewhere in the application once the preconditions for
 * displaying the selector (e.g. a valid simulation group/selection) are met.
 */
public class DisplayEnsembleSelectorAction extends AbstractAction
{
	// The main forecast/actions window that owns this action; kept so it can
	// be passed along to the selector dialog that this action displays.
	private final ActionsWindow _parent;

	// The UsbrPanel that triggered/hosts this action; also forwarded to the
	// selector dialog so it can interact with the panel's state.
	private final UsbrPanel _parentPanel;

	// The forecast report selector dialog. Created lazily (on first use) in
	// displayReportSelector() and then reused for subsequent invocations.
	private DisplayForecastReportsSelector _selector;

	/**
	 * Creates the action with the label "Create Report..." and disables it
	 * by default. The caller is responsible for enabling this action once
	 * the application state allows a report to be created.
	 *
	 * @param parent      the parent actions window, forwarded to the selector dialog
	 * @param parentPanel the panel that owns/hosts this action
	 */
	public DisplayEnsembleSelectorAction(ActionsWindow parent, UsbrPanel parentPanel)
	{
		// Sets the menu/button text shown to the user for this action.
		super("Create Report...");
		// Disabled until the rest of the application decides it is safe to run.
		setEnabled(false);

		_parent = parent;
		_parentPanel = parentPanel;
	}

	/**
	 * Invoked by Swing when the user triggers this action (e.g. clicking a
	 * button or menu item bound to it). Simply delegates to
	 * {@link #displayReportSelector()}.
	 *
	 * @param e the Swing action event (unused)
	 */
	@Override
	public void actionPerformed(ActionEvent e)
	{
		displayReportSelector();
	}

	/**
	 * Lazily instantiates the {@link DisplayForecastReportsSelector} dialog
	 * (only the first time this method is called) and then makes it visible.
	 * Reusing the same dialog instance on subsequent calls avoids rebuilding
	 * the UI each time the action is triggered.
	 */
	private void displayReportSelector()
	{
		// Only build the dialog once; reuse it on subsequent calls.
		if ( _selector == null )
		{
			_selector = new DisplayForecastReportsSelector(_parent,_parentPanel);
		}
		// Show (or re-show) the dialog to the user.
		_selector.setVisible(true);
	}

}

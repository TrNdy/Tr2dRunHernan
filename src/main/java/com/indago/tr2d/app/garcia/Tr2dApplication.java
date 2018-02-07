/**
 *
 */
package com.indago.tr2d.app.garcia;

import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.WindowConstants;

import com.indago.gurobi.GurobiInstaller;
import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.scijava.Context;
import org.scijava.app.StatusService;
import org.scijava.io.IOService;
import org.scijava.log.LogService;

import com.apple.eawt.Application;
import com.indago.tr2d.Tr2dContext;
import com.indago.tr2d.io.projectfolder.Tr2dProjectFolder;
import com.indago.tr2d.plugins.seg.Tr2dSegmentationPluginService;
import com.indago.tr2d.ui.model.Tr2dModel;
import com.indago.tr2d.ui.util.FrameProperties;
import com.indago.tr2d.ui.util.UniversalFileChooser;
import com.indago.tr2d.ui.view.Tr2dMainPanel;
import com.indago.util.OSValidator;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import io.scif.codec.CodecService;
import io.scif.formats.qt.QTJavaService;
import io.scif.formats.tiff.TiffService;
import io.scif.img.ImgUtilityService;
import io.scif.services.DatasetIOService;
import io.scif.services.FormatService;
import io.scif.services.JAIIIOService;
import io.scif.services.LocationService;
import io.scif.services.TranslatorService;
import net.imagej.DatasetService;
import net.imagej.ops.OpMatchingService;
import net.imagej.ops.OpService;
import org.scijava.log.Logger;
import weka.gui.ExtensionFileFilter;

/**
 * Starts the tr2d app.
 *
 * @author jug
 */
public class Tr2dApplication {

	/**
	 * true, iff this app is not started by the imagej2/fiji plugin (tr2d_)
	 */
	private final boolean isStandalone;

	private JFrame guiFrame;
	private Tr2dMainPanel mainPanel;

	private File inputStack;
	private Tr2dProjectFolder projectFolder;

	private File exportFolder;
	private int minTime = 0;
	private int maxTime = Integer.MAX_VALUE;

	private boolean autoRun = false;

	private final OpService ops;
	private final Tr2dSegmentationPluginService segPlugins;

	private final Logger log;

	public static void main( final String[] args ) {
		new Tr2dApplication().run(args);
	}

	public Tr2dApplication() {
		isStandalone = true;
		final ImageJ temp = IJ.getInstance();
		if ( temp == null ) {
			new ImageJ();
		}

		final Context context = new Context( FormatService.class, OpService.class, OpMatchingService.class,
				IOService.class, DatasetIOService.class, LocationService.class, DatasetService.class,
				ImgUtilityService.class, StatusService.class, TranslatorService.class, QTJavaService.class,
				TiffService.class, CodecService.class, JAIIIOService.class, LogService.class, Tr2dSegmentationPluginService.class );
		ops = context.getService( OpService.class );
		segPlugins = context.getService( Tr2dSegmentationPluginService.class );
		log = context.getService( LogService.class ).subLogger("tr2d");
		log.info( "STANDALONE" );
	}

	public Tr2dApplication( OpService opService, Tr2dSegmentationPluginService tr2dSegmentationPluginService, Logger log )
	{
		isStandalone = false;
		if(tr2dSegmentationPluginService == null)
			log.error( "Tr2dPlugin failed to set the Tr2dSegmentationPluginService!" );
		ops = opService;
		segPlugins = tr2dSegmentationPluginService;
		this.log = log;
		log.info( "PLUGIN" );
	}

	public void run( final String[] args ) {

		System.setProperty( "apple.laf.useScreenMenuBar", "true" );

		// GET THE APP SPECIFIC LOGGER
		// ---------------------------

		checkGurobiAvailability();
		parseCommandLineArgs( args );

		guiFrame = new JFrame( "tr2d" );
		if ( isStandalone ) setImageAppIcon();

		//TODO improve this ad-hoc concept
		// Set context for tr2d
		Tr2dContext.segPlugins = segPlugins;
		Tr2dContext.ops = ops;
		Tr2dContext.guiFrame = guiFrame;

		if(projectFolder == null || inputStack == null)
			openStackOrProjectUserInteraction();

		final ImagePlus imgPlus = openImageStack();

		if ( imgPlus != null ) {
			final Tr2dModel model = new Tr2dModel( projectFolder, imgPlus );
			mainPanel = new Tr2dMainPanel( guiFrame, model, log );

			guiFrame.getContentPane().add( mainPanel );
			setFrameSizeAndCloseOperation();
			guiFrame.setVisible( true );
			mainPanel.collapseLog();

			if ( autoRun ) {
				mainPanel.selectTab( mainPanel.getTabTracking() );
				model.getTrackingModel().runInThread( false );
			}
		} else {
			guiFrame.dispose();
			if ( isStandalone ) System.exit( 0 );
		}
	}

	private void setFrameSizeAndCloseOperation() {
		try {
			FrameProperties.load( projectFolder.getFile( Tr2dProjectFolder.FRAME_PROPERTIES ).getFile(), guiFrame );
		} catch ( final IOException e ) {
			log.warn( "Frame properties not found. Will use default values." );
			guiFrame.setBounds( FrameProperties.getCenteredRectangle( 1200, 1024 ) );
		}

		guiFrame.setDefaultCloseOperation( WindowConstants.DO_NOTHING_ON_CLOSE );
		guiFrame.addWindowListener( new WindowAdapter() {

			@Override
			public void windowClosing( final WindowEvent we ) {
				final Object[] options = { "Quit", "Cancel" };
				final int choice = JOptionPane.showOptionDialog(
						guiFrame,
						"Do you really want to quit Tr2d?",
						"Quit?",
						JOptionPane.DEFAULT_OPTION,
						JOptionPane.QUESTION_MESSAGE,
						null,
						options,
						options[ 0 ] );
				if ( choice == 0 ) {
					runOptionalExport();

					try {
						FrameProperties.save( projectFolder.getFile( Tr2dProjectFolder.FRAME_PROPERTIES ).getFile(), guiFrame );
					} catch ( final Exception e ) {
						log.error( "Could not save frame properties in project folder!" );
						e.printStackTrace();
					}
					quit( 0 );
				}
			}
		} );
	}

	private void runOptionalExport() {
		if( exportFolder != null )
			mainPanel.getTabExport().schnitzcellExport( exportFolder );
	}

	/**
	 * @param exit_value
	 */
	public void quit( final int exit_value ) {
		if(guiFrame != null)
			guiFrame.dispose();
		if ( isStandalone ) {
			System.exit( exit_value );
		}
	}

	/**
	 * @return
	 */
	private void openStackOrProjectUserInteraction() {
		UniversalFileChooser.showOptionPaneWithTitleOnMac = true;


		final Object[] options = { "Tr2d Project...", "TIFF Stack..." };
		final int choice = JOptionPane.showOptionDialog(
				guiFrame,
				"Please choose an input type to be opened.",
				"Open...",
				JOptionPane.DEFAULT_OPTION,
				JOptionPane.QUESTION_MESSAGE,
				null,
				options,
				options[ 0 ] );
		if ( choice == 0 ) { // ===== PROJECT =====
			openProjectUserInteraction();
		} else if ( choice == 1 ) { // ===== TIFF STACK =====
			openStackUserInteraction();
		}

		UniversalFileChooser.showOptionPaneWithTitleOnMac = false;
	}

	private void openStackUserInteraction()
	{
		chooseStackUserInteraction();
		boolean validSelection = false;
		while ( !validSelection )
			validSelection = chooseProjectFolderUserInteraction();
		projectFolder.restartWithRawDataFile( inputStack.getAbsolutePath() );
	}

	private boolean chooseProjectFolderUserInteraction()
	{
		File projectFolderBasePath = UniversalFileChooser.showLoadFolderChooser(
				guiFrame,
				inputStack.getParent(),
				"Choose tr2d project folder..." );
		if ( projectFolderBasePath == null ) {
			quit( 2 );
		}
		try {
			projectFolder = new Tr2dProjectFolder( projectFolderBasePath );
		} catch ( final IOException e ) {
			log.error(
					String.format( "ERROR: Project folder (%s) could not be initialized.", projectFolderBasePath.getAbsolutePath() ) );
			e.printStackTrace();
			quit( 2 );
		}
		if ( ! projectFolder.getFile( Tr2dProjectFolder.RAW_DATA ).exists() )
			return true;

		final String msg = String.format(
				"Chosen project folder exists (%s).\nShould this project be overwritten?\nCurrent data in this project will be lost!",
				projectFolderBasePath );
		final int overwrite = JOptionPane.showConfirmDialog( guiFrame, msg, "Project Folder Exists", JOptionPane.YES_NO_OPTION );
		return overwrite == JOptionPane.YES_OPTION;
	}

	private void chooseStackUserInteraction()
	{
		inputStack = UniversalFileChooser.showLoadFileChooser(
				guiFrame,
				"",
				"Load input tiff stack...",
				new ExtensionFileFilter( "tif", "TIFF Image Stack" ) );
		if ( inputStack == null ) {
			quit( 1 );
		}
	}

	private void openProjectUserInteraction()
	{
		UniversalFileChooser.showOptionPaneWithTitleOnMac = false;
		File projectFolderBasePath = UniversalFileChooser.showLoadFolderChooser(
				guiFrame,
				"",
				"Choose tr2d project folder..." );
		UniversalFileChooser.showOptionPaneWithTitleOnMac = true;
		if ( projectFolderBasePath == null ) {
			quit( 1 );
		}
		openProjectFolder(projectFolderBasePath);
	}

	private ImagePlus openImageStack() {
		ImagePlus imgPlus = null;
		if ( inputStack != null ) {
//			IJ.open( inputStack.getAbsolutePath() );
			imgPlus = IJ.openImage( inputStack.getAbsolutePath() );
			if ( imgPlus == null ) {
				IJ.error( "There must be an active, open window!" );
				quit( 4 );
			}
		}
		return imgPlus;
	}

	/**
	 *
	 */
	private void setImageAppIcon() {
		Image image = null;
		try {
			image = new ImageIcon( Tr2dApplication.class.getClassLoader().getResource( "tr2d_dais_icon_color.png" ) ).getImage();
		} catch ( final Exception e ) {
			try {
				image = new ImageIcon( Tr2dApplication.class.getClassLoader().getResource(
						"resources/tr2d_dais_icon_color.png" ) ).getImage();
			} catch ( final Exception e2 ) {
				log.error( "app icon not found..." );
			}
		}

		if ( image != null ) {
			if ( OSValidator.isMac() ) {
				log.info( "On a Mac! --> trying to set icons..." );
				Application.getApplication().setDockIconImage( image );
			} else {
				log.info( "Not a Mac! --> trying to set icons..." );
				guiFrame.setIconImage( image );
			}
		}
	}

	/**
	 * Check if GRBEnv can be instantiated. For this to work Gurobi has to be
	 * installed and a valid license has to be pulled.
	 */
	private void checkGurobiAvailability() {
		final String jlp = System.getProperty( "java.library.path" );
		if ( !GurobiInstaller.testGurobi() ) {
			final String msgs = "Initial Gurobi test threw exception... check your Gruobi setup!\n\nJava library path: " + jlp;
			JOptionPane.showMessageDialog(
					guiFrame,
					msgs,
					"Gurobi Error?",
					JOptionPane.ERROR_MESSAGE);
			quit(98);
		}
	}

	/**
	 * Parse command line arguments and set variables accordingly.
	 *
	 * @param args
	 */
	private void parseCommandLineArgs( final String[] args ) {
		final String helpMessageLine1 =
				"Tr2d args: [-uprops properties-file] -p project-folder [-run] [-i input-stack] [-tmin idx] [-tmax idx] [-orange num-frames] [-e export-folder]";
		final Options options = getOptions();

		// get the commands parsed
		final CommandLineParser parser = new BasicParser();
		CommandLine cmd = null;
		try {
			cmd = parser.parse( options, args );
		} catch ( final ParseException e1 ) {
			final HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp(
					helpMessageLine1,
					"",
					options,
					"Error: " + e1.getMessage() );
			quit( 0 );
		}

		if ( cmd.hasOption( "help" ) ) {
			final HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp( helpMessageLine1, options );
			quit( 0 );
		}

		final File projectFolderBasePath = checkWritableFolderOption(cmd, "p", "project folder");

		exportFolder = checkWritableFolderOption(cmd, "e", "project folder");

		inputStack = null;
		if ( cmd.hasOption( "i" ) ) {
			inputStack = new File( cmd.getOptionValue( "i" ) );
			if ( !inputStack.isFile() )
				showErrorAndExit(5, "Given input tiff stack could not be found!");
			if ( !inputStack.canRead() )
				showErrorAndExit(6, "Given input tiff stack is not readable!");
		} else if ( projectFolderBasePath != null ) { // if a project folder was given load data from there!
			openProjectFolder(projectFolderBasePath);
		}

		if ( cmd.hasOption( "tmin" ) ) {
			minTime = Integer.parseInt( cmd.getOptionValue( "tmin" ) );
			if ( minTime < 0 )
				showWarning("Argument 'tmin' cannot be smaller than 0... using tmin=0...");
		}
		if ( cmd.hasOption( "tmax" ) ) {
			maxTime = Integer.parseInt( cmd.getOptionValue( "tmax" ) );
			if ( maxTime < minTime ) {
				maxTime = minTime + 1;
				showWarning( "Argument 'tmax' cannot be smaller than 'tmin'... using tmax=%d...",
						maxTime );
			}
		}

		if ( cmd.hasOption( "run" ) ) {
			autoRun = true;
		}
	}

	private Options getOptions()
	{
		// create Options object & the parser
		final Options options = new Options();
		// defining command line options
		final Option help = new Option( "help", "print this message" );

		final Option timeFirst = new Option( "tmin", "min_time", true, "first time-point to be processed" );
		timeFirst.setRequired( false );

		final Option timeLast = new Option( "tmax", "max_time", true, "last time-point to be processed" );
		timeLast.setRequired( false );

		final Option optRange = new Option( "orange", "opt_range", true, "obsolete parameter" );
		optRange.setRequired( false );

		final Option projectfolder = new Option( "p", "projectfolder", true, "tr2d project folder" );
		projectfolder.setRequired( false );

		final Option instack = new Option( "i", "input", true, "tiff stack to be read" );
		instack.setRequired( false );

		final Option run = new Option( "r", "run", false, "auto-run tracking upon start" );
		instack.setRequired( false );

		final Option userProps = new Option( "uprops", "userprops", true, "obsolete parameter" );
		userProps.setRequired( false );

		final Option exportFolder = new Option( "e", "export_folder", true, "Write results to this folder when closing tr2d." );
		exportFolder.setRequired( false );

		options.addOption( help );
		options.addOption( timeFirst );
		options.addOption( timeLast );
		options.addOption( optRange );
		options.addOption( instack );
		options.addOption( run );
		options.addOption( projectfolder );
		options.addOption( userProps );
		options.addOption( exportFolder );
		return options;
	}

	private File checkWritableFolderOption(final CommandLine cmd, final String shortOption, final String displayName) {
		File result = null;
		if ( cmd.hasOption( shortOption ) ) {
			result = new File( cmd.getOptionValue( shortOption ) );
			if ( !result.exists() )
				showErrorAndExit(1, "Given " + displayName + " does not exist!");
			if ( !result.isDirectory() )
				showErrorAndExit(2, "Given " + displayName + " is not a folder!");
			if ( !result.canWrite() )
				showErrorAndExit(3, "Given " + displayName + " cannot be written to!");
		}
		return result;
	}

	private void showWarning(final String msg, final Object... data) {
		JOptionPane.showMessageDialog( guiFrame, String.format(msg, data),
				"Argument Warning", JOptionPane.WARNING_MESSAGE );
		log.warn( msg );
	}

	private void showErrorAndExit(final int exit_value, final String msg, final Object... data) {
		JOptionPane.showMessageDialog(guiFrame, String.format(msg, data),
				"Argument Error", JOptionPane.ERROR_MESSAGE);
		log.error(msg);
		quit(exit_value);
	}

	private void openProjectFolder(final File projectFolderBasePath) {
		try {
			projectFolder = new Tr2dProjectFolder( projectFolderBasePath );
			inputStack = projectFolder.getFile( Tr2dProjectFolder.RAW_DATA ).getFile();
			if ( !inputStack.canRead() || !inputStack.exists() ) {
				showErrorAndExit(7, "Invalid project folder -- missing RAW data or read protected!");
			}
		} catch ( final IOException e ) {
			e.printStackTrace();
			showErrorAndExit(8, "Project folder (%s) could not be initialized.", projectFolderBasePath.getAbsolutePath() );
		}
	}

}

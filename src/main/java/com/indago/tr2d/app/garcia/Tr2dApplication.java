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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.apple.eawt.Application;
import com.indago.log.LoggingPanel;
import com.indago.tr2d.Tr2dContext;
import com.indago.tr2d.io.projectfolder.Tr2dProjectFolder;
import com.indago.tr2d.plugins.seg.Tr2dSegmentationPluginService;
import com.indago.tr2d.ui.model.Tr2dModel;
import com.indago.tr2d.ui.util.FrameProperties;
import com.indago.tr2d.ui.util.UniversalFileChooser;
import com.indago.tr2d.ui.view.Tr2dMainPanel;
import com.indago.util.OSValidator;

import gurobi.GRBEnv;
import gurobi.GRBException;
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
	public static boolean isStandalone = true;

	private static JFrame guiFrame;
	private static Tr2dMainPanel mainPanel;

	private static File inputStack;
	private static Tr2dProjectFolder projectFolder;

	private static File fileUserProps;
	private static int minTime = 0;
	private static int maxTime = Integer.MAX_VALUE;
	private static int initOptRange = Integer.MAX_VALUE;

	public static OpService ops = null;
	public static Tr2dSegmentationPluginService segPlugins = null;

	private static LogService global_log;
	public static Logger log;

	public static void main( final String[] args ) {

		System.setProperty( "apple.laf.useScreenMenuBar", "true" );

		if ( isStandalone ) { // main NOT called via Tr2dPlugin
			final ImageJ temp = IJ.getInstance();
			if ( temp == null ) {
				new ImageJ();
			}

			// Create context (since we did not receive one that was injected in 'Tr2dPlugin')
			final Context context =
					new Context( FormatService.class, OpService.class, OpMatchingService.class, IOService.class, DatasetIOService.class, LocationService.class, DatasetService.class, ImgUtilityService.class, StatusService.class, TranslatorService.class, QTJavaService.class, TiffService.class, CodecService.class, JAIIIOService.class, LogService.class, Tr2dSegmentationPluginService.class );
//			ImageSaver.context = context;
			ops = context.getService( OpService.class );
			segPlugins = context.getService( Tr2dSegmentationPluginService.class );

			// GET THE GLOBAL LOGGER
			// ---------------------
			global_log = context.getService( LogService.class );
			global_log.info( "STANDALONE" );

		} else {
			// GET THE GLOBAL LOGGER
			// ---------------------
			global_log = ops.getContext().getService( LogService.class );
			global_log.info( "PLUGIN" );

			// Check that all is set as it should...
			if ( segPlugins == null ) {
				log.error( "Tr2dPlugin failed to set the Tr2dSegmentationPluginService!" );
			}
		}

		// GET THE APP SPECIFIC LOGGER
		// ---------------------------
		log = LoggerFactory.getLogger( "tr2d" );

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
			mainPanel = new Tr2dMainPanel( guiFrame, model );

			guiFrame.getContentPane().add( mainPanel );
			setFrameSizeAndCloseOperation();
			guiFrame.setVisible( true );
			mainPanel.collapseLog();
		} else {
			guiFrame.dispose();
			if ( isStandalone ) System.exit( 0 );
		}
	}

	private static void setFrameSizeAndCloseOperation() {
		try {
			FrameProperties.load( projectFolder.getFile( Tr2dProjectFolder.FRAME_PROPERTIES ).getFile(), guiFrame );
		} catch ( final IOException e ) {
			Tr2dApplication.log.warn( "Frame properties not found. Will use default values." );
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
					try {
						FrameProperties.save( projectFolder.getFile( Tr2dProjectFolder.FRAME_PROPERTIES ).getFile(), guiFrame );
					} catch ( final Exception e ) {
						Tr2dApplication.log.error( "Could not save frame properties in project folder!" );
						e.printStackTrace();
					}
					Tr2dApplication.quit( 0 );
				}
			}
		} );
	}

	/**
	 * @param i
	 */
	public static void quit( final int exit_value ) {
		guiFrame.dispose();
		if ( isStandalone ) {
			System.exit( exit_value );
		}
	}

	/**
	 * @return
	 */
	private static void openStackOrProjectUserInteraction() {
		UniversalFileChooser.showOptionPaneWithTitleOnMac = true;

		File projectFolderBasePath = null;
		if ( projectFolder != null ) projectFolderBasePath = projectFolder.getFolder();

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
			UniversalFileChooser.showOptionPaneWithTitleOnMac = false;
			projectFolderBasePath = UniversalFileChooser.showLoadFolderChooser(
					guiFrame,
					"",
					"Choose tr2d project folder..." );
			UniversalFileChooser.showOptionPaneWithTitleOnMac = true;
			if ( projectFolderBasePath == null ) {
				Tr2dApplication.quit( 1 );
			}
			openProjectFolder(projectFolderBasePath);
		} else if ( choice == 1 ) { // ===== TIFF STACK =====
			inputStack = UniversalFileChooser.showLoadFileChooser(
					guiFrame,
					"",
					"Load input tiff stack...",
					new ExtensionFileFilter( "tif", "TIFF Image Stack" ) );
			if ( inputStack == null ) {
				Tr2dApplication.quit( 1 );
			}

			boolean validSelection = false;
			while ( !validSelection ) {
				projectFolderBasePath = UniversalFileChooser.showLoadFolderChooser(
						guiFrame,
						inputStack.getParent(),
						"Choose tr2d project folder..." );
				if ( projectFolderBasePath == null ) {
					Tr2dApplication.quit( 2 );
				}
				try {
					projectFolder = new Tr2dProjectFolder( projectFolderBasePath );
				} catch ( final IOException e ) {
					Tr2dApplication.log.error(
							String.format( "ERROR: Project folder (%s) could not be initialized.", projectFolderBasePath.getAbsolutePath() ) );
					e.printStackTrace();
					Tr2dApplication.quit( 2 );
				}
				if ( projectFolder.getFile( Tr2dProjectFolder.RAW_DATA ).exists() ) {
					final String msg = String.format(
							"Chosen project folder exists (%s).\nShould this project be overwritten?\nCurrent data in this project will be lost!",
							projectFolderBasePath );
					final int overwrite = JOptionPane.showConfirmDialog( guiFrame, msg, "Project Folder Exists", JOptionPane.YES_NO_OPTION );
					if ( overwrite == JOptionPane.YES_OPTION ) {
						validSelection = true;
					}
				} else {
					validSelection = true;
				}
			}
			projectFolder.restartWithRawDataFile( inputStack.getAbsolutePath() );
		}

		UniversalFileChooser.showOptionPaneWithTitleOnMac = false;
	}

	private static ImagePlus openImageStack() {
		ImagePlus imgPlus = null;
		if ( inputStack != null ) {
//			IJ.open( inputStack.getAbsolutePath() );
			imgPlus = IJ.openImage( inputStack.getAbsolutePath() );
			if ( imgPlus == null ) {
				IJ.error( "There must be an active, open window!" );
				Tr2dApplication.quit( 4 );
			}
		}
		return imgPlus;
	}

	/**
	 *
	 */
	private static void setImageAppIcon() {
		Image image = null;
		try {
			image = new ImageIcon( Tr2dApplication.class.getClassLoader().getResource( "tr2d_dais_icon_color.png" ) ).getImage();
		} catch ( final Exception e ) {
			try {
				image = new ImageIcon( Tr2dApplication.class.getClassLoader().getResource(
						"resources/tr2d_dais_icon_color.png" ) ).getImage();
			} catch ( final Exception e2 ) {
				Tr2dApplication.log.error( "app icon not found..." );
			}
		}

		if ( image != null ) {
			if ( OSValidator.isMac() ) {
				Tr2dApplication.log.info( "On a Mac! --> trying to set icons..." );
				Application.getApplication().setDockIconImage( image );
			} else {
				Tr2dApplication.log.info( "Not a Mac! --> trying to set icons..." );
				guiFrame.setIconImage( image );
			}
		}
	}

	/**
	 * Check if GRBEnv can be instantiated. For this to work Gurobi has to be
	 * installed and a valid license has to be pulled.
	 */
	private static void checkGurobiAvailability() {
		final String jlp = System.getProperty( "java.library.path" );
//		Tr2dApplication.log.info( jlp );
		try {
			try {
				new GRBEnv();
			} catch ( final GRBException e ) {
				final String msgs = "Initial Gurobi test threw exception... check your Gruobi setup!\n\nJava library path: " + jlp;
				JOptionPane.showMessageDialog(
						guiFrame,
						msgs,
						"Gurobi Error?",
						JOptionPane.ERROR_MESSAGE );
				e.printStackTrace();
				Tr2dApplication.quit( 98 );
			} catch ( final UnsatisfiedLinkError ulr ) {
				final String msgs =
						"Could not initialize Gurobi.\n" + "You might not have installed Gurobi properly or you miss a valid license.\n" + "Please visit 'www.gurobi.com' for further information.\n\n" + ulr
								.getMessage() + "\nJava library path: " + jlp;
				JOptionPane.showMessageDialog(
						guiFrame,
						msgs,
						"Gurobi Error?",
						JOptionPane.ERROR_MESSAGE );
				ulr.printStackTrace();
				Tr2dApplication.log.info( ">>>>> Java library path: " + jlp );
				Tr2dApplication.quit( 99 );
			}
		} catch ( final NoClassDefFoundError err ) {
			final String msgs =
					"Gurobi seems to be not installed on your system.\n" + "Please visit 'www.gurobi.com' for further information.\n\n" + "Java library path: " + jlp;
			JOptionPane.showMessageDialog(
					guiFrame,
					msgs,
					"Gurobi not installed?",
					JOptionPane.ERROR_MESSAGE );
			err.printStackTrace();
			Tr2dApplication.quit( 100 );
		}
	}

	/**
	 * Parse command line arguments and set static variables accordingly.
	 *
	 * @param args
	 */
	private static void parseCommandLineArgs( final String[] args ) {
		final String helpMessageLine1 =
				"Tr2d args: [-uprops properties-file] -p project-folder [-i input-stack] [-tmin idx] [-tmax idx] [-orange num-frames]";

		// create Options object & the parser
		final Options options = new Options();
		final CommandLineParser parser = new BasicParser();
		// defining command line options
		final Option help = new Option( "help", "print this message" );

		final Option timeFirst = new Option( "tmin", "min_time", true, "first time-point to be processed" );
		timeFirst.setRequired( false );

		final Option timeLast = new Option( "tmax", "max_time", true, "last time-point to be processed" );
		timeLast.setRequired( false );

		final Option optRange = new Option( "orange", "opt_range", true, "initial optimization range" );
		optRange.setRequired( false );

		final Option projectfolder = new Option( "p", "projectfolder", true, "tr2d project folder" );
		projectfolder.setRequired( false );

		final Option instack = new Option( "i", "input", true, "tiff stack to be read" );
		instack.setRequired( false );

		final Option userProps = new Option( "uprops", "userprops", true, "user properties file to be loaded" );
		userProps.setRequired( false );

		options.addOption( help );
		options.addOption( timeFirst );
		options.addOption( timeLast );
		options.addOption( optRange );
		options.addOption( instack );
		options.addOption( projectfolder );
		options.addOption( userProps );
		// get the commands parsed
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
			Tr2dApplication.quit( 0 );
		}

		if ( cmd.hasOption( "help" ) ) {
			final HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp( helpMessageLine1, options );
			Tr2dApplication.quit( 0 );
		}

		File projectFolderBasePath = null;
		if ( cmd.hasOption( "p" ) ) {
			projectFolderBasePath = new File( cmd.getOptionValue( "p" ) );
			if ( !projectFolderBasePath.exists() )
				showErrorAndExit(1, "Given project folder does not exist!");
			if ( !projectFolderBasePath.isDirectory() )
				showErrorAndExit(2, "Given project folder is not a folder!");
			if ( !projectFolderBasePath.canWrite() )
				showErrorAndExit(3, "Given project folder cannot be written to!");
		}

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

		fileUserProps = null;
		if ( cmd.hasOption( "uprops" ) ) {
			fileUserProps = new File( cmd.getOptionValue( "uprops" ) );
			if ( !inputStack.canRead() )
				showWarning( "User properties file not readable (%s). Continue without...",
						fileUserProps.getAbsolutePath() );
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

		if ( cmd.hasOption( "orange" ) ) {
			initOptRange = Integer.parseInt( cmd.getOptionValue( "orange" ) );
			if ( initOptRange > maxTime - minTime ) {
				initOptRange = maxTime - minTime;
				showWarning( "Argument 'orange' (initial optimization range in frames)" +
						" too large... using %d instead...", initOptRange );
			}
		}
	}

	private static void showWarning(String msg, Object... data) {
		JOptionPane.showMessageDialog( guiFrame, String.format(msg, data),
				"Argument Warning", JOptionPane.WARNING_MESSAGE );
		Tr2dApplication.log.warn( msg );
	}

	private static void showErrorAndExit(int exit_value, String msg, Object... data) {
		JOptionPane.showMessageDialog(guiFrame, String.format(msg, data),
				"Argument Error", JOptionPane.ERROR_MESSAGE);
		Tr2dApplication.log.error(msg);
		Tr2dApplication.quit(exit_value);
	}

	private static void openProjectFolder(File projectFolderBasePath) {
		try {
			projectFolder = new Tr2dProjectFolder( projectFolderBasePath );
			inputStack = projectFolder.getFile( Tr2dProjectFolder.RAW_DATA ).getFile();
			if ( !inputStack.canRead() || !inputStack.exists() ) {
				showErrorAndExit(7, "Invalid project folder -- missing RAW data or read protected!");
			}
		} catch ( final IOException e ) {
			e.printStackTrace();
			showErrorAndExit(8, "Project folder (%s) could not be initialized.", projectFolderBasePath.getAbsolutePath() ) );
		}
	}

	/**
	 * @return the guiFrame
	 */
	public static JFrame getGuiFrame() {
		return guiFrame;
	}

	/**
	 * @return the mainPanel
	 */
	public static Tr2dMainPanel getMainPanel() {
		return mainPanel;
	}

	/**
	 * @return the mainPanel
	 */
	public static LoggingPanel getLogPanel() {
		return mainPanel.getLogPanel();
	}
}

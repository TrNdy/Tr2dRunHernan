import javax.swing.JOptionPane;

import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.log.LogService;
import org.scijava.log.Logger;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import com.indago.tr2d.app.garcia.Tr2dApplication;
import com.indago.tr2d.plugins.seg.Tr2dSegmentationPluginService;

import net.imagej.ops.OpService;

/**
 * Tr2d Plugin for Fiji/ImageJ2
 *
 * @author Florian Jug
 */

@Plugin( type = ContextCommand.class, headless = false, menuPath = "Plugins>Tracking>Tr2d 0.2.2-SNAP" )
public class Tr2dPlugin implements Command {

	@Parameter
	private OpService opService;

	@Parameter
	private Logger log;

	/**
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		System.setProperty( "scijava.log.level", "error" );
		System.setProperty( "scijava.log.level:com.indago", "none" );

		Tr2dApplication.isStandalone = false;
		Tr2dApplication.ops = opService;
		Tr2dApplication.segPlugins = opService.context().getService( Tr2dSegmentationPluginService.class );
		Tr2dApplication.log = log;

//		ImageSaver.context = opService.context();

		try {
			Tr2dApplication.main( null );
		} catch ( final NoClassDefFoundError err ) {
			final String jlp = System.getProperty( "java.library.path" );
			final String msgs =
					"Gurobi seems to be not installed on your system.\n" + "Please visit 'www.gurobi.com' for further information.\n\n" + "Java library path: " + jlp;
			JOptionPane.showMessageDialog(
					null,
					msgs,
					"Gurobi not installed?",
					JOptionPane.ERROR_MESSAGE );
			err.printStackTrace();
			Tr2dApplication.quit( 100 );
		}
	}
}

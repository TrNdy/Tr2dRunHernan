package com.indago.tr2d.app.garcia;

import com.indago.io.ProjectFolder;
import com.indago.plugins.seg.IndagoSegmentationPlugin;
import com.indago.plugins.seg.IndagoSegmentationPluginService;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.DoubleType;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.log.Logger;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.PluginInfo;
import org.scijava.plugin.PluginService;
import org.scijava.plugin.SciJavaPlugin;

import javax.swing.*;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class SegmentationPluginsTest {

	@Test
	public void testSegmentationPluginAvailability() {
		Context context = new Context();
		PluginService service = context.service(PluginService.class);
		List< PluginInfo< IndagoSegmentationPlugin > > plugins =
				service.getPluginsOfType(IndagoSegmentationPlugin.class);
		// There should be three segmentation plugins
		//  * Indago Labkit Segmentation Plugin
		//  * Indago Segmentation Import Plugin
		//  * Indago Weka Segmenter Plugin
		assertEquals(3, plugins.size());
	}
}

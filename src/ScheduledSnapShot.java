import java.util.Arrays;
import java.util.Date;
import java.util.prefs.Preferences;

import java.awt.TextField;
import java.awt.Label;
import java.awt.GridBagConstraints;
import java.awt.Dimension;
import java.awt.Checkbox;
import java.awt.Rectangle;
import java.awt.Point;
import java.awt.Button;
import java.awt.Choice;
import java.awt.GridBagLayout;

import java.awt.event.ActionListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseEvent;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;

import ij.ImageListener;
import ij.ImagePlus;
import ij.IJ;
import ij.WindowManager;
import ij.ImageStack;

import ij.io.DirectoryChooser;
import ij.io.FileInfo;
import ij.io.TiffEncoder;
import ij.io.FileSaver;

import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.gui.Roi;
import ij.gui.Wand;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.PolygonRoi;

import ij.plugin.filter.EDM;
import ij.plugin.filter.RankFilters;
import ij.plugin.frame.PlugInFrame;

import java.io.File;
import java.io.OutputStream;
import java.io.FileOutputStream;

import mmcorej.CMMCore;
import mmcorej.MMCoreJ;
import mmcorej.StrVector;
import mmcorej.Configuration;
import mmcorej.PropertySetting;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.micromanager.api.ScriptInterface;


class ScheduledSnapShot implements Runnable {
	CMMCore core;
	ScriptInterface app;
	long timePoint;
	String saveDirectory;
	int frameIndex;

	ScheduledSnapShot(CMMCore core_, ScriptInterface app_, long timePoint_, String saveDirectory_, int frameIndex_){
		core = core_;
		timePoint = timePoint_;
		app = app_;
		saveDirectory = saveDirectory_;
		frameIndex = frameIndex_;
	}

	public void run(){
		IJ.log("[INFO] Acquring image at time " + String.valueOf(timePoint));

		if( !app.isLiveModeOn() ){
			IJ.log("[ERROR] Could not acquire image.  Live mode must be on.  Please press STOP." );
			return;
		}
        ImagePlus liveModeImage = app.getSnapLiveWin().getImagePlus();

		FileSaver f = new FileSaver(liveModeImage);
		f.saveAsTiff(saveDirectory + "/" + String.valueOf(frameIndex) + ".tiff");
	}
}

import java.util.ArrayList;
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

import org.micromanager.api.ScriptInterface;

// provides the ui for track stim

// implements a ImageJ plugin interface 
// **NOTE**: There is a big difference between a micro manager plugin and an imagej plugin
// this program was initially designed as an imageJ plugin, but is now wrapped inside a micromanager plugin
// to migrate it to new versions of micromanager
class TrackStimGUI extends PlugInFrame implements ActionListener, ImageListener, MouseListener, ItemListener {
    // globals just in this class
    Preferences prefs;

    // camera options
    TextField numFramesText;
    TextField numSkipFramesText;
    java.awt.Choice cameraExposureDurationSelector;
    java.awt.Choice cameraCycleDurationSelector;

    // tracker options
    java.awt.Checkbox useClosest;// target definition method.
    java.awt.Checkbox trackRightSideScreen;// field for tacking source
    java.awt.Checkbox saveXYPositionsAsTextFile;// if save xy pos data into txt file. not inclued z.
    java.awt.Choice stageAccelerationSelector;
    java.awt.Choice thresholdMethodSelector;
    java.awt.Checkbox useCenterOfMassTracking;// center of mass method
    java.awt.Checkbox useManualTracking;
    java.awt.Checkbox useFullFieldImaging;// full size filed.
    java.awt.Checkbox useBrightFieldImaging;// Bright field tracking only works with full size

    // stimulator options
    java.awt.Checkbox enableStimulator;
    TextField preStimulationTimeMsText;
    TextField stimulationStrengthText;
    TextField stimulationDurationMsText;
    TextField stimulationCycledurationMsText;
    TextField numStimulationCyclesText;
    java.awt.Checkbox enableRamp;
    TextField rampBase;
    TextField rampStart;
    TextField rampEnd;

    // directory to save to
    TextField saveDirectoryText;
    String saveDirectory; // trackstim will create subdirectories in this folder


    // pass to Tracker
    CMMCore mmc;
    ScriptInterface app;
    ImagePlus currentImage;
    ImageCanvas currentImageCanvas;
    String imageSaveDirectory;  // a subfolder within saveDirectory
    int numFrames;
    boolean ready;

    Tracker tracker;
    Stimulator stimulator;

    public TrackStimGUI(CMMCore cmmcore, ScriptInterface app_) {
        super("TrackStim");

        // set up micro manager variables
        mmc = cmmcore;
        app = app_;
        IJ.log("TrackStimGUI Constructor: MMCore initialized");

        // set up imageJ window stuff
        currentImage = WindowManager.getCurrentImage();
        ImageWindow iw = currentImage.getWindow();
        currentImageCanvas = iw.getCanvas();
        currentImageCanvas.addMouseListener(this);
        ImagePlus.addImageListener(this);

        // set up stimulator
        stimulator = new Stimulator(mmc);
        boolean stimulatorConnected = stimulator.initialize();
        if( !stimulatorConnected ){
            IJ.log("TrackStimGUI Constructor: could not initialize stimulator.  Stimulator related options will not work");
        }

        // set up tracker
        // just set it to null for now, it is initialized later
        tracker = null;

        // set up previous preferences
        prefs = Preferences.userNodeForPackage(this.getClass());// make instance?
        String previousSaveDirectoryPref = prefs.get("DIR", "");
        String previousNumFramesPref = prefs.get("FRAME", "");

        // try to get preferences directory and frame
        try {
            numFrames = previousNumFramesPref == "" ? 3000 : Integer.parseInt(previousNumFramesPref);
        } catch (java.lang.Exception e){
            IJ.log("TrackStimGUI Constructor: Could not get frame value from preferences obj");
            IJ.log(e.getMessage());
        }
        saveDirectory = previousSaveDirectoryPref == "" ? IJ.getDirectory("current") : previousSaveDirectoryPref;
        saveDirectory = saveDirectory ==  null ? IJ.getDirectory("home") : saveDirectory;

        IJ.log("TrackStimGUI Constructor: initial dir is " + saveDirectory);
        IJ.log("TrackStimGUI Constructor: Frame value is " + String.valueOf(numFrames));

        // initialize GUI
        requestFocus(); // may need for keylistener
        initComponents(); // create the GUI
        setSize(700, 225);
        setVisible(true);
    }

    // validate the frame field in the UI
    boolean numFramesIsValid(){
        int testint;

        try {
            testint = Integer.parseInt(numFramesText.getText());
            numFrames = testint;
            prefs.put("FRAME", String.valueOf(numFrames));
            IJ.log("numFramesIsValid: numFrames is valid " + String.valueOf(numFrames));
            return true;
        } catch (java.lang.Exception e) {
            IJ.log("numFramesIsValid: the current value of the frame field is not an int");
            IJ.log(e.getMessage());
            numFramesText.setText(String.valueOf(numFrames));
            return false;
        }
    }

    // validate the directory field in the UI
    boolean saveDirectoryIsValid(){
        String dirName = saveDirectoryText.getText();
        File checkdir = new File(dirName);

        if (checkdir.exists()) {
            IJ.log("saveDirectoryIsValid: directory is valid . " + dirName + " exists");
            saveDirectory = dirName;
            prefs.put("DIR", saveDirectory);
            return true;
        } else {
            IJ.log("saveDirectoryIsValid: directory " + dirName + " DOES NOT EXIST! Please create the directory first");
            saveDirectoryText.setText(saveDirectory);
            return false;
        }
    }

    // method required by ItemListener
    public void itemStateChanged(ItemEvent e) {

        // listen to item changes if the user selecs the camera exposure duration selector or camera cycle duration
        // becayse these values depend on each other
        if (e.getSource() == cameraExposureDurationSelector || e.getSource() == cameraCycleDurationSelector) {
            IJ.log("itemStateChanged: camera settings selector changed, updating camera settings");
            // indexes are mapped to specific values
            // e.g. if you want trigger length 50, you send index=3 to the stimulator
            // see /documentation/arduino.c lines 41-45
            int newExposureSelectionIndex = cameraExposureDurationSelector.getSelectedIndex();
            int newCycleLengthIndex = cameraCycleDurationSelector.getSelectedIndex();

            // set cycle length if exposure is set
            if ((newExposureSelectionIndex == 1 || newExposureSelectionIndex == 2) && newCycleLengthIndex == 0) {
                cameraCycleDurationSelector.select(1);
                newCycleLengthIndex = cameraCycleDurationSelector.getSelectedIndex();
            } else if (newCycleLengthIndex + 1 <= newExposureSelectionIndex) {
                cameraCycleDurationSelector.select(newExposureSelectionIndex - 1);
                newCycleLengthIndex = cameraCycleDurationSelector.getSelectedIndex();
            }

            try {
                stimulator.updateCameraSettings(newExposureSelectionIndex, newCycleLengthIndex);

            } catch (java.lang.Exception ex){
                IJ.log("itemStateChanged: error trying to update the camera settings");
                IJ.log(ex.getMessage());
            }
        }

    }

    // handle button presses
    public void actionPerformed(ActionEvent e) {
        String clickedBtn = e.getActionCommand();

        IJ.log("actionPerformed: button clicked is " + clickedBtn);

        // num frames text
        if (clickedBtn.equals(numFramesText.getText())){
            numFramesIsValid();
        } 

        // savedirectory text
        if (clickedBtn.equals(saveDirectoryText.getText())){
            saveDirectoryIsValid();
        }

        // button to ready.  not sure what ready does that go doesnt do
        if (clickedBtn.equals("Ready")){
            ready = true;
            validateAndStartTracker();
        }

        // button to start TrackStim image acquisition
        if (clickedBtn.equals("Go") && numFramesIsValid() && saveDirectoryIsValid()){
            createImageSaveDirectory();
            ready = false;
            if (enableStimulator.getState()) {
                validateAndStartStimulation();
            }
            validateAndStartTracker();
        }

        // stop image acquisition process
        if (clickedBtn.equals("Stop")){
            stopTracker();
            // TODO also stop the stimulator if possible
        }

        // button to choose directory
        if (clickedBtn.equals("Change dir")) {
            DirectoryChooser dc = new DirectoryChooser("Directory for temp folder");
            String dcdir = dc.getDirectory();
            saveDirectoryText.setText(dcdir);
        }

        // button to test stimulator
        if (clickedBtn.equals("Run")){
            validateAndStartStimulation();
        }
    }

    void createImageSaveDirectory(){
        // get count number of directories N so that we can create directory N+1
        File saveDirectoryFile = new File(saveDirectory);
        File[] fileList = saveDirectoryFile.listFiles();
        int numSubDirectories = 0;
        for (int i = 0; i < fileList.length; i++) {
            if (fileList[i].isDirectory()) {
                numSubDirectories++;
            }
        }

        // choose first temp<i> which does not exist yet and create directory with name tempi
        int i = 1;
        File newdir = new File(saveDirectory + "temp" + String.valueOf(numSubDirectories + i));
        while (newdir.exists()) {
            i++;
            newdir = new File(saveDirectory + "temp" + String.valueOf(numSubDirectories + i));
        }

        newdir.mkdir();
        imageSaveDirectory = newdir.getPath();// this one doen't have "/" at the end
        IJ.log("createImageSaveDirectory: created new directory " + imageSaveDirectory);
    }

    void stopTracker(){
        // check if the tracking thread is running
        if (tracker != null && tracker.isAlive()){

            // stop by stopping sequence acquisition. don't know better way but seems work.
            try {
                mmc.stopSequenceAcquisition();
                IJ.log("stopTracker: tracking thread is still running, stopping sequence acquisition and setting tracker to null");
            } catch (java.lang.Exception ex) {
                IJ.log("stopTracker: could not stop previous thread sequence acquisition");
                IJ.log(ex.getMessage());
            }
        }
        tracker = null; 
    }

    void validateAndStartTracker(){
        IJ.log("validateAndStartTracker: stopping previous tracker if possible and creating new tracker");
        stopTracker();
        tracker = new Tracker(this);
        tracker.start();
    }

    void validateAndStartStimulation(){
        IJ.log("validateAndStartStimulation: running stimulation");
        try {
            int preStimulationVal = Integer.parseInt(preStimulationTimeMsText.getText());// initial delay
            int strengthVal = Integer.parseInt(stimulationStrengthText.getText());// strength
            int stimDurationVal = Integer.parseInt(stimulationDurationMsText.getText());// duration
            int stimCycleLengthVal = Integer.parseInt(stimulationCycledurationMsText.getText());// cycle time
            int stimCycleVal = Integer.parseInt(numStimulationCyclesText.getText());// repeat
            int rampBaseVal = Integer.parseInt(rampBase.getText());// base
            int rampStartVal = Integer.parseInt(rampStart.getText());// start
            int rampEndVal = Integer.parseInt(rampEnd.getText());// end
            boolean useRamp = enableRamp.getState();
            stimulator.runStimulation(useRamp, preStimulationVal, strengthVal, stimDurationVal, stimCycleLengthVal, stimCycleVal, rampBaseVal, rampStartVal, rampEndVal);

        } catch (java.lang.Exception e){
            IJ.log("TrackStimGUI.validateAndStartStimulation: error trying to run stimulation");
            IJ.log(e.getMessage());
        }
    }

    public void imageOpened(ImagePlus imp) {
    }

    public void imageUpdated(ImagePlus imp) {
    }

    // Write logic to clear variables when image closed here
    public void imageClosed(ImagePlus impc) {
        IJ.log("imageClosed: cleaning up");
        if (currentImage == impc) {
            currentImage = null;
            IJ.log("imageClosed: imp set to null");
        }
    }

    /** Handle the key typed event from the text field. */
    public void keyTyped(KeyEvent e) {
        IJ.log("keyTyped: " + KeyEvent.getKeyText(e.getKeyCode()));
    }

    /** Handle the key-pressed event from the text field. */
    public void keyPressed(KeyEvent e) {
        IJ.log("keyPressed: " + KeyEvent.getKeyText(e.getKeyCode()));
    }

    /** Handle the key-released event from the text field. */
    public void keyReleased(KeyEvent e) {
        IJ.log("keyReleased: " + KeyEvent.getKeyText(e.getKeyCode()));
    }

    // Handle mouse click
    public void mouseClicked(MouseEvent e) {
        IJ.log("mouseClicked: ");
        if (tracker != null && tracker.isAlive()) {
            IJ.log("mouseClicked: tracking thread is active, changing target...");
            tracker.changeTarget();
        }
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
    }

    public void mousePressed(MouseEvent e) {
    }

    public void mouseReleased(MouseEvent e) {
    }

    // create the GUI
    void initComponents(){
        // Prepare GUI
        GridBagLayout gbl = new GridBagLayout();
        setLayout(gbl);
        GridBagConstraints gbc = new GridBagConstraints();

        Button b = new Button("Ready");
        b.setPreferredSize(new Dimension(100, 60));
        b.addActionListener(this);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.gridheight = 2;
        gbl.setConstraints(b, gbc);
        add(b);

        Button b2 = new Button("Go");
        b2.setPreferredSize(new Dimension(100, 60));
        b2.addActionListener(this);
        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.gridheight = 2;
        gbl.setConstraints(b2, gbc);
        add(b2);

        Button b3 = new Button("Stop");
        b3.setPreferredSize(new Dimension(100, 60));
        b3.addActionListener(this);
        gbc.gridx = 4;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.gridheight = 2;
        gbl.setConstraints(b3, gbc);
        add(b3);

        Label labelexpduration = new Label("exposure");
        gbc.gridx = 6;
        gbc.gridy = 0;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        gbl.setConstraints(labelexpduration, gbc);
        add(labelexpduration);

        cameraExposureDurationSelector = new Choice();
        cameraExposureDurationSelector.setPreferredSize(new Dimension(80, 20));
        gbc.gridx = 7;
        gbc.gridy = 0;
        gbc.gridwidth = 1;
        cameraExposureDurationSelector.add("0");
        cameraExposureDurationSelector.add("1");
        cameraExposureDurationSelector.add("10");
        cameraExposureDurationSelector.add("50");
        cameraExposureDurationSelector.add("100");
        cameraExposureDurationSelector.add("200");
        cameraExposureDurationSelector.add("500");
        cameraExposureDurationSelector.add("1000");
        cameraExposureDurationSelector.addItemListener(this);
        gbl.setConstraints(cameraExposureDurationSelector, gbc);
        add(cameraExposureDurationSelector);

        Label labelcameracyclelength = new Label("cycle len.");
        gbc.gridx = 6;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        gbl.setConstraints(labelcameracyclelength, gbc);
        add(labelcameracyclelength);

        cameraCycleDurationSelector = new Choice();
        cameraCycleDurationSelector.setPreferredSize(new Dimension(80, 20));
        gbc.gridx = 7;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        cameraCycleDurationSelector.add("0");
        cameraCycleDurationSelector.add("50");
        cameraCycleDurationSelector.add("100");
        cameraCycleDurationSelector.add("200");
        cameraCycleDurationSelector.add("500");
        cameraCycleDurationSelector.add("1000");
        cameraCycleDurationSelector.add("2000");
        cameraCycleDurationSelector.addItemListener(this);
        gbl.setConstraints(cameraCycleDurationSelector, gbc);
        add(cameraCycleDurationSelector);

        Label labelframe = new Label("Frame num");
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbl.setConstraints(labelframe, gbc);
        add(labelframe);

        numFramesText = new TextField(String.valueOf(numFrames), 5);
        numFramesText.addActionListener(this);
        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbl.setConstraints(numFramesText, gbc);
        add(numFramesText);

        useClosest = new Checkbox("Just closest", true);
        gbc.gridx = 2;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbl.setConstraints(useClosest, gbc);
        add(useClosest);

        stageAccelerationSelector = new Choice();
        gbc.gridx = 3;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        stageAccelerationSelector.add("1x");
        stageAccelerationSelector.add("2x");
        stageAccelerationSelector.add("4x");
        stageAccelerationSelector.add("5x");
        stageAccelerationSelector.add("6x");
        gbl.setConstraints(stageAccelerationSelector, gbc);
        add(stageAccelerationSelector);

        thresholdMethodSelector = new Choice();
        gbc.gridx = 4;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        thresholdMethodSelector.add("Yen");// good for normal
        thresholdMethodSelector.add("Triangle");// good for on coli
        thresholdMethodSelector.add("Otsu");// good for ventral cord?

        thresholdMethodSelector.add("Default");
        thresholdMethodSelector.add("Huang");
        thresholdMethodSelector.add("Intermodes");
        thresholdMethodSelector.add("IsoData");
        thresholdMethodSelector.add("Li");
        thresholdMethodSelector.add("MaxEntropy");
        thresholdMethodSelector.add("Mean");
        thresholdMethodSelector.add("MinError(I)");
        thresholdMethodSelector.add("Minimum");
        thresholdMethodSelector.add("Moments");
        thresholdMethodSelector.add("Percentile");
        thresholdMethodSelector.add("RenyiEntropy");
        thresholdMethodSelector.add("Shanbhag");
        thresholdMethodSelector.setPreferredSize(new Dimension(80, 20));
        gbl.setConstraints(thresholdMethodSelector, gbc);
        add(thresholdMethodSelector);

        trackRightSideScreen = new Checkbox("Use right", false);
        gbc.gridx = 5;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbl.setConstraints(trackRightSideScreen, gbc);
        add(trackRightSideScreen);

        saveXYPositionsAsTextFile = new Checkbox("Save xypos file", false);
        gbc.gridx = 6;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbl.setConstraints(saveXYPositionsAsTextFile, gbc);
        add(saveXYPositionsAsTextFile);

        Label labelskip = new Label("one of ");
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbl.setConstraints(labelskip, gbc);
        add(labelskip);

        numSkipFramesText = new TextField("1", 2);
        numSkipFramesText.addActionListener(this);
        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbl.setConstraints(numSkipFramesText, gbc);
        add(numSkipFramesText);

        useCenterOfMassTracking = new Checkbox("Center of Mass", false);
        gbc.gridx = 2;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbl.setConstraints(useCenterOfMassTracking, gbc);
        add(useCenterOfMassTracking);

        useManualTracking = new Checkbox("manual track", false);
        gbc.gridx = 3;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbl.setConstraints(useManualTracking, gbc);
        add(useManualTracking);

        useFullFieldImaging = new Checkbox("Full field", false);
        gbc.gridx = 5;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbl.setConstraints(useFullFieldImaging, gbc);
        add(useFullFieldImaging);

        useBrightFieldImaging = new Checkbox("Bright field", false);
        gbc.gridx = 6;
        gbc.gridy = 3;
        gbc.gridwidth = 1;
        gbl.setConstraints(useBrightFieldImaging, gbc);
        add(useBrightFieldImaging);

        Label labeldir = new Label("Save at");
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 1;
        gbl.setConstraints(labeldir, gbc);
        add(labeldir);

        saveDirectoryText = new TextField(saveDirectory, 40);
        saveDirectoryText.addActionListener(this);
        gbc.gridx = 1;
        gbc.gridy = 4;
        gbc.gridwidth = 5;
        gbc.fill = GridBagConstraints.BOTH;
        gbl.setConstraints(saveDirectoryText, gbc);
        add(saveDirectoryText);
        gbc.fill = GridBagConstraints.NONE;// return to default

        Button b4 = new Button("Change dir");
        b4.addActionListener(this);
        gbc.gridx = 6;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        gbl.setConstraints(b4, gbc);
        add(b4);

        // gui for stimulation
        enableStimulator = new Checkbox("Light", false);
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.NORTH;
        gbl.setConstraints(enableStimulator, gbc);
        add(enableStimulator);

        b = new Button("Run");
        b.setPreferredSize(new Dimension(40, 20));
        b.addActionListener(this);
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.gridheight = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(b, gbc);
        add(b);
        gbc.gridheight = 1;

        Label labelpre = new Label("Pre-stim");
        gbc.gridx = 1;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(labelpre, gbc);
        add(labelpre);

        preStimulationTimeMsText = new TextField(String.valueOf(10000), 6);
        preStimulationTimeMsText.setPreferredSize(new Dimension(50, 30));
        preStimulationTimeMsText.addActionListener(this);
        gbc.gridx = 2;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbl.setConstraints(preStimulationTimeMsText, gbc);
        add(preStimulationTimeMsText);

        Label labelstrength = new Label("Strength <63");
        gbc.gridx = 1;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(labelstrength, gbc);
        add(labelstrength);

        stimulationStrengthText = new TextField(String.valueOf(63), 6);
        stimulationStrengthText.setPreferredSize(new Dimension(50, 30));
        stimulationStrengthText.addActionListener(this);
        gbc.gridx = 2;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbl.setConstraints(stimulationStrengthText, gbc);
        add(stimulationStrengthText);

        Label labelduration = new Label("Duration");
        gbc.gridx = 1;
        gbc.gridy = 7;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(labelduration, gbc);
        add(labelduration);

        stimulationDurationMsText = new TextField(String.valueOf(5000), 6);
        stimulationDurationMsText.setPreferredSize(new Dimension(50, 30));
        stimulationDurationMsText.addActionListener(this);
        gbc.gridx = 2;
        gbc.gridy = 7;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbl.setConstraints(stimulationDurationMsText, gbc);
        add(stimulationDurationMsText);

        Label labelcyclelength = new Label("Cycle length");
        gbc.gridx = 3;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(labelcyclelength, gbc);
        add(labelcyclelength);

        stimulationCycledurationMsText = new TextField(String.valueOf(10000), 6);
        stimulationCycledurationMsText.setPreferredSize(new Dimension(50, 30));
        stimulationCycledurationMsText.addActionListener(this);
        gbc.gridx = 4;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbl.setConstraints(stimulationCycledurationMsText, gbc);
        add(stimulationCycledurationMsText);

        Label labelcyclenum = new Label("Cycle num");
        gbc.gridx = 3;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(labelcyclenum, gbc);
        add(labelcyclenum);

        numStimulationCyclesText = new TextField(String.valueOf(3), 6);
        numStimulationCyclesText.setPreferredSize(new Dimension(50, 30));
        numStimulationCyclesText.addActionListener(this);
        gbc.gridx = 4;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbl.setConstraints(numStimulationCyclesText, gbc);
        add(numStimulationCyclesText);

        enableRamp = new Checkbox("ramp", false);
        gbc.gridx = 5;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        // gbc.gridheight=3;
        gbc.anchor = GridBagConstraints.NORTH;
        gbl.setConstraints(enableRamp, gbc);
        add(enableRamp);

        Label labelbase = new Label("base");
        gbc.gridx = 6;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(labelbase, gbc);
        add(labelbase);

        rampBase = new TextField(String.valueOf(0), 3);
        rampBase.setPreferredSize(new Dimension(30, 30));
        rampBase.addActionListener(this);
        gbc.gridx = 7;
        gbc.gridy = 5;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbl.setConstraints(rampBase, gbc);
        add(rampBase);

        Label labelrampstart = new Label("start");
        gbc.gridx = 6;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(labelrampstart, gbc);
        add(labelrampstart);

        rampStart = new TextField(String.valueOf(0), 3);
        rampStart.setPreferredSize(new Dimension(30, 30));
        rampStart.addActionListener(this);
        gbc.gridx = 7;
        gbc.gridy = 6;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbl.setConstraints(rampStart, gbc);
        add(rampStart);

        Label labelrampend = new Label("end");
        gbc.gridx = 6;
        gbc.gridy = 7;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.CENTER;
        gbl.setConstraints(labelrampend, gbc);
        add(labelrampend);

        rampEnd = new TextField(String.valueOf(63), 3);
        rampEnd.setPreferredSize(new Dimension(30, 30));
        rampEnd.addActionListener(this);
        gbc.gridx = 7;
        gbc.gridy = 7;
        gbc.gridwidth = 1;
        gbc.anchor = GridBagConstraints.EAST;
        gbl.setConstraints(rampEnd, gbc);
        add(rampEnd);
    }

}
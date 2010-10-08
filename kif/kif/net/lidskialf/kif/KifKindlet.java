package net.lidskialf.kif;

import java.awt.Component;
import java.awt.Container;
import java.awt.Image;
import java.awt.Insets;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;

import org.apache.log4j.*;

import com.amazon.kindle.kindlet.*;
import com.amazon.kindle.kindlet.event.KindleKeyCodes;
import com.amazon.kindle.kindlet.ui.*;

import org.zmpp.ExecutionControl;
import org.zmpp.blorb.NativeImage;
import org.zmpp.blorb.NativeImageFactory;
import org.zmpp.iff.FormChunk;
import org.zmpp.iff.WritableFormChunk;
import org.zmpp.io.IOSystem;
import org.zmpp.vm.InvalidStoryException;
import org.zmpp.vm.MachineRunState;
import org.zmpp.vm.SaveGameDataStore;
import org.zmpp.vm.MachineFactory.MachineInitStruct;
import org.zmpp.windowing.*;
import org.zmpp.windowing.BufferedScreenModel.StatusLineListener;

public class KifKindlet implements Kindlet, StatusLine, StatusLineListener, NativeImageFactory, SaveGameDataStore, IOSystem, Runnable {
	private Container root;
	
	private KindletContext ctx;
	
	private Component noGameLoadedComponent;
	private InfocomGamePanel gameComponent;
	private static Logger logger;
	
	private static final int inset = 10;
	
	private ExecutionControl executionControl;
	private BufferedScreenModel screenModel;
	private MachineRunState runState;
	private StringBuffer inputBuffer = new StringBuffer();
	private String input = null;

	
	public synchronized Logger getLogger() {
		if (logger != null)
			return logger;
		
		logger = Logger.getLogger("kif");
		try {
			logger.addAppender(new FileAppender(new PatternLayout("%m%n"), new File(ctx.getHomeDirectory(), "log.txt").getAbsolutePath()));		
		} catch (Throwable t) {
		}
		return logger;
	}
	
	public void create(KindletContext context) {
		this.ctx = context;
		this.root = ctx.getRootContainer();
		this.noGameLoadedComponent = createNoGameLoaded();
		this.gameComponent = createGameDisplay();
		
	    this.screenModel = new BufferedScreenModel();
	    this.screenModel.addStatusLineListener(this);
	    this.screenModel.addScreenModelListener(this.gameComponent);
		
		showMainComponent();
		installGlobalKeyHandler();
		ctx.setMenu(createMenu());
	}

	public void start() {
	}
	
	public void destroy() {
	}

	public void stop() {
	}

	
	
	public KindletContext getContext() {
		return ctx;
	}
	
	private InfocomGamePanel createGameDisplay() {
		return new InfocomGamePanel(this);
	}

	private Component createNoGameLoaded() {
		KLabelMultiline label = new KLabelMultiline("Kif - an Infocom interpreter for the Kindle\nPlease press Menu to open a game");
		label.setVerticalAlignment(KTextComponent.TOP);
		label.setMargin(new Insets(inset, inset, inset, inset));
		return label;
	}

	private KMenu createMenu() {
		KMenu menu = new KMenu();
		
		KMenuItem menuItem = new KMenuItem("Open game");
		menuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				showGameSelector();
			}			
		});
		menu.add(menuItem);
		
		return menu;
	}

	private void installGlobalKeyHandler() {
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(new KeyEventDispatcher() {
			
			public boolean dispatchKeyEvent(KeyEvent e) {
				// if we're not on the main screen when we get a "BACK", trap it and return to the main game screen
				if (e.getKeyCode() == KindleKeyCodes.VK_BACK) {
					if ((root.getComponent(0) != gameComponent) && (root.getComponent(0) != noGameLoadedComponent)) {
						showMainComponent();
						return true;
					}
				}
				
				return false;
			}
		});
	}
	
	private void showGameSelector() {
		showSubComponent(new GameSelectorPanel(this), "Please select a game to play");
	}
	
	private void showSubComponent(Component component, String title) {
        root.removeAll();
        
        root.add(component);
        component.requestFocus();
        
        ctx.setSubTitle(title);
	}
	
	private void showMainComponent() {
		root.removeAll();
		
		if (noGameLoadedComponent != null) {
			root.add(noGameLoadedComponent);
			noGameLoadedComponent.setLocation(0, 0);
			noGameLoadedComponent.setSize(root.getWidth(), root.getHeight());
			noGameLoadedComponent.requestFocus();
		} else {
			root.add(gameComponent);
			gameComponent.setLocation(0, 0);
			gameComponent.setSize(root.getWidth(), root.getHeight());
			gameComponent.focus();
		}
		
		ctx.setSubTitle("");
	}
	
	public void loadGame(String gameFilename)
	{
		noGameLoadedComponent = null;
		showMainComponent();
		gameComponent.reset();
		inputBuffer.setLength(0);
				
	    try {
	    	if (gameFilename.endsWith(".blorb"))
	    		startGame(null, new FileInputStream(new File(ctx.getHomeDirectory(), gameFilename)));
	    	else
	    		startGame(new FileInputStream(new File(ctx.getHomeDirectory(), gameFilename)), null);

	    } catch (Throwable t) {
	    	getLogger().error("Failed to load game", t);
	    	try {
	    		KOptionPane.showConfirmDialog(root, "Failed to start game " + t.getMessage(), "Error");
	    	} catch (Throwable t2) {
	    	}
	    }		
	}
	
	private void startGame(InputStream storyStream, InputStream blorbStream) throws IOException, InvalidStoryException
	{
	    MachineInitStruct initStruct = new MachineInitStruct();
	    initStruct.blorbFile = blorbStream;
	    initStruct.storyFile = storyStream;
	    initStruct.ioSystem = this;
	    initStruct.nativeImageFactory = this;
	    initStruct.saveGameDataStore = this;
	    initStruct.screenModel = screenModel;
//	    initStruct.soundEffectFactory = ?;
	    initStruct.statusLine = this;

		executionControl = new ExecutionControl(initStruct);
		screenModel.init(executionControl.getMachine(), executionControl.getZsciiEncoding());
	    executionControl.setDefaultColors(ScreenModel.COLOR_WHITE, ScreenModel.COLOR_BLACK);
	    updateScreenSize();

	    new Thread(this).start();
	}

	private void updateScreenSize() {
		/*
		((BufferedScreenModel) executionControl.getMachine().getScreen()).setNumCharsPerRow(gameComponent.getTopCols());
		executionControl.resizeScreen(gameComponent.getTopRows(), gameComponent.getTopCols());
		*/
		
		// FIXME: need to figure this out!
		
		((BufferedScreenModel) executionControl.getMachine().getScreen()).setNumCharsPerRow(20);
		executionControl.resizeScreen(20, 20);
	}

	public void statusLineUpdated(String objectDescription, String status) {
		ctx.setSubTitle(objectDescription + " " + status);
	}

	public void updateStatusScore(String objectName, int score, int steps) {
	    ctx.setSubTitle(objectName + " " + score + "/" + steps);
	}

	public void updateStatusTime(String objectName, int hours, int minutes) {
	    ctx.setSubTitle(objectName + " " + hours + ":" + minutes);
	}

	public NativeImage createImage(InputStream inputStream) throws IOException {	
		// whee, nasty hacking ahead!
		
		// load the image from the input stream
		Image img = Toolkit.getDefaultToolkit().createImage(new HackImageSource(inputStream));		
		if (img == null)
			return null;
		
		return new AwtImage(((sun.awt.image.BufferedImagePeer) img).getSubimage(0, 0, img.getWidth(null), img.getHeight(null)));
	}
	
	/**
	 * Nasty hack in order to load an image from.. wait for it... an inputstream! What a /revolutionary/ concept.
	 * 
	 * @author adq
	 */
	public static class HackImageSource extends sun.awt.image.FileImageSource {

		private InputStream is;
		
		public HackImageSource(String filename) {
			super(filename);
		}

		public HackImageSource(InputStream is) {
			super("");
			this.is = is;
		}
		
		protected sun.awt.image.ImageDecoder getDecoder() {
	        return getDecoder(new BufferedInputStream(is));
		}
	}
	

	public boolean saveFormChunk(WritableFormChunk formchunk) {
		// FIXME: popup a save dialog
		
		// TODO Auto-generated method stub
		return false;
	}

	public FormChunk retrieveFormChunk() {
		// FIXME: popup a load dialog
		
		// TODO Auto-generated method stub
		return null;
	}

	public Writer getTranscriptWriter() {
		// FIXME: popup a save dialog

		// TODO Auto-generated method stub
		return null;
	}

	public Reader getInputStreamReader() {
		// FIXME: popup a load dialog

		// TODO Auto-generated method stub
		return null;
	}

	public void run() {
		try {
			if (input != null)
			    runState = executionControl.resumeWithInput(input);
			else
				runState = executionControl.run();
		} catch (Throwable t) {
			getLogger().error("", t);
		}
	}
	
	public void input(String i) {
		input = i;
		updateScreenSize();
	    new Thread(this).start();
	}
	
	public boolean inCharMode() {
		if ((runState != null) && (runState.isReadChar()))
			return true;
		return false;
	}
}
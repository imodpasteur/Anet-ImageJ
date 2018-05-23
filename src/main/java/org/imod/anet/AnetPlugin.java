/*
 * To the extent possible under law, the ImageJ developers have waived
 * all copyright and related or neighboring rights to this tutorial code.
 *
 * See the CC0 1.0 Universal license for details:
 *     http://creativecommons.org/publicdomain/zero/1.0/
 */

package org.imod.anet;

import ij.IJ;
import ij.Prefs;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij.process.FloatProcessor;
import ij.WindowManager;
import org.imod.anet.AnetPredict;

import java.awt.Button;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.Panel;
import java.awt.FlowLayout;

import java.io.File;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Paths;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;

import io.crossbar.autobahn.wamp.Client;
import io.crossbar.autobahn.wamp.Session;
import io.crossbar.autobahn.wamp.types.CallResult;
import io.crossbar.autobahn.wamp.types.CloseDetails;
import io.crossbar.autobahn.wamp.types.ExitInfo;
import io.crossbar.autobahn.wamp.types.InvocationDetails;
import io.crossbar.autobahn.wamp.types.Publication;
import io.crossbar.autobahn.wamp.types.PublishOptions;
import io.crossbar.autobahn.wamp.types.Registration;
import io.crossbar.autobahn.wamp.types.SessionDetails;
import io.crossbar.autobahn.wamp.types.Subscription;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.util.Iterator;
import java.io.FileReader;
/**
 * A template for processing each pixel of either
 * GRAY8, GRAY16, GRAY32 or COLOR_RGB images.
 *
 * @author Johannes Schindelin
 */
public class AnetPlugin implements PlugIn {
	protected ImagePlus image;

	// image property members
	private int width;
	private int height;
	private static final int BUFFER_SIZE = 4096;
	private boolean cancel_flag = false;

	// plugin parameters
	public int input_size;
	public String mode;
	private AnetPredict ap;
	private Session session;
	public String model_path;
	public String model_name;
	Map<String, Object> inputs;
	Map<String, Object> outputs;

	String[] image_window_titles;
	Map<String, ImagePlus> image_window_map;

	@Override
	public void run(String arg) {
		// get width and height
		if (arg.equals("about")) {
			showAbout();
			return;
		}
		ap = new AnetPredict();
		if(arg.equals("setup")){
			showMainDialog(false);
		}
		else if(arg == null || arg.equals("run")){
			String _model = Prefs.get("ANET.default_model", "none");
			File folder = new File("anet-models");
			File[] listOfFiles = folder.listFiles();
			if(_model.equals("none") || listOfFiles == null || listOfFiles.length == 0){
				showMainDialog(true);
			}
			else{
				showModelDialog(_model);
			}
		}
		else{
			showModelDialog(arg);
		}
	}
	private void closeDialog(GenericDialog gd){
		gd.windowClosing(null);
	}

	private boolean showMainDialog(boolean start) {
		GenericDialog gd = new GenericDialog("A-Net Process");
		// Create download button
		Button btDownload = new Button("Download models");
		btDownload.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e)
				{
						downloadModels();
						closeDialog(gd);
						showMainDialog(false);
				}
		});
		// Add and show button
		gd.add(btDownload);

		List<String> modelList = new ArrayList<String>();
		File folder = new File("anet-models");
		File[] listOfFiles = folder.listFiles();
		if(listOfFiles == null || listOfFiles.length == 0){
			modelList.add("no model available.");
		}
		else{
			for (int i = 0; i < listOfFiles.length; i++) {
				if (listOfFiles[i].isFile()) {

				} else if (listOfFiles[i].isDirectory()) {
					modelList.add(listOfFiles[i].getName());
				}
			}
		}

		String[] items = (String[]) modelList.toArray(new String[modelList.size()]);

		gd.addRadioButtonGroup("Available models", items, items.length, 1, items[0]);
		gd.addMessage("Please place the model files into the 'anet-models' folder (next to the 'plugin' folder in FIJI/ImageJ).");
		if(listOfFiles != null && listOfFiles.length > 0 ){
			// Create select button
			Button btRun = new Button("Run");
			btRun.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent e)
					{
					  	closeDialog(gd);
							String model_name = gd.getNextRadioButton();
							showModelDialog(model_name);
					}
			});
			// Add and show button
			Panel panel = new Panel();
			panel.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
			panel.add(btRun);
			gd.add(panel);
		}
		gd.showDialog();
		if (gd.wasCanceled())
			return false;
		// get entered values
		model_name = gd.getNextRadioButton();
		if(!model_name.equals("no model available.")){
			Prefs.set("ANET.default_model", model_name);
			IJ.showStatus("selected model:" + model_name);
		}
		else{
			return false;
		}

		if(start)
			return showModelDialog(model_name);
		else{
			IJ.showMessage("A-Net",
				"Now you can use the selected model by click 'Run A-net' in the menu."
			);
		}
		  return true;
	}

	private boolean showModelDialog(String model_name){
		model_path = Paths.get(IJ.getDirectory("imagej"), "anet-models/" + model_name).toString();
		File fm = new File(model_path+"/tensorflow_model.pb");
		File fc = new File(model_path+"/config.json");
		if(fm.exists() && !fm.isDirectory() && fc.exists() && !fc.isDirectory()) {
				IJ.showStatus("loading model from "+model_path);
				ap.loadModel(model_path);
		}
		else{
			IJ.showMessage("A-Net",
				"invalid model file. Please use a valid model(see https://annapalm.pasteur.fr for more details). "
			);
			showMainDialog(true);
		}

		GenericDialog gd = new GenericDialog(String.format("A-Net (%s)", model_name));
		// gd.hideCancelButton();
		inputs = new HashMap<String, Object>();
		outputs = new HashMap<String, Object>();
		model_path = Paths.get(IJ.getDirectory("imagej"), "anet-models/" + model_name).toString();
		// build image window map
		int[] list = WindowManager.getIDList();
		if(list != null){
			image_window_titles = new String[list.length+1];
			image_window_map = new HashMap<String, ImagePlus>();
			for (int i=0; i<list.length; i++) {
				ImagePlus imp = WindowManager.getImage(list[i]);
				image_window_titles[i] = imp.getTitle();
				image_window_map.put(image_window_titles[i], imp);
			}
		}
		else{
			image_window_titles = new String[1];
			image_window_map = new HashMap<String, ImagePlus>();
		}
		image_window_titles[image_window_titles.length-1] = "";

		if(parseConfig("build_gui", inputs, outputs, gd)){
			// Create select button
			Button btSelect = new Button("select another model");
			btSelect.addActionListener(new ActionListener() {
					public void actionPerformed(ActionEvent e)
					{

							closeDialog(gd);
							showMainDialog(false);
					}
			});
			Panel panel = new Panel();
			panel.setLayout(new FlowLayout(FlowLayout.LEFT, 0, 0));
			panel.add(btSelect);
			// Add and show button
			gd.add(panel);

			gd.showDialog();
			if (gd.wasCanceled())
				return false;
			else{
				if(parseConfig("fetch_inputs", inputs, outputs, gd))
					process();
			}
			return true;
		}
		else
			return false;
	}

	public boolean parseConfig(String stage, Map<String, Object> inputs, Map<String, Object> outputs, GenericDialog gd) {
		JSONParser jsonParser = new JSONParser();
		try{
			Object obj = jsonParser.parse(new FileReader(Paths.get(model_path, "config.json").toString()));
			JSONObject config = (JSONObject) obj;
			String model_label = (String) config.get("label");
			if(stage.equals("show_outputs")){
				// fetch outputs dictionary
				JSONArray outputs_c = (JSONArray) config.get("outputs");
				Iterator<JSONObject> iterator = outputs_c.iterator();
				while (iterator.hasNext()) {
						JSONObject output = iterator.next();
						JSONArray shape_o = (JSONArray) output.get("shape");
						int s = shape_o.size();
						int[] shape = null;
						if(s>0){
							shape = new int[s];
							for(int i=0;i<s;i++){
								shape[i] = ((Long) shape_o.get(i)).intValue();
							}
						}
						String key = (String) output.get("key") + ":" + Integer.toString(s);
						if(s == 4){
							float[][][][] _output = (float[][][][]) outputs.get(key);
							JSONArray chs = (JSONArray) output.get("channels");
							assert chs.size() == shape[3];
							for(int b=0; b<shape[0]; b++)
							for(int c=0; c<chs.size();c++){
								float[] pixels_output = new float[shape[1]*shape[2]];
								for(int i=0;i<shape[1];i++){
									for(int j=0;j<shape[2];j++){
										pixels_output[i*shape[2]+j] = _output[b][i][j][c];
									}
								}
								new ImagePlus(String.format("%s (%s_%s_batch%d)", model_label, key, (String)chs.get(c), b), new FloatProcessor(shape[1], shape[2], pixels_output, null)).show();
							}
						}
						else if(s == 3){
							float[][][] _output = (float[][][]) outputs.get(key);
							IJ.showStatus(String.format("%s (%s), float[][][] output(not implemented yet)", model_label, key));
						}
					  else if(s == 2){
							float[][] _output = (float[][]) outputs.get(key);
							IJ.showStatus(String.format("%s (%s), float[][] output(not implemented yet)", model_label, key));
						}
						else if(s == 1){
							float[] _output = (float[]) outputs.get(key);
							IJ.showStatus(String.format("%s (%s), float[] output(not implemented yet)", model_label, key));
						}
						else if(s == 0){
							float _output = (float) outputs.get(key);
							IJ.showStatus(String.format("%s (%s), float output(%f)", model_label, key, _output));
						}
						else{
							IJ.showStatus("invalid shape");
							return false;
						}
				}
			}
			else {
				// construct inputs dictionary
				JSONArray inputs_c = (JSONArray) config.get("inputs");
				Iterator<JSONObject> iterator = inputs_c.iterator();
				while (iterator.hasNext()) {
						JSONObject input = iterator.next();
						JSONArray shape_i = (JSONArray) input.get("shape");
						int s = shape_i.size();
						int[] shape = null;
						if(s>0){
							shape = new int[s];
							for(int i=0;i<s;i++){
								shape[i] = ((Long) shape_i.get(i)).intValue();
							}
						}

						String key = (String) input.get("key") + ":" + Integer.toString(s);
						if(stage.equals("fetch_inputs")){
							float d = (float)(double) input.get("default");
							if(s == 4) {
								float[][][][] dd = new float[shape[0]][shape[1]][shape[2]][shape[3]];
								if(d != 0.0f){
									for(int i=0;i<shape[0];i++)
										for(int j=0;j<shape[1];j++)
											for(int k=0;k<shape[2];k++)
												for(int l=0;l<shape[3];l++)
													dd[i][j][k][l] = d;
								}
								inputs.put(key, dd);
							}
							else if(s == 3) {
								float[][][] dd = new float[shape[0]][shape[1]][shape[2]];
								if(d != 0.0f){
									for(int i=0;i<shape[0];i++)
										for(int j=0;j<shape[1];j++)
											for(int k=0;k<shape[2];k++)
												dd[i][j][k] = d;
								}
								inputs.put(key, dd);
							}
						  else if(s == 2) {
								float[][] dd = new float[shape[0]][shape[1]];
								if(d != 0.0f){
									for(int i=0;i<shape[0];i++)
										for(int j=0;j<shape[1];j++)
											dd[i][j] = d;
								}
								inputs.put(key, dd);
							}
							else if(s == 1) {
								float[] dd = new float[shape[0]];
								if(d != 0.0f){
									for(int i=0;i<shape[0];i++)
											dd[i] = d;
								}
								inputs.put(key, dd);
							}
							else if(s == 0) inputs.put(key, d);
							else inputs.put(key, new float[shape[0]][shape[1]][shape[2]][shape[3]]);
						}

						String tp = (String) input.get("type");
						if(tp.equals("image")){
							JSONArray chs = (JSONArray) input.get("channels");
							for(int c=0; c<chs.size();c++){

								if(stage.equals("build_gui"))
									gd.addChoice((String)input.get("name")+"_"+(String)chs.get(c), image_window_titles, "");
								else if(stage.equals("fetch_inputs")){
									String input_image_str = gd.getNextChoice();
									if(!input_image_str.equals("")){
										ImagePlus image = image_window_map.get(input_image_str);
										int width = image.getWidth();
										int height = image.getHeight();
										if(height!=shape[1] || width!=shape[2]){
											IJ.showMessage("image size must be "+shape[1]+"x"+shape[2]+".");
											return false;
										}
										int type = image.getType();
										float[] pixels = null;
										if (type == ImagePlus.GRAY8)
											pixels = (float[]) image.getProcessor().convertToFloat().getPixels();
										else if (type == ImagePlus.GRAY16)
											pixels = (float[]) image.getProcessor().convertToFloat().getPixels();
										else if (type == ImagePlus.GRAY32)
											pixels = (float[]) image.getProcessor().getPixels();
										else if (type == ImagePlus.COLOR_RGB)
											throw new RuntimeException("not supported");
										else {
											throw new RuntimeException("not supported");
										}
										assert shape.length == 4;
										float[][][][] _input_ = (float[][][][])inputs.get(key);
										for(int i=0;i<shape[1];i++){
											for(int j=0;j<shape[2];j++){
												if(pixels != null) _input_[0][i][j][c] = pixels[i*shape[2]+j];
											}
										}
									}
									else{
										IJ.showStatus("no image selected for "+ (String)chs.get(c));
									}
							   }
							 }

						}
						else if(tp.equals("choice")){
							JSONObject opts = (JSONObject)input.get("options");
							String[] options = new String[opts.size()];
							Map<String, Float> value_map = new HashMap<String, Float>();
							int c = 0;
							for(Object k: opts.keySet()){
								double v = (double) opts.get((String)k);
								options[c] = (String)k;
								value_map.put((String)k, (float)v);
								c++;
							}

							if(stage.equals("build_gui"))
								gd.addChoice((String)input.get("name"), options, options[0]);
							else if(stage.equals("fetch_inputs")){
								String chv = gd.getNextChoice();
								float v = value_map.get(chv);
								if(shape.length == 4){
									float[][][][] _input_ = (float[][][][])inputs.get(key);
									_input_[0][0][0][0] = v;
								}
								else if(shape.length == 4){
									float[][][] _input_ = (float[][][])inputs.get(key);
									_input_[0][0][0] = v;
								}
								else if(shape.length == 4){
									float[][] _input_ = (float[][])inputs.get(key);
									_input_[0][0] = v;
								}
								else if(shape.length == 4){
									float[] _input_ = (float[])inputs.get(key);
									_input_[0] = v;
								}
								else{
									float _input_ = (float)inputs.get(key);
									_input_ = v;
								}
							}
						}


				}

				// construct outputs dictionary
				JSONArray outputs_c = (JSONArray) config.get("outputs");
				iterator = outputs_c.iterator();
				while (iterator.hasNext()) {
						JSONObject output = iterator.next();
						JSONArray shape_o = (JSONArray) output.get("shape");
						int s = shape_o.size();
						int[] shape = null;
						if(s>0){
							shape = new int[s];
							for(int i=0;i<s;i++){
								shape[i] = ((Long) shape_o.get(i)).intValue();
							}
						}
						String key = (String) output.get("key") + ":" + Integer.toString(s);
						if(s == 4) outputs.put(key, new float[shape[0]][shape[1]][shape[2]][shape[3]]);
						else if(s == 3) outputs.put(key, new float[shape[0]][shape[1]][shape[2]]);
					  else if(s == 2) outputs.put(key, new float[shape[0]][shape[1]]);
						else if(s == 1) outputs.put(key, new float[shape[0]]);
						else if(s == 0) outputs.put(key, 0.0f);
						else{
							IJ.showStatus("unsupported shape.");
							return false;
						};
				}
		  }

		}
		catch (Exception e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	public void process() {
		IJ.showStatus("processing with A-net...");
		ap.predict(inputs, outputs);
		IJ.showStatus("A-net finished, showing result...");
		parseConfig("show_outputs", inputs, outputs, null);
		IJ.showStatus("Done.");
	}

	public void showAbout() {
		IJ.showMessage("A-Net",
			"Process image with a A-Net using tensorflow. Author: Wei OUYANG @ Institut Pasteur, Imaging and Modeling Unit"
		);
	}

	public void saveFiles(String url, String file, boolean extract) throws IOException{
			HTTPDownloadUtil util = new HTTPDownloadUtil();
			util.downloadFile(url);
			IJ.showStatus(String.format("downloading file %s, size:%d", util.getFileName(), util.getContentLength()));
			InputStream inputStream = util.getInputStream();
      // opens an output stream to save into file
      FileOutputStream outputStream = new FileOutputStream(file);

      byte[] buffer = new byte[BUFFER_SIZE];
      int bytesRead = -1;
      long totalBytesRead = 0;
      double percentCompleted = 0.0;
      long fileSize = util.getContentLength();
      while ((bytesRead = inputStream.read(buffer)) != -1 && !cancel_flag) {
          outputStream.write(buffer, 0, bytesRead);
          totalBytesRead += bytesRead;
          percentCompleted = totalBytesRead * 1.0 / fileSize;
					IJ.showProgress(percentCompleted);
					IJ.showStatus(String.format("downloading %d bytes/%d bytes", totalBytesRead, fileSize));
      }
			IJ.showStatus("A-net model downloaded.");
      outputStream.close();
      util.disconnect();
			if(cancel_flag){
				File f = new File(file);
      	f.delete();
			}
			if(extract){
				util.extractFolder(file, "./anet-models");
				File tmp_file = new File(file);
				tmp_file.delete();
			}
  }

	public void downloadModels() {
		  String model_name = "tubulin_model_demo";
			IJ.showStatus("download " + model_name);
			IJ.showMessage("A-Net",
				"Please notice that downloading models may take a while, the model list will be updated when it finished."
			);
			String model_url = "https://github.com/imodpasteur/Anet-ImageJ/releases/download/0.2.2/models_v0.1.zip";
			IJ.showStatus("dowloading model from: " + model_url);
			String fileRoot = Paths.get(IJ.getDirectory("imagej"), "anet-models").toString();
			try {
					// returns pathnames for files and directory
					File folder = new File(fileRoot);
					// create
					folder.mkdirs();
			 } catch(Exception e3) {
					// if any error occurs
					e3.printStackTrace();
			 }
			try {
				  String model_path_str = Paths.get(fileRoot, "__tmp__.zip").toString();
					// save model file
					saveFiles(model_url, model_path_str, true);
					IJ.showStatus("model and config has been saved to " + fileRoot);
					IJ.showMessage("A-Net",
						"Models has been saved to 'anet-models' folder in ImageJ. Please run 'Setup A-net' again."
					);
			}
			catch (IOException e2) {
				 e2.printStackTrace();
			}



		}

	/**
	 * Main method for debugging.
	 *
	 * For debugging, it is convenient to have a method that starts ImageJ, loads
	 * an image and calls the plugin, e.g. after setting breakpoints.
	 *
	 * @param args unused
	 */
	public static void main(String[] args) {
		// set the plugins.dir property to make the plugin appear in the Plugins menu
		Class<?> clazz = AnetPlugin.class;
		String url = clazz.getResource("/" + clazz.getName().replace('.', '/') + ".class").toString();
		String pluginsDir = url.substring("file:".length(), url.length() - clazz.getName().length() - ".class".length());
		System.setProperty("plugins.dir", pluginsDir);

		// start ImageJ
		new ImageJ();

		// open the Clown sample
		ImagePlus image = IJ.openImage("http://imagej.net/images/clown.jpg");
		image.show();

		// run the plugin
		IJ.runPlugIn(clazz.getName(), "");
	}

}

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

import java.io.File;
import java.io.FileWriter;
import java.io.FileOutputStream;
import java.io.IOException;
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
		else if(arg.equals("download")){
			session = connect("wss://dai.pasteur.fr/ws", "realm1");
		}
		else if(arg == null || arg.equals("run")){
			String _model = Prefs.get("ANET.default_model", "none");
			if(_model.equals("none")){
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

	private boolean showMainDialog(boolean start) {
		GenericDialog gd = new GenericDialog("A-Net Process");
		// Create download button
		Button btDownload = new Button("download model");
		btDownload.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e)
				{
						session = connect("wss://dai.pasteur.fr/ws", "realm1");
				}
		});
		// Add and show button
		gd.add(btDownload);

		List<String> modelList = new ArrayList<String>();
		File folder = new File("models");
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
		gd.addRadioButtonGroup("models", items, items.length, 1, items[0]);
		gd.showDialog();
		if (gd.wasCanceled())
			return false;
		// get entered values
		model_name = gd.getNextRadioButton();
		if(!model_name.equals("no model available.")){
			Prefs.set("ANET.default_model", model_name);
			IJ.log("selected model:" + model_name);
		}
		if(start)
			return showModelDialog(model_name);
		else
		  return true;
	}

	private boolean showModelDialog(String model_name){
		model_path = Paths.get(IJ.getDirectory("imagej"), "models/" + model_name).toString();
		File fm = new File(model_path+"/tensorflow_model.pb");
		File fc = new File(model_path+"/config.json");
		if(fm.exists() && !fm.isDirectory() && fc.exists() && !fc.isDirectory()) {
				IJ.log("loading model from "+model_path);
				ap.loadModel(model_path);
		}
		else{
			IJ.showMessage("A-Net",
				"invalid model file."
			);
		}

		GenericDialog gd = new GenericDialog(String.format("A-Net (%s)", model_name));
		// gd.hideCancelButton();
		inputs = new HashMap<String, Object>();
		outputs = new HashMap<String, Object>();
		model_path = Paths.get(IJ.getDirectory("imagej"), "models/" + model_name).toString();
		// build image window map
		int[] list = WindowManager.getIDList();
		if(list != null){
			image_window_titles = new String[list.length+1];
			image_window_map = new HashMap<String, ImagePlus>();
			for (int i=0; i<list.length; i++) {
				ImagePlus imp = WindowManager.getImage(list[i]);
				image_window_titles[i] = Integer.toString(i)+". "+imp.getTitle();
				image_window_map.put(image_window_titles[i], imp);
			}
		}
		else{
			image_window_titles = new String[1];
			image_window_map = new HashMap<String, ImagePlus>();
		}
		image_window_titles[image_window_titles.length-1] = "";

		if(parseConfig("build_gui", inputs, outputs, gd)){
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

	private boolean showDialog2() {
		GenericDialog gd = new GenericDialog("A-Net Process");

		List<String> modelList = new ArrayList<String>();
		File folder = new File("models");
		File[] listOfFiles = folder.listFiles();
    for (int i = 0; i < listOfFiles.length; i++) {
      if (listOfFiles[i].isFile()) {

      } else if (listOfFiles[i].isDirectory()) {
				IJ.log(listOfFiles[i].getName());
				modelList.add(listOfFiles[i].getName());
      }
    }
		if(modelList.size()<=0){
			modelList.add("no model available.");
		}
		String[] items = (String[]) modelList.toArray(new String[modelList.size()]);
		gd.addRadioButtonGroup("models", items, items.length, 1, items[0]);

		String[] structure_types = {"tubulin", "nuclear_pore", "actin", "mitochondria","unknown"};
		// gd.addStringField("mode", "tubulin");
		gd.addChoice("mode:", structure_types, "tubulin");

		// default value is 0.00, 2 digits right of the decimal point
		gd.addNumericField("input_size", 512, 0);

		// Create custom button
		Button bt = new Button("download model");
		bt.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e)
				{
						session = connect("wss://dai.pasteur.fr/ws", "realm1");
				}
		});
		// Add and show button
		gd.add(bt);

		gd.showDialog();
		if (gd.wasCanceled())
			return false;

		if (modelList.size()<=0){
			// check if there is any new file
			for (int i = 0; i < listOfFiles.length; i++) {
	      if (listOfFiles[i].isFile()) {
	      } else if (listOfFiles[i].isDirectory()) {
					modelList.add(listOfFiles[i].getName());
	      }
	    }
			if(modelList.size()>0){
				IJ.showMessage("A-Net",
					"Please try again."
				);
			}
			return false;
		}
		// get entered values
		model_name = gd.getNextRadioButton();
		mode = gd.getNextChoice();//gd.getNextString();
		input_size = (int) gd.getNextNumber();
		IJ.log("using model " + model_name);
		IJ.log("using mode " + mode);
		model_path = Paths.get(IJ.getDirectory("imagej"), "models/" + model_name).toString();
		File f = new File(model_path+"/tensorflow_model.pb");
		if(f.exists() && !f.isDirectory()) {
				IJ.log(model_path);
				// modelPath = "/Users/weiouyang/workspace/tensorflow-java/A-NET-npc-tubulin-946b/";
				ap.loadModel(model_path);
				return true; //
		}
		else{
			IJ.showMessage("A-Net",
				"invalid model file."
			);
			return false;
		}

	}

	private boolean showDownloadDialog(List<String> models) {
		GenericDialog gd = new GenericDialog("A-net model download");


		for (String model_name : models) {
			 gd.addCheckbox(model_name, false);
		}
		gd.showDialog();
		if (gd.wasCanceled())
			return false;

		String selected_models_str = "";
		for (String model_name : models) {
			 if(gd.getNextBoolean())
			 selected_models_str = model_name + "," + selected_models_str;
		}
		List<String> selected_model_names = Arrays.asList(selected_models_str.split(","));
		for(String model_name: selected_model_names){
			IJ.log("download " + model_name);
			// here we CALL every second
			CompletableFuture<CallResult> fGet = session.call("org.imod.public.models.get_download_url", model_name);
			fGet.whenComplete((callResultGet, throwableGet) -> {
					if (throwableGet == null) {
							IJ.log(String.format("model url: %s, ", callResultGet.results.get(0)));
							Map model_dict = (Map) callResultGet.results.get(0);
							String model_url = (String) model_dict.get("url");


							IJ.log("dowloading model from: " + model_url);
							String fileRoot = Paths.get(IJ.getDirectory("imagej"), "models", model_name).toString();
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
								  String model_path_str = Paths.get(fileRoot, "tensorflow_model.pb").toString();
									// save config.json
									JSONObject obj = new JSONObject();
		    					obj.putAll(model_dict);
									try (FileWriter file = new FileWriter(Paths.get(fileRoot, "config.json").toString())) {
										file.write(obj.toJSONString());
									}
									// save model file
									saveFile(model_url, model_path_str);

									IJ.log("model and config has been saved to " + fileRoot);
							}
							catch (IOException e2) {
								 e2.printStackTrace();
							}
					} else {
							IJ.log(String.format("ERROR - call failed: %s", throwableGet.getMessage()));
					}
			});
		}
		return true;
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
							IJ.log(String.format("%s (%s), float[][][] output(not implemented yet)", model_label, key));
						}
					  else if(s == 2){
							float[][] _output = (float[][]) outputs.get(key);
							IJ.log(String.format("%s (%s), float[][] output(not implemented yet)", model_label, key));
						}
						else if(s == 1){
							float[] _output = (float[]) outputs.get(key);
							IJ.log(String.format("%s (%s), float[] output(not implemented yet)", model_label, key));
						}
						else if(s == 0){
							float _output = (float) outputs.get(key);
							IJ.log(String.format("%s (%s), float output(%f)", model_label, key, _output));
						}
						else{
							IJ.log("invalid shape");
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
										if(height!=512 || width!=512){
											IJ.showMessage("image size must be 512x512.");
											return false;
										}
										assert width == 512 && height == 512 ;
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
										IJ.log("no image selected for "+ (String)chs.get(c));
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
							IJ.log("unsupported shape.");
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
		IJ.log("processing with A-net...");
		ap.predict(inputs, outputs);
		IJ.log("A-net finished, showing result...");
		parseConfig("show_outputs", inputs, outputs, null);
		IJ.log("Done.");
	}

	public void showAbout() {
		IJ.showMessage("A-Net",
			"Process image with a A-Net using tensorflow. Author: Wei OUYANG @ Institut Pasteur, Imaging and Modeling Unit"
		);
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

	public Session connect(String websocketURL, String realm) {
			Session session = new Session();
			session.addOnConnectListener(this::onConnectCallback);
			session.addOnJoinListener(this::onJoinCallback);
			session.addOnLeaveListener(this::onLeaveCallback);
			session.addOnDisconnectListener(this::onDisconnectCallback);

			// finally, provide everything to a Client instance and connect
			Client client = new Client(session, websocketURL, realm);
			client.connect();
			return session;
	}

	private void onConnectCallback(Session session) {
        IJ.log("Session connected, ID=" + session.getID());
    }

    private void onJoinCallback(Session session, SessionDetails details) {
				IJ.log("Joined, ID=" + session.getID());
				CompletableFuture<CallResult> fList = session.call("org.imod.public.models.list", "*");
				fList.whenComplete((callResultList, throwableList) -> {
					if (throwableList == null) {
						String modelstr = (String) callResultList.results.get(0);
						IJ.log("models: " + modelstr);
						IJ.log(modelstr);
						List<String> model_names = Arrays.asList(modelstr.split(","));
						showDownloadDialog(model_names);
				  }
					else{
						IJ.showMessage("A-Net error",
							"can't get model list"
						);
					}
				});


    }

    private void onLeaveCallback(Session session, CloseDetails detail) {
        IJ.log(String.format("Left reason=%s, message=%s", detail.reason, detail.message));
    }

    private void onDisconnectCallback(Session session, boolean wasClean) {
        IJ.log(String.format("Session with ID=%s, disconnected.", session.getID()));
    }

		public static void saveFile (String url, String file) throws IOException{
			URL website = new URL(url);
			ReadableByteChannel rbc = Channels.newChannel(website.openStream());
			FileOutputStream fos = new FileOutputStream(file);
			fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
	}


}

/* Copyright 2016 The TensorFlow Authors. All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package org.imod.anet;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.tensorflow.DataType;
import org.tensorflow.Graph;
import org.tensorflow.Output;
import org.tensorflow.Session;
import org.tensorflow.Session.Runner;
import org.tensorflow.Tensor;
import org.tensorflow.TensorFlow;
import org.tensorflow.types.UInt8;

import java.util.Map;
import java.util.HashMap;

import ij.IJ;
/** Sample use of the TensorFlow Java API to label images using a pre-trained model. */
public class AnetPredict {
    private Graph graph=null;
    public boolean loadModel(String modelDir){
      byte[] graphDef = readAllBytesOrExit(Paths.get(modelDir, "tensorflow_model.pb"));
      this.graph = new Graph();
      this.graph.importGraphDef(graphDef);
      // this.session = new Session(g);
      return true;
    }
    public float[] predict(float[] sr_input, float[] wf_input, int size, String type) {
       float[][][][] _input_ = new float[1][size][size][2];
       float[][][][] _control_ = new float[1][1][1][1];
       float[][][][] _channel_mask_ = new float[1][1][1][2];

       if(type.equals("tubulin")) _control_[0][0][0][0] = 0.0f;
       else if(type.equals("nuclear_pore")) _control_[0][0][0][0] = 1.0f;
       else if(type.equals("actin")) _control_[0][0][0][0] = 2.0f;
       else if(type.equals("mitochondria")) _control_[0][0][0][0] = 3.0f;
       else _control_[0][0][0][0] = -1.0f;

       _channel_mask_[0][0][0][0] = 1.0f;
       _channel_mask_[0][0][0][1] = 1.0f;

       float _dropout_prob_ = 0.0f;
       for(int i=0;i<size;i++){
         for(int j=0;j<size;j++){
           if(sr_input != null) _input_[0][i][j][0] = sr_input[i*size+j];
           if(wf_input != null) _input_[0][i][j][1] = wf_input[i*size+j];
         }
       }
       float[][][][] _output = this.predict(_input_, _control_, _channel_mask_, _dropout_prob_);
       float[] output = new float[size*size];
       for(int i=0;i<size;i++){
         for(int j=0;j<size;j++){
           output[i*size+j] = _output[0][i][j][0];
         }
       }
       return output;
    }
    public float[][][][] predict(float[][][][] input, float[][][][] control, float[][][][] channel_mask, float dropout_prob) {
      // byte[] srImageBytes = readAllBytesOrExit(Paths.get(srImageFile));
      // byte[] wfImageBytes = readAllBytesOrExit(Paths.get(wfImageFile));

       // float[][][][] _input_ = new float[1][512][512][2];
       // float[][][][] _control_ = new float[1][1][1][1];
       // float[][][][] _channel_mask_ = new float[1][1][1][2];
       // float _dropout_prob_ = 0.5f;

       // DatasetTypeIDs = {'random': -1, 'tubulin': 0, 'nuclear_pore': 1, 'actin': 2, 'mitochondria': 3}
      Tensor<Float> _dropout_prob = Tensor.<Float>create(dropout_prob, Float.class);
      Tensor<Float> _input = Tensor.create(input, Float.class);
      Tensor<Float> _control = Tensor.create(control, Float.class);
      Tensor<Float> _channel_mask = Tensor.create(channel_mask, Float.class);
      int size = input[0].length;
      System.out.println(size);
      float[][][][] output = executeGraph(_input, _channel_mask, _control, _dropout_prob, size);
      return output;
    }

  private float[][][][] executeGraph(Tensor<Float> input, Tensor<Float> channel_mask, Tensor<Float> control, Tensor<Float> dropout_prob, int size) {

      try (
          Session s = new Session(this.graph);
          Tensor<Float> result = s.runner().feed("input", input).feed("control", control)
                                  .feed("channel_mask", channel_mask).feed("dropout_prob", dropout_prob)
                                  .fetch("output").run().get(0).expect(Float.class)) {
        final long[] rshape = result.shape();
        if (result.numDimensions() != 4) {
          throw new RuntimeException(
              String.format(
                  "Expected model to produce a [1 H W C] shaped tensor, instead it produced one with shape %s",
                  Arrays.toString(rshape)));
        }
        return result.copyTo(new float[1][size][size][1]);
      }

  }

  public void predict(Map<String, Object> inputs, Map<String, Object> outputs) {
      try (
          Session s = new Session(this.graph);
        ) {
          Runner runner = s.runner();
          for(String key: inputs.keySet()){
            String [] tmp = key.split(":");
            String pkey = tmp[0];
            int tn = Integer.parseInt(tmp[1]);
            Tensor<Float> t;
            if(tn == 4) t = Tensor.create((float[][][][]) inputs.get(key), Float.class);
            else if(tn == 3) t = Tensor.create((float[][][]) inputs.get(key), Float.class);
            else if(tn == 2) t = Tensor.create((float[][]) inputs.get(key), Float.class);
            else if(tn == 1) t = Tensor.create((float[]) inputs.get(key), Float.class);
            else if(tn == 0) t = Tensor.create((float) inputs.get(key), Float.class);
            else t = Tensor.create((float[][][][]) inputs.get(key), Float.class);

            runner.feed(pkey, t);
          }
          for(String key: outputs.keySet()){
            String [] tmp = key.split(":");
            String pkey = tmp[0];
            int tn = Integer.parseInt(tmp[1]);
            Tensor<Float> result = runner.fetch(pkey).run().get(0).expect(Float.class);
            final long[] rshape = result.shape();
            if (result.numDimensions() != tn) {
              throw new RuntimeException(
                  String.format(
                      "Expected model to produce a tensor with %d dimension(s), instead it produced one with shape %s",
                      tn, Arrays.toString(rshape)));
            }
            result.copyTo(outputs.get(key));
          }
      }

  }

  private static int maxIndex(float[] probabilities) {
    int best = 0;
    for (int i = 1; i < probabilities.length; ++i) {
      if (probabilities[i] > probabilities[best]) {
        best = i;
      }
    }
    return best;
  }

  private static byte[] readAllBytesOrExit(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      System.err.println("Failed to read [" + path + "]: " + e.getMessage());
      System.exit(1);
    }
    return null;
  }

  private static List<String> readAllLinesOrExit(Path path) {
    try {
      return Files.readAllLines(path, Charset.forName("UTF-8"));
    } catch (IOException e) {
      System.err.println("Failed to read [" + path + "]: " + e.getMessage());
      System.exit(0);
    }
    return null;
  }

  // In the fullness of time, equivalents of the methods of this class should be auto-generated from
  // the OpDefs linked into libtensorflow_jni.so. That would match what is done in other languages
  // like Python, C++ and Go.
  static class GraphBuilder {
    GraphBuilder(Graph g) {
      this.g = g;
    }

    Output<Float> div(Output<Float> x, Output<Float> y) {
      return binaryOp("Div", x, y);
    }

    <T> Output<T> sub(Output<T> x, Output<T> y) {
      return binaryOp("Sub", x, y);
    }

    <T> Output<Float> resizeBilinear(Output<T> images, Output<Integer> size) {
      return binaryOp3("ResizeBilinear", images, size);
    }

    <T> Output<T> expandDims(Output<T> input, Output<Integer> dim) {
      return binaryOp3("ExpandDims", input, dim);
    }

    <T, U> Output<U> cast(Output<T> value, Class<U> type) {
      DataType dtype = DataType.fromClass(type);
      return g.opBuilder("Cast", "Cast")
          .addInput(value)
          .setAttr("DstT", dtype)
          .build()
          .<U>output(0);
    }

    Output<UInt8> decodePng(Output<String> contents, long channels) {
      return g.opBuilder("DecodePng", "DecodePng")
          .addInput(contents)
          .setAttr("channels", channels)
          .build()
          .<UInt8>output(0);
    }

    Output<UInt8> decodeJpeg(Output<String> contents, long channels) {
      return g.opBuilder("DecodeJpeg", "DecodeJpeg")
          .addInput(contents)
          .setAttr("channels", channels)
          .build()
          .<UInt8>output(0);
    }

    <T> Output<T> constant(String name, Object value, Class<T> type) {
      try (Tensor<T> t = Tensor.<T>create(value, type)) {
        return g.opBuilder("Const", name)
            .setAttr("dtype", DataType.fromClass(type))
            .setAttr("value", t)
            .build()
            .<T>output(0);
      }
    }
    Output<String> constant(String name, byte[] value) {
      return this.constant(name, value, String.class);
    }

    Output<Integer> constant(String name, int value) {
      return this.constant(name, value, Integer.class);
    }

    Output<Integer> constant(String name, int[] value) {
      return this.constant(name, value, Integer.class);
    }

    Output<Float> constant(String name, float value) {
      return this.constant(name, value, Float.class);
    }

    private <T> Output<T> binaryOp(String type, Output<T> in1, Output<T> in2) {
      return g.opBuilder(type, type).addInput(in1).addInput(in2).build().<T>output(0);
    }

    private <T, U, V> Output<T> binaryOp3(String type, Output<U> in1, Output<V> in2) {
      return g.opBuilder(type, type).addInput(in1).addInput(in2).build().<T>output(0);
    }
    private Graph g;
  }
}

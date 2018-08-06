/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.computervision;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.media.Image;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.widget.ImageView;

import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.ux.ArFragment;

import java.nio.ByteBuffer;


/**
 * This is a simple example that demonstrates cpu image access with ARCore.
 */
public class ComputerVisionActivity extends AppCompatActivity {
  private static final String TAG = ComputerVisionActivity.class.getSimpleName();
  private final EdgeDetector edgeDetector = new EdgeDetector();
  Bitmap cameraBitmap;
  // Sceneform handles the rendering.
  private ArFragment arFragment;
  private ImageView imageView;
  private CpuImageDisplayRotationHelper cpuImageDisplayRotationHelper;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.sceneform_fragment);
    imageView = findViewById(R.id.cpu_image);

    cpuImageDisplayRotationHelper = new CpuImageDisplayRotationHelper(/*context=*/ this);

    initializeScene(arFragment.getArSceneView());
  }

  private void initializeScene(ArSceneView arSceneView) {
    // Setup a touch listener to control the texture splitter position.
    arSceneView.setOnTouchListener(this::onToggleEdgeImage);
    arSceneView.getScene().addOnUpdateListener(this::onSceneUpdate);
  }

  private boolean onToggleEdgeImage(View view, MotionEvent motionEvent) {
    if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
      if (imageView.getVisibility() == View.VISIBLE) {
        imageView.setVisibility(View.GONE);
      } else {
        imageView.setVisibility(View.VISIBLE);
      }
      return true;
    }
    return false;
  }

  private void onSceneUpdate(FrameTime frameTime) {
    Session session = arFragment.getArSceneView().getSession();
    if (session == null) {
      return;
    }

    renderProcessedImageCpuDirectAccess(arFragment.getArSceneView().getArFrame());
  }

  /* Demonstrates how to access a CPU image directly from ARCore. */
  private void renderProcessedImageCpuDirectAccess(Frame frame) {
    if (frame == null) {
      return;
    }

    // Only get the camera image if we are using it.
    if (imageView.getVisibility() != View.VISIBLE) {
      return;
    }

    try (Image image = frame.acquireCameraImage()) {
      if (image.getFormat() != ImageFormat.YUV_420_888) {
        throw new IllegalArgumentException(
                "Expected image in YUV_420_888 format, got format " + image.getFormat());
      }

      ByteBuffer processedImageBytesGrayscale =
              edgeDetector.detect(
                      image.getWidth(),
                      image.getHeight(),
                      image.getPlanes()[0].getRowStride(),
                      image.getPlanes()[0].getBuffer());

      if (cameraBitmap == null || cameraBitmap.getWidth() != image.getWidth() ||
              cameraBitmap.getHeight() != image.getHeight()) {
        cameraBitmap = Bitmap.createBitmap(image.getWidth(), image.getHeight(),
                Bitmap.Config.ALPHA_8);
      }
      // Copy the bits all the time.
      processedImageBytesGrayscale.rewind();
      cameraBitmap.copyPixelsFromBuffer(processedImageBytesGrayscale);

      // Scale to the screen, and rotate.
      Matrix matrix = new Matrix();
      int bitmapw = arFragment.getArSceneView().getWidth();
      int bitmaph = arFragment.getArSceneView().getHeight();
      int tmp;
      switch (cpuImageDisplayRotationHelper.getCameraToDisplayRotation()) {
        case Surface.ROTATION_90:
          matrix.postRotate(90);
          tmp = bitmapw;
          bitmapw = bitmaph;
          bitmaph = tmp;
          break;
        case Surface.ROTATION_180:
          matrix.postRotate(180);
          break;
        case Surface.ROTATION_270:
          matrix.postRotate(270);
          tmp = bitmapw;
          bitmapw = bitmaph;
          bitmaph = tmp;
      }

      Bitmap imageBitmap = Bitmap.createScaledBitmap(cameraBitmap, bitmapw,
              bitmaph,
              false);
      imageBitmap = Bitmap.createBitmap(imageBitmap, 0, 0, imageBitmap.getWidth(),
              imageBitmap.getHeight(), matrix, true);
      imageView.setImageBitmap(imageBitmap);

    } catch (NotYetAvailableException e) {
      imageView.setVisibility(View.INVISIBLE);
    }
  }
}

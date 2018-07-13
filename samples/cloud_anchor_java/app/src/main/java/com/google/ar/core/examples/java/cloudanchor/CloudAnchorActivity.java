/*
 * Copyright 2018 Google LLC
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

package com.google.ar.core.examples.java.cloudanchor;

import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.cloudanchor.sceneform.PointCloudNode;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.collision.Box;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Material;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.Renderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.TransformableNode;
import com.google.common.base.Preconditions;
import com.google.firebase.database.DatabaseError;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Main Activity for the Cloud Anchor Example
 * <p>
 * <p>This is a simple example that shows how to host and resolve anchors using ARCore Cloud Anchors
 * API calls. This app only has at most one anchor at a time, to focus more on the cloud aspect of
 * anchors.
 */
public class CloudAnchorActivity extends AppCompatActivity {
  private static final String TAG = CloudAnchorActivity.class.getSimpleName();
  private final SnackbarHelper snackbarHelper = new SnackbarHelper();
  private final CloudAnchorManager cloudManager = new CloudAnchorManager();
  // Sceneform handles the rendering.
  private ArFragment arFragment;
  private PointCloudNode pointCloudNode;
  private ModelRenderable andyRenderable;
  private AnchorNode anchorNode;
  private CompletableFuture<Material> material;
  private Map<String, Node> nodes = new HashMap<>();
  private Button hostButton;
  private Button resolveButton;
  private TextView roomCodeText;

  // Cloud Anchor Components.
  private FirebaseManager firebaseManager;
  private HostResolveMode currentMode;
  private RoomCodeAndCloudAnchorIdListener hostListener;

  /**
   * Returns {@code true} if and only if the hit can be used to create an Anchor reliably.
   */
  private static boolean shouldCreateAnchorWithHit(HitResult hit) {
    Trackable trackable = hit.getTrackable();
    if (trackable instanceof Plane) {
      // Check if the hit was within the plane's polygon.
      return ((Plane) trackable).isPoseInPolygon(hit.getHitPose());
    } else if (trackable instanceof Point) {
      // Check if the hit was against an oriented point.
      return ((Point) trackable).getOrientationMode() == OrientationMode.ESTIMATED_SURFACE_NORMAL;
    }
    return false;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.sceneform_fragment);


    initializeScene(arFragment.getArSceneView().getScene());

    // Initialize UI components.
    hostButton = findViewById(R.id.host_button);
    hostButton.setOnClickListener((view) -> onHostButtonPress());
    resolveButton = findViewById(R.id.resolve_button);
    resolveButton.setOnClickListener((view) -> onResolveButtonPress());
    roomCodeText = findViewById(R.id.room_code_text);

    // Initialize Cloud Anchor variables.
    firebaseManager = new FirebaseManager(this);
    currentMode = HostResolveMode.NONE;
  }

  private void initializeScene(Scene scene) {
    scene.setOnTouchListener(this::onTap);
    scene.setOnUpdateListener(this::onFrame);

    pointCloudNode = new PointCloudNode(this);
    scene.addChild(pointCloudNode);

    ModelRenderable.builder().setSource(this, Uri.parse("andy.sfb")).build()
            .thenAccept(renderable -> andyRenderable = renderable)
            .exceptionally(throwable -> {
              snackbarHelper.showError(this, "Exception loading model: " +
                      throwable.getMessage());
              return null;
            });

    material = MaterialFactory.makeOpaqueWithColor(this, new Color(.7f, 0, .5f));
  }


  private boolean onTap(HitTestResult hitTestResult, MotionEvent motionEvent) {

    // Only process taps when hosting.
    if (currentMode != HostResolveMode.HOSTING) {
      return false;
    }

    Frame frame = arFragment.getArSceneView().getArFrame();
    TrackingState cameraTrackingState = frame.getCamera().getTrackingState();
    // Only handle a tap if the anchor is currently null, the queued tap is non-null and the
    // camera is currently tracking.
    if (motionEvent != null && cameraTrackingState == TrackingState.TRACKING) {
      Preconditions.checkState(
              currentMode == HostResolveMode.HOSTING,
              "We should only be creating an anchor in hosting mode.");
      for (HitResult hit : frame.hitTest(motionEvent)) {
        if (shouldCreateAnchorWithHit(hit)) {
          Anchor newAnchor = hit.createAnchor();
          cloudManager.hostCloudAnchor(newAnchor, hostListener);
          if (anchorNode == null) {
            setNewAnchor(newAnchor);
            snackbarHelper.showMessage(this, getString(R.string.snackbar_anchor_placed));
          } else {
            placeCube(hit.getHitPose());
          }
          return true; // Only handle the first valid hit.
        }
      }
    }
    return false;
  }

  private Node makeCube(String name, Vector3 position) {
    if (!material.isDone()) {
      return null;
    }
    Renderable cube =
            ShapeFactory.makeCube(new Vector3(.1f, .1f, .1f), Vector3.zero(), material.getNow(null));

    Node node = new Node();
    node.setParent(anchorNode);
    node.setName(name);
    node.setLocalPosition(position);
    node.setRenderable(cube);

    return node;
  }

  private void placeCube(Pose pose) {
    if (!material.isDone()) {
      return;
    }
    Renderable cube =
            ShapeFactory.makeCube(new Vector3(.1f, .1f, .1f), Vector3.zero(), material.getNow(null));

    TransformableNode node = new TransformableNode(arFragment.getTransformationSystem());
      node.setParent(anchorNode);
    node.setWorldPosition(new Vector3(
            pose.tx(), pose.ty(), pose.tz()));
    node.setWorldRotation(new Quaternion(
            pose.qx(), pose.qy(), pose.qz(), pose.qw()
    ));
    node.setRenderable(cube);

    String name = "Cube_" + nodes.size();
    node.setName(name);
    nodes.put(name, node);
    storePositions();
  }

  private void storePositions() {
    List<Pair<String, Vector3>> positions = new ArrayList<>();
    for (Node node : anchorNode.getChildren()) {
      Vector3 p = node.getLocalPosition();
      positions.add(new Pair<>(node.getName(), p));
    }
    firebaseManager.storeRelativePositions(Long.parseLong(roomCodeText.getText().toString()), positions);
  }

  @Override
  protected void onResume() {
    super.onResume();

    // Setting the session in the HostManager.
    cloudManager.setSession(arFragment.getArSceneView().getSession());

    if (currentMode == HostResolveMode.NONE) {
      snackbarHelper.showMessage(this, getString(R.string.snackbar_initial_message));
    }
  }

  private void onFrame(FrameTime frameTime) {
    arFragment.onUpdate(frameTime);
    Frame frame = arFragment.getArSceneView().getArFrame();

    if (frame == null) {
      return;
    }

    Camera camera = frame.getCamera();
    Collection<Anchor> updatedAnchors = frame.getUpdatedAnchors();
    TrackingState cameraTrackingState = camera.getTrackingState();


    cloudManager.setSession(arFragment.getArSceneView().getSession());
    // Notify the cloudManager of all the updates.
    cloudManager.onUpdate(updatedAnchors);

    // If not tracking, don't draw 3d objects.
    if (cameraTrackingState == TrackingState.PAUSED) {
      return;
    }

    // Visualize tracked points.
    PointCloud pointCloud = frame.acquirePointCloud();
    pointCloudNode.update(pointCloud);

    // Application is responsible for releasing the point cloud resources after using it.
    pointCloud.release();

  }

  /**
   * Sets the new value of the current anchor. Detaches the old anchor, if it was non-null.
   */
  private void setNewAnchor(Anchor newAnchor) {


    AnchorNode newAnchorNode = null;

    if (anchorNode != null && newAnchor != null) {
      // Create a new anchor node and move the children over.
      newAnchorNode = new AnchorNode(newAnchor);
      newAnchorNode.setParent(arFragment.getArSceneView().getScene());
      List<Node> children = new ArrayList<>(anchorNode.getChildren());
      for (Node child : children) {
        child.setParent(newAnchorNode);
      }
    } else if (anchorNode == null && newAnchor != null) {
      // First anchor node created, add Andy as a child.
      newAnchorNode = new AnchorNode(newAnchor);
      newAnchorNode.setParent(arFragment.getArSceneView().getScene());

      Node andy = new Node();
      andy.setRenderable(andyRenderable);
      andy.setParent(newAnchorNode);

      if (arFragment.getArSceneView().isDebugEnabled()) {
        // Create node to display the bounds of the andy
        Node boundsNode = new Node();
        boundsNode.setParent(andy);
        MaterialFactory.makeTransparentWithColor(this, new Color(0.8f, 0.8f, 0.8f, 0.4f))
                .thenAccept(
                        material -> {
                          Box box = (Box) andyRenderable.getCollisionShape();
                          Renderable renderable =
                                  ShapeFactory.makeCube(box.getSize(), box.getCenter(), material);
                          renderable.setCollisionShape(null);
                          boundsNode.setRenderable(renderable);
                        });
      }
    } else {
      // Just clean up the anchor node.
      if (anchorNode != null && anchorNode.getAnchor() != null) {
        anchorNode.getAnchor().detach();
        anchorNode.setParent(null);
        anchorNode = null;
      }
    }

    anchorNode = newAnchorNode;

    // Last step is to reparent the child objects
    for (Node n : nodes.values()) {
      if (n.getParent() != anchorNode) {
        n.setParent(anchorNode);
      }
    }
  }

  /**
   * Callback function invoked when the Host Button is pressed.
   */
  private void onHostButtonPress() {
    if (currentMode == HostResolveMode.HOSTING) {
      resetMode();
      return;
    }

    if (hostListener != null) {
      return;
    }
    resolveButton.setEnabled(false);
    hostButton.setText(R.string.cancel);
    snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_host));

    hostListener = new RoomCodeAndCloudAnchorIdListener();
    firebaseManager.getNewRoomCode(hostListener);
  }

  /**
   * Callback function invoked when the Resolve Button is pressed.
   */
  private void onResolveButtonPress() {
    if (currentMode == HostResolveMode.RESOLVING) {
      resetMode();
      return;
    }
    ResolveDialogFragment dialogFragment = new ResolveDialogFragment();
    dialogFragment.setOkListener(this::onRoomCodeEntered);
    dialogFragment.show(getSupportFragmentManager(), "ResolveDialog");
  }

  /**
   * Resets the mode of the app to its initial state and removes the anchors.
   */
  private void resetMode() {
    hostButton.setText(R.string.host_button_text);
    hostButton.setEnabled(true);
    resolveButton.setText(R.string.resolve_button_text);
    resolveButton.setEnabled(true);
    roomCodeText.setText(R.string.initial_room_code);
    currentMode = HostResolveMode.NONE;
    firebaseManager.clearRoomListener();
    hostListener = null;
    setNewAnchor(null);
    snackbarHelper.hide(this);
    cloudManager.clearListeners();
  }

  /**
   * Callback function invoked when the user presses the OK button in the Resolve Dialog.
   */
  private void onRoomCodeEntered(Long roomCode) {
    currentMode = HostResolveMode.RESOLVING;
    hostButton.setEnabled(false);
    resolveButton.setText(R.string.cancel);
    roomCodeText.setText(String.valueOf(roomCode));
    snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_resolve));

    // Register a new listener for the given room.
    firebaseManager.registerNewListenerForRoom(
            roomCode,
            (cloudAnchorId) -> {
              // When the cloud anchor ID is available from Firebase.
              cloudManager.resolveCloudAnchor(
                      cloudAnchorId,
                      (anchor) -> {
                        // When the anchor has been resolved, or had a final error state.
                        CloudAnchorState cloudState = anchor.getCloudAnchorState();
                        if (cloudState.isError()) {
                          Log.w(
                                  TAG,
                                  "The anchor in room "
                                          + roomCode
                                          + " could not be resolved. The error state was "
                                          + cloudState);
                          snackbarHelper.showMessageWithDismiss(
                                  CloudAnchorActivity.this,
                                  getString(R.string.snackbar_resolve_error, cloudState));
                          return;
                        }
                        snackbarHelper.showMessageWithDismiss(
                                CloudAnchorActivity.this, getString(R.string.snackbar_resolve_success));
                        setNewAnchor(anchor);
                      });
            });

    firebaseManager.registerLocalPositionListener(roomCode,
            this::updateLocalPositions);

  }

  private void updateLocalPositions(List<Pair<String, Vector3>> positions) {

    for (Pair<String, Vector3> pos : positions) {
      String name = pos.first;
      Vector3 lp = pos.second;

      Node n = anchorNode == null ? null : anchorNode.findByName(name);
      if (n != null) {
        n.setLocalPosition(lp);
      } else {
        n = makeCube(name, lp);
        nodes.put(name, n);

      }
    }
  }

  private enum HostResolveMode {
    NONE,
    HOSTING,
    RESOLVING,
  }

  /**
   * Listens for both a new room code and an anchor ID, and shares the anchor ID in Firebase with
   * the room code when both are available.
   */
  private final class RoomCodeAndCloudAnchorIdListener
          implements CloudAnchorManager.CloudAnchorListener, FirebaseManager.RoomCodeListener {

    private Long roomCode;
    private String cloudAnchorId;

    @Override
    public void onNewRoomCode(Long newRoomCode) {
      Preconditions.checkState(roomCode == null, "The room code cannot have been set before.");
      roomCode = newRoomCode;
      roomCodeText.setText(String.valueOf(roomCode));
      snackbarHelper.showMessageWithDismiss(
              CloudAnchorActivity.this, getString(R.string.snackbar_room_code_available));
      checkAndMaybeShare();
      // Change currentMode to HOSTING after receiving the room code (not when the 'Host' button
      // is tapped), to prevent an anchor being placed before we know the room code and able to
      // share the anchor ID.
      currentMode = HostResolveMode.HOSTING;
    }

    @Override
    public void onError(DatabaseError error) {
      Log.w(TAG, "A Firebase database error happened.", error.toException());
      snackbarHelper.showError(
              CloudAnchorActivity.this, getString(R.string.snackbar_firebase_error));
    }

    @Override
    public void onCloudTaskComplete(Anchor anchor) {
      CloudAnchorState cloudState = anchor.getCloudAnchorState();
      if (cloudState.isError()) {
        Log.e(TAG, "Error hosting a cloud anchor, state " + cloudState);
        snackbarHelper.showMessageWithDismiss(
                CloudAnchorActivity.this, getString(R.string.snackbar_host_error, cloudState));
        return;
      }
      if (cloudAnchorId == null) {
        cloudAnchorId = anchor.getCloudAnchorId();
        runOnUiThread(() -> {
          setNewAnchor(anchor);
          checkAndMaybeShare();
        });
      }
    }

    private void checkAndMaybeShare() {
      if (roomCode == null || cloudAnchorId == null) {
        return;
      }
      firebaseManager.storeAnchorIdInRoom(roomCode, cloudAnchorId);
      storePositions();
      snackbarHelper.showMessageWithDismiss(
              CloudAnchorActivity.this, getString(R.string.snackbar_cloud_id_shared));
    }
  }
}

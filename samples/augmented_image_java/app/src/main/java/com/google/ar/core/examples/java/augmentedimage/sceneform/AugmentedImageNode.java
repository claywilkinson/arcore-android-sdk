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

package com.google.ar.core.examples.java.augmentedimage.sceneform;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.ar.core.AugmentedImage;
import com.google.ar.core.Pose;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ModelRenderable;

import java.util.concurrent.CompletableFuture;

public class AugmentedImageNode extends AnchorNode {

    private static final String TAG = "AugmentedImageNode";

    private AugmentedImage image;
    private static CompletableFuture<ModelRenderable> ulCorner;
    private static CompletableFuture<ModelRenderable> urCorner;
    private static CompletableFuture<ModelRenderable> lrCorner;
    private static CompletableFuture<ModelRenderable> llCorner;

    public AugmentedImageNode(Context context) {
        // Upon construction, start loading the models for the corners of the frame.
        if (ulCorner == null) {
            ulCorner = ModelRenderable.builder().setRegistryId("ulCorner")
                    .setSource(context, Uri.parse("models/frame_upper_left.sfb"))
                    .build();
            urCorner = ModelRenderable.builder().setRegistryId("ulCorner")
                    .setSource(context, Uri.parse("models/frame_upper_right.sfb"))
                    .build();
            llCorner = ModelRenderable.builder().setRegistryId("ulCorner")
                    .setSource(context, Uri.parse("models/frame_lower_left.sfb"))
                    .build();
            lrCorner = ModelRenderable.builder().setRegistryId("ulCorner")
                    .setSource(context, Uri.parse("models/frame_lower_right.sfb"))
                    .build();
        }
    }

    /**
     * Called when the AugmentedImage is detected and should be rendered. A Sceneform node tree is
     * created based on an Anchor created from the image.  The corners are then positioned based
     * on the extents of the image.  There is no need to worry about world coordinates since everything
     * is relative to the center of the image, which is the parent node of the corners.
     *
     * @param image
     */
    public void setImage(AugmentedImage image) {
        this.image = image;

        if (!ulCorner.isDone() || !urCorner.isDone() || !llCorner.isDone() || !lrCorner.isDone()) {
            CompletableFuture.allOf(ulCorner, urCorner, llCorner, lrCorner).thenAccept((Void aVoid) -> {
                setImage(image);
            })
                    .exceptionally(throwable -> {
                        Log.e(TAG, "Exception loading", throwable);
                        return null;
                    });
        }

        setAnchor(image.createAnchor(image.getCenterPose()));

        Node node = new Node();

        Pose p = Pose.makeTranslation(
                -0.5f * image.getExtentX(),
                0.0f,
                -0.5f * image.getExtentZ()); // upper left

        node.setParent(this);
        node.setLocalPosition(new Vector3(p.tx(), p.ty(), p.tz()));
        node.setLocalRotation(new Quaternion(p.qx(), p.qy(), p.qz(), p.qw()));
        node.setRenderable(ulCorner.getNow(null));

        p = Pose.makeTranslation(
                0.5f * image.getExtentX(),
                0.0f,
                -0.5f * image.getExtentZ()); // upper right

        node = new Node();
        node.setParent(this);
        node.setLocalPosition(new Vector3(p.tx(), p.ty(), p.tz()));
        node.setLocalRotation(new Quaternion(p.qx(), p.qy(), p.qz(), p.qw()));
        node.setRenderable(urCorner.getNow(null));


        p = Pose.makeTranslation(
                0.5f * image.getExtentX(),
                0.0f,
                0.5f * image.getExtentZ()); // lower right

        node = new Node();
        node.setParent(this);
        node.setLocalPosition(new Vector3(p.tx(), p.ty(), p.tz()));
        node.setLocalRotation(new Quaternion(p.qx(), p.qy(), p.qz(), p.qw()));
        node.setRenderable(lrCorner.getNow(null));


        p = Pose.makeTranslation(
                -0.5f * image.getExtentX(),
                0.0f,
                0.5f * image.getExtentZ()); // lower left

        node = new Node();
        node.setParent(this);
        node.setLocalPosition(new Vector3(p.tx(), p.ty(), p.tz()));
        node.setLocalRotation(new Quaternion(p.qx(), p.qy(), p.qz(), p.qw()));
        node.setRenderable(llCorner.getNow(null));

    }

    public AugmentedImage getImage() {
        return image;
    }
}

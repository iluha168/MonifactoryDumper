/**
 * Artifact format 2: a recipe's picture as layers of stills, each still stored once for the whole corpus.
 * <p>
 * {@code stills.pak} holds every distinct still as a lossless WebP, back to back in still-id order, and
 * {@code stills.json} says where each one is and how big it is ({@link StillTable}). A record in
 * {@code recipes.json} carries a {@link LayeredImage}: a canvas size and layers, each a box on the canvas and a
 * {@link Timeline} of still ids that loops on its own. {@link Compositor} turns that back into the picture at any tick.
 * <p>
 * The renderer writes all of it and {@code dumper/compare} reads it, so the types live here, where both can see them.
 * They write their own JSON by hand, as the renderer writes the rest of the artifact: this jar ships inside the mod and
 * carries no JSON library. Reading is left to {@code dumper/compare}, which already parses {@code recipes.json} with
 * Gson and builds these types from the values; their constructors check every rule the format has, so a reader
 * cannot build a record that breaks one.
 */
package com.iluha168.monifactory.imgencoder.layered;

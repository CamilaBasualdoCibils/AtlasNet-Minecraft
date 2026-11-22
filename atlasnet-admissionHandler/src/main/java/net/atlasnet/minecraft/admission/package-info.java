/**
 * Reserved for Minecraft admission during the AtlasNet ingress lifecycle.
 * Register a generated {@link AdmissionHandler} through
 * {@link Registry#registerAdmissionHandler(String, AdmissionHandler)} using
 * {@link Callbacks#admissionHandler(AdmissionHandler)} for boxed return ownership.
 */
package net.atlasnet.minecraft.admission;

import org.atlasnet.atlasnet.AdmissionHandler;
import org.atlasnet.atlasnet.Callbacks;
import org.atlasnet.atlasnet.Registry;

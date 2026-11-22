/**
 * Minecraft 1.21.1 online-mode Login and Configuration during AtlasNet ingress.
 * Register an {@link InteractiveAdmissionHandler} through
 * {@link Registry#registerInteractiveAdmissionHandler(String, InteractiveAdmissionHandler)}.
 * A handler finalizes admission through its {@link ClientAdmissionSession}.
 */
package net.atlasnet.minecraft.admission;

import org.atlasnet.atlasnet.ClientAdmissionSession;
import org.atlasnet.atlasnet.InteractiveAdmissionHandler;
import org.atlasnet.atlasnet.Registry;

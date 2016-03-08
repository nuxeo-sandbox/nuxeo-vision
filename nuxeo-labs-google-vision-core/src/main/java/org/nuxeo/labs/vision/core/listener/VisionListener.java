/*
 * (C) Copyright 2015-2016 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Michael Vachette
 */

package org.nuxeo.labs.vision.core.listener;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationChain;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.OperationException;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventContext;
import org.nuxeo.ecm.core.event.EventListener;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.labs.vision.core.service.GoogleVision;
import org.nuxeo.runtime.api.Framework;

import static org.nuxeo.ecm.platform.picture.api.ImagingDocumentConstants.PICTURE_FACET;


public class VisionListener implements EventListener {

    private static final Log log = LogFactory.getLog(VisionListener.class);

    @Override
    public void handleEvent(Event event) {
        EventContext ectx = event.getContext();
        if (!(ectx instanceof DocumentEventContext)) {
            return;
        }
        DocumentEventContext docCtx = (DocumentEventContext) ectx;
        DocumentModel doc = docCtx.getSourceDocument();
        if (!doc.hasFacet(PICTURE_FACET) || doc.isProxy()) return;

        GoogleVision visionService = Framework.getService(GoogleVision.class);
        String mapperChainName = visionService.getMapperChainName();

        AutomationService as = Framework.getService(AutomationService.class);
        OperationContext octx = new OperationContext();
        octx.setInput(doc);
        octx.setCoreSession(doc.getCoreSession());
        OperationChain chain = new OperationChain("VisionListenerChain");
        chain.add(mapperChainName);
        try {
            as.run(octx, chain);
        } catch (OperationException e) {
            log.warn(e);
        }
    }

}

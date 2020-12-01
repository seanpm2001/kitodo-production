/*
 * (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
 *
 * This file is part of the Kitodo project.
 *
 * It is licensed under GNU General Public License version 3 or later.
 *
 * For the full copyright and license information, please read the
 * GPL3-License.txt file that was distributed with this source code.
 */

package org.kitodo.production.services.command;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.kitodo.api.MdSec;
import org.kitodo.api.Metadata;
import org.kitodo.api.MetadataEntry;
import org.kitodo.api.dataformat.IncludedStructuralElement;
import org.kitodo.api.dataformat.Workpiece;
import org.kitodo.data.database.beans.Process;
import org.kitodo.data.elasticsearch.exceptions.CustomResponseException;
import org.kitodo.data.exceptions.DataException;
import org.kitodo.production.helper.metadata.legacytypeimplementations.LegacyMetsModsDigitalDocumentHelper;
import org.kitodo.production.services.ServiceManager;

public class AddDataScript extends EditDataScript {

    private static final Logger logger = LogManager.getLogger(AddDataScript.class);

    /**
     * Executes the given script on the given file for the given process.
     * @param metadataFile the file to edit
     * @param process the related process
     * @param metadataScript the script to execute
     */
    public void executeScript(LegacyMetsModsDigitalDocumentHelper metadataFile, Process process,
            MetadataScript metadataScript) {
        Workpiece workpiece = metadataFile.getWorkpiece();
        List<IncludedStructuralElement> allIncludedStructuralElements = workpiece.getAllIncludedStructuralElements();

        IncludedStructuralElement child = allIncludedStructuralElements.get(0);
        Collection<Metadata> metadataCollection = child.getMetadata();
        MdSec domain = null;
        for (Metadata metadata : metadataCollection) {
            domain = metadata.getDomain();
        }

        generateValueForMetadataScript(metadataScript, metadataCollection, process, metadataFile);

        MetadataEntry metadataEntry = new MetadataEntry();
        metadataEntry.setKey(metadataScript.getGoal());
        metadataEntry.setValue(metadataScript.getValue());
        metadataEntry.setDomain(domain);
        metadataCollection.add(metadataEntry);

        saveChanges(workpiece, process);
    }

}

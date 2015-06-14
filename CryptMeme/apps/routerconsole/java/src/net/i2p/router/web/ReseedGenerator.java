package net.i2p.router.web;

import java.io.File;
import java.io.IOException;

/**
 *  Handler to create a i2preseed.zip file
 *  @since 0.9.19
 */
public class ReseedGenerator extends HelperBase {

    public File createZip() throws IOException {
        ReseedBundler rb = new ReseedBundler(_context);
        return rb.createZip(100);
    }
}

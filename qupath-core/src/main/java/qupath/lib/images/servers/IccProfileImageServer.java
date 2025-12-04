/*-
 * #%L
 * This file is part of QuPath.
 * %%
 * Copyright (C) 2025 QuPath developers, The University of Edinburgh
 * %%
 * QuPath is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * QuPath is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QuPath.  If not, see <https://www.gnu.org/licenses/>.
 * #L%
 */


package qupath.lib.images.servers;

import qupath.lib.images.servers.icc.IccProfileTools;
import qupath.lib.images.servers.icc.IccProfileWrapper;
import qupath.lib.io.GsonTools;

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Map;

/**
 * {@link ImageServer} implementation that transforms colors by using a source and destination ICC profile.
 * @since v0.7.0
 */
public class IccProfileImageServer extends AbstractTileableImageServer {

    private final ImageServer<BufferedImage> server;
    private final IccProfileWrapper source;
    private final IccProfileWrapper dest;

    private transient volatile ColorConvertOp op;

    IccProfileImageServer(ImageServer<BufferedImage> server, IccProfileWrapper source, IccProfileWrapper dest) {
        super();
        this.server = server;
        this.source = source;
        this.dest = dest;
    }

    private ColorConvertOp getOp() throws IOException {
        if (op == null) {
            synchronized (this) {
                if (op == null) {
                    op = createOp();
                }
            }
        }
        return op;
    }

    private synchronized ColorConvertOp createOp() throws IOException {
        var sourceIcc = source.getProfile(server);
        var destIcc = dest == null ? ICC_Profile.getInstance(ICC_ColorSpace.CS_sRGB) : dest.getProfile(server);
        return new ColorConvertOp(
                new ICC_Profile[]{
                        sourceIcc,
                        destIcc
                },
                null
        );
    }

    /**
     * Get underlying ImageServer, i.e. the one that is being wrapped.
     * @return
     */
    protected ImageServer<BufferedImage> getWrappedServer() {
        return server;
    }

    @Override
    public Collection<URI> getURIs() {
        return getWrappedServer().getURIs();
    }

    @Override
    public String getServerType() {
        return "ICC profile server";
    }

    @Override
    public ImageServerMetadata getOriginalMetadata() {
        return server.getOriginalMetadata();
    }

    @Override
    protected BufferedImage readTile(TileRequest tileRequest) throws IOException {
        var img = getWrappedServer().readRegion(tileRequest.getRegionRequest());
        if (img == null)
            return null;
        else {
            IccProfileTools.applyInPlace(getOp(), img.getRaster());
            return img;
        }
    }

    @Override
    protected ImageServerBuilder.ServerBuilder<BufferedImage> createServerBuilder() {
        return new ImageServers.IccProfileImageServerBuilder(getMetadata(), getWrappedServer().getBuilder(), source, dest);
    }

    @Override
    protected String createID() {
        return "ICC: " + getWrappedServer().getPath() + " " + GsonTools.getInstance(false).toJson(Map.of("source", source, "dest", dest));
    }


}

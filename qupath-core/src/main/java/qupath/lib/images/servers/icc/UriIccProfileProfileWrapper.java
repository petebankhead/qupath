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

package qupath.lib.images.servers.icc;

import qupath.lib.images.servers.ImageServer;

import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ICC profile wrapper that reads the profile from a specified URI.
 * <p>
 * It is expected that the URI corresponds to a local file, and contains <i>only</i> the ICC profile
 * (i.e., it is not an image).
 * @since v0.7.0
 */
public class UriIccProfileProfileWrapper implements IccProfileWrapper {

    private final URI uri;

    public UriIccProfileProfileWrapper(URI uri) {
        this.uri = uri;
    }

    public UriIccProfileProfileWrapper(Path path) {
        this(path.toUri());
    }

    @Override
    public ICC_Profile getProfile(ImageServer<?> imageServer) throws IOException {
        return ICC_Profile.getInstance(Paths.get(uri).toString());
    }

    @Override
    public Collection<URI> getURIs() {
        return uri == null ? List.of() : List.of(uri);
    }

    @Override
    public IccProfileWrapper updateURIs(Map<URI, URI> replacements) {
        for (var entry : replacements.entrySet()) {
            if (Objects.equals(uri, entry.getKey()) && !Objects.equals(uri, entry.getValue())) {
                return new UriIccProfileProfileWrapper(entry.getValue());
            }
        }
        return this;
    }

}
